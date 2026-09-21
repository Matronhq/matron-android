package chat.matron.android.journal

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import chat.matron.android.journal.db.MatronDatabase
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Launch-performance work (apple #212): the write-path columns that replace
/// the read path's event sub-queries, insert-time tombstoning, and the
/// watermarked background sweeps. Ported from matron-apple's
/// `JournalStoreLaunchPerfTests`; the v7 migration itself is covered in
/// `MatronDatabaseMigrationTest`. Kept in its own file so the diff against
/// `JournalStoreTest` stays readable.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalStoreLaunchPerfTest {
    private lateinit var db: MatronDatabase

    private fun makeStore(): JournalStore {
        db = MatronDatabase.inMemory(ApplicationProvider.getApplicationContext())
        return JournalStore(db, ownSender = "user:dan")
    }

    /// `ts` is `seq` seconds after the epoch, exactly like `JournalStoreTest.ev`
    /// — so a test can make a row arbitrarily "old" relative to an injected
    /// `now` without wall-clock flake.
    private fun event(
        seq: Long,
        convo: String = "c1",
        sender: String = "agent:dev-2",
        type: String = JournalEventType.TEXT,
        payload: JsonObject = buildJsonObject { put("body", "hi") },
        ts: Long = seq * 1000,
    ) = JournalEvent(seq, convo, Instant.ofEpochMilli(ts), sender, type, payload)

    private fun ms(seconds: Long) = seconds * 1000
    private val hour = 3600_000L
    private val day = 24 * hour

    private suspend fun rawPayload(store: JournalStore, seq: Long): JsonObject =
        db.eventDao().byId(seq)!!.let { parseJsonObjectOrNull(it.payload)!! }

    private suspend fun row(id: String = "c1") = db.conversationDao().byId(id)!!

    // MARK: Write path keeps the columns current

    @Test
    fun applyJournalMaintainsLastMessageColumns() = runBlocking {
        val store = makeStore()
        val fresh = ms(10)
        store.applyJournal(event(1, type = JournalEventType.TEXT), now = fresh)
        assertEquals(JournalEventType.TEXT, row().lastMessageType)
        assertNull(row().expiredSnippet)

        store.applyJournal(
            event(2, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "make test"); put("live_log", true); put("snippet", "out")
            }),
            now = fresh,
        )
        assertEquals(JournalEventType.TOOL_OUTPUT, row().lastMessageType)
        assertEquals("$ make test", row().expiredSnippet)
        assertEquals("the live preview is still the real output while it is fresh", "out", row().snippet)

        // A bookkeeping frame is not a message: the columns must not move.
        store.applyJournal(
            event(3, sender = "user:dan", type = JournalEventType.READ_MARKER,
                payload = buildJsonObject { put("up_to_seq", 2) }),
            now = fresh,
        )
        assertEquals(JournalEventType.TOOL_OUTPUT, row().lastMessageType)
        assertEquals("$ make test", row().expiredSnippet)
    }

    /// The insert-time half of the watermark contract: a row that is already
    /// past a cutoff when it lands is stored tombstoned, so the sweeps can
    /// skip everything below their watermark and still be right.
    @Test
    fun applyJournalTombstonesAnAlreadyStaleToolOutputAtInsert() = runBlocking {
        val store = makeStore()
        store.applyJournal(
            event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "make test"); put("live_log", true); put("snippet", "out"); put("blob_ref", "b1")
            }),
            now = ms(1) + 25 * hour,
        )
        val stored = rawPayload(store, 1)
        assertFalse("a stale live log must land already tombstoned", "snippet" in stored)
        assertEquals(true, stored.boolOrNull("expired"))
        assertEquals("$ make test", row().expiredSnippet)
    }

    /// `conversation.snippet` is derived from the ORIGINAL wire payload, not
    /// the stored (possibly tombstoned) one — an arrival past its cutoff
    /// behaves exactly like a row that expires LATER, in place, where the
    /// read-time TTL is what hides it. `expired_snippet` still comes from the
    /// stored payload — it's what the read-time TTL substitutes IN.
    @Test
    fun applyJournalStoresOriginalSnippetForAlreadyStaleToolOutput() = runBlocking {
        val store = makeStore()
        store.applyJournal(
            event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "make test"); put("live_log", true); put("snippet", "out")
            }),
            now = ms(1) + 25 * hour,
        )
        assertEquals("conversation.snippet keeps the ORIGINAL output — in-place-expiry parity", "out", row().snippet)
        assertEquals("$ make test", row().expiredSnippet)
    }

    /// `diff` is also a message type with no `snippet()` case, and it has NO
    /// read-time override at all — so a stored-payload-derived snippet would
    /// show `[diff]` forever, not just transiently.
    @Test
    fun applyJournalStoresOriginalSnippetForAlreadyPastRetentionDiff() = runBlocking {
        val store = makeStore()
        store.applyJournal(
            event(1, type = JournalEventType.DIFF, payload = buildJsonObject {
                put("file_path", "/w/A.swift"); put("diff", "+ a"); put("snippet", "A.swift +1")
            }),
            now = ms(1) + 31 * day,
        )
        assertEquals("a diff keeps its original preview, never the [diff] placeholder", "A.swift +1", row().snippet)
        val stored = rawPayload(store, 1)
        assertFalse("diff" in stored)
        assertFalse("snippet" in stored)
        assertEquals(true, stored.boolOrNull("expired"))
    }

    /// An ALREADY absent `blob_ref` must stay absent through insert-time
    /// tombstoning, not gain a `null` entry it never had.
    @Test
    fun applyJournalLeavesAbsentBlobRefAbsentAtInsert() = runBlocking {
        val store = makeStore()
        store.applyJournal(
            event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "make test"); put("live_log", true); put("snippet", "out")
            }),
            now = ms(1) + 25 * hour,
        )
        val stored = rawPayload(store, 1)
        assertFalse("snippet" in stored)
        assertEquals(true, stored.boolOrNull("expired"))
        assertFalse("an absent key must stay absent, not become an explicit null", "blob_ref" in stored)
    }

    @Test
    fun applyJournalTombstonesAPastRetentionDiffAtInsert() = runBlocking {
        val store = makeStore()
        store.applyJournal(
            event(1, type = JournalEventType.DIFF, payload = buildJsonObject {
                put("file_path", "/w/A.swift"); put("diff", "+ a"); put("added", 1)
            }),
            now = ms(1) + 31 * day,
        )
        val stored = rawPayload(store, 1)
        assertFalse("diff" in stored)
        assertEquals(true, stored.boolOrNull("expired"))
        assertEquals("/w/A.swift", stored.stringOrNull("file_path"))
    }

    @Test
    fun insertHistoryRecomputesTheColumnsAndTombstones() = runBlocking {
        val store = makeStore()
        val fresh = ms(10)
        store.applyJournal(event(5, payload = buildJsonObject { put("body", "newest") }), now = fresh)

        // Backfill lands OLDER rows: `last_seq` does not move, so the columns
        // can only stay right if insertHistory recomputes them.
        store.insertHistory(
            listOf(
                event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                    put("command", "old"); put("live_log", true); put("snippet", "out")
                }),
            ),
            now = fresh,
        )
        assertEquals("seq 5 is still the newest message", JournalEventType.TEXT, row().lastMessageType)
        assertNull(row().expiredSnippet)

        // Now a backfilled row that IS the newest message-type row, and old
        // enough to arrive tombstoned.
        store.insertHistory(
            listOf(
                event(6, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                    put("command", "backfilled"); put("live_log", true); put("snippet", "out")
                }),
            ),
            now = ms(6) + 25 * hour,
        )
        assertEquals(JournalEventType.TOOL_OUTPUT, row().lastMessageType)
        assertEquals("$ backfilled", row().expiredSnippet)
        assertFalse("snippet" in rawPayload(store, 6))
    }

    // MARK: Read path is columns only

    /// The pin that matters: delete every `event` row, then read the list.
    /// If the TTL still needed a sub-query the override would vanish.
    @Test
    fun readTimeTTLDerivesFromColumnsWithoutReadingEvents() = runBlocking {
        val store = makeStore()
        store.applyJournal(
            event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "make test"); put("live_log", true); put("snippet", "out")
            }),
            now = ms(2),
        )
        db.eventDao().deleteAll()

        assertEquals("inside the TTL the real output still shows", "out",
            store.conversations(now = ms(1) + 60_000).first().snippet)
        assertEquals("the TTL override must come from the columns, not from an event sub-query",
            "$ make test", store.conversations(now = ms(1) + 25 * hour).first().snippet)
    }

    @Test
    fun readTimeTTLIgnoresConversationsWhoseNewestMessageIsNotToolOutput() = runBlocking {
        val store = makeStore()
        store.applyJournal(event(1, payload = buildJsonObject { put("body", "hello") }), now = ms(2))
        assertEquals("hello", store.conversations(now = ms(1) + 48 * hour).first().snippet)
    }

    /// The chat-list observation used to read `event` per stale row.
    /// Rewriting an `event` payload in a way that WOULD have changed the old
    /// derived snippet must now deliver nothing; the following `conversation`
    /// write proves the stream is still alive rather than merely quiet.
    @Test
    fun conversationsFlowNoLongerTracksTheEventTable() = runBlocking {
        val store = makeStore()
        // Newest message is a tool_output with NO live_log: `expired_snippet`
        // is null, so the list shows the real snippet under the new rules —
        // while the old read path would have started substituting
        // "$ make test" the moment `live_log` appeared in the payload.
        store.applyJournal(
            event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "make test"); put("snippet", "out")
            }),
            now = ms(2),
        )
        store.conversationsFlow(now = { ms(1) + 25 * hour }).test {
            assertEquals("out", awaitItem().first().snippet)
            db.eventDao().updatePayload(
                1,
                buildJsonObject { put("command", "make test"); put("snippet", "out"); put("live_log", true) }.toString(),
            )
            db.conversationDao().upsert(row().copy(title = "renamed"))
            val next = awaitItem()
            assertEquals("the event-payload write delivered a value — the list fetch still reads `event`",
                "renamed", next.first().title)
            assertEquals("out", next.first().snippet)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun readTimeTTLUsesExpiredSnippetForAServerTombstonedNewestRow() = runBlocking {
        val store = makeStore()
        store.applyJournal(
            event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "make build"); put("expired", true)
            }),
            now = ms(2),
        )
        assertNotNull(row().expiredSnippet)
        assertTrue(store.conversations(now = ms(1) + 25 * hour).first().snippet == "$ make build")
    }
}
