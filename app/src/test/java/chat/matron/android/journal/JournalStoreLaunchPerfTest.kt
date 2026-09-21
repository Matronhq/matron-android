package chat.matron.android.journal

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import chat.matron.android.journal.db.MatronDatabase
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    // MARK: Watermarked sweeps

    private suspend fun watermark(key: String): Long? = db.metaDao().value(key)?.toLongOrNull()

    private fun liveLog(seq: Long, command: String = "make test", ts: Long = seq * 1000) =
        event(seq, type = JournalEventType.TOOL_OUTPUT, ts = ts, payload = buildJsonObject {
            put("command", command); put("live_log", true); put("snippet", "out")
        })

    @Test
    fun purgeRecordsItsWatermarkAndTheSecondSweepSkipsThatRange() = runBlocking {
        val store = makeStore()
        store.applyJournal(
            event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "make test"); put("live_log", true); put("snippet", "out"); put("blob_ref", "b1")
            }),
            now = ms(1),
        )
        val sweepAt = ms(1) + 25 * hour
        store.purgeExpiredToolOutputSnippets(now = sweepAt)
        assertFalse("snippet" in rawPayload(store, 1))
        assertEquals(sweepAt - 24 * hour, watermark(JournalStore.SNIPPET_TTL_WATERMARK_KEY))

        // Put an un-tombstoned payload back under the watermark by hand. A
        // second sweep must not see it — that is what "incremental" means,
        // and the insert paths are what guarantee no real row can be there.
        db.eventDao().updatePayload(
            1, buildJsonObject { put("command", "make test"); put("live_log", true); put("snippet", "back") }.toString(),
        )
        store.purgeExpiredToolOutputSnippets(now = sweepAt + 60_000)
        assertEquals("the second sweep rescanned a range its watermark had already covered",
            "back", rawPayload(store, 1).stringOrNull("snippet"))
    }

    /// The other half of the watermark contract: a row older than the
    /// watermark that lands AFTER it arrives tombstoned, so skipping the
    /// range is safe.
    @Test
    fun anOldRowInsertedAfterTheWatermarkArrivesTombstoned() = runBlocking {
        val store = makeStore()
        val sweepAt = ms(100) + 25 * hour
        store.purgeExpiredToolOutputSnippets(now = sweepAt)
        store.insertHistory(listOf(liveLog(1, command = "ancient")), now = sweepAt + 60_000)
        val stored = rawPayload(store, 1)
        assertFalse("a below-watermark row must arrive already tombstoned", "snippet" in stored)
        assertEquals(true, stored.boolOrNull("expired"))
    }

    @Test
    fun sweepCoversMoreRowsThanOneChunk() = runBlocking {
        val store = makeStore()
        // 1200 rows = three chunks of 500 (the last partial), so a
        // single-chunk implementation leaves 700 rows un-tombstoned.
        store.insertHistory((1L..1200L).map { liveLog(it, command = "c$it") }, now = ms(1))
        store.purgeExpiredToolOutputSnippets(now = ms(1200) + 25 * hour)
        val stillCarryingABody = store.events("c1").filter { "snippet" in it.payload }.map { it.seq }
        assertEquals("the sweep stopped after the first chunk", emptyList<Long>(), stillCarryingABody)
    }

    @Test
    fun applyRetentionReturnsTheSeqsItTombstoned() = runBlocking {
        val store = makeStore()
        store.insertHistory(
            listOf(
                event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                    put("command", "old"); put("snippet", "out"); put("exit_code", 0)
                }),
                event(2, type = JournalEventType.DIFF, payload = buildJsonObject {
                    put("file_path", "/w/A.swift"); put("diff", "+ a")
                }),
                event(3, payload = buildJsonObject { put("body", "kept forever") }),
            ),
            now = ms(3),
        )
        val seqs = store.applyRetention(now = ms(3) + 31 * day)
        assertEquals("text rows are never retention-tombstoned", listOf(1L, 2L), seqs.sorted())
        assertFalse("snippet" in rawPayload(store, 1))
        assertFalse("diff" in rawPayload(store, 2))
        assertEquals("/w/A.swift", rawPayload(store, 2).stringOrNull("file_path"))
        assertEquals("kept forever", rawPayload(store, 3).stringOrNull("body"))

        assertEquals("a second retention sweep over the same range must tombstone nothing",
            emptyList<Long>(), store.applyRetention(now = ms(3) + 31 * day + 60_000))
    }

    /// `JournalMaintenance.stop()` must be able to await an in-flight sweep
    /// rather than only ever waiting one out — so a cancelled sweep stops
    /// without advancing the watermark, and the next pass resumes over the
    /// same, still-unswept range.
    @Test
    fun applyRetentionWritesNothingWhenCancelledAndTheNextPassCoversTheRange() = runBlocking {
        val store = makeStore()
        store.insertHistory((1L..1200L).map { liveLog(it, command = "c$it") }, now = ms(1))
        val sweepAt = ms(1) + 31 * day

        val cancelled = Job().also { it.cancel() }
        val outcome = runCatching { withContext(cancelled) { store.applyRetention(now = sweepAt) } }
        assertTrue("a cancelled sweep either returns nothing or throws CancellationException",
            outcome.getOrNull().isNullOrEmpty() || outcome.exceptionOrNull() is CancellationException)
        assertEquals("a cancelled sweep must leave every row untouched", "out", rawPayload(store, 1).stringOrNull("snippet"))
        assertNull("a cancelled sweep must not advance the watermark", watermark(JournalStore.RETENTION_WATERMARK_KEY))

        val resumed = store.applyRetention(now = sweepAt)
        assertEquals(1200, resumed.size)
        assertFalse("snippet" in rawPayload(store, 1))
        assertNotNull(watermark(JournalStore.RETENTION_WATERMARK_KEY))
    }

    /// A row the 24h TTL sweep already tombstoned is a no-op for the 30-day
    /// rule and never appears in a rewrite-only list — but the watermark
    /// guarantees this seq is visited exactly once, ever, so it must still
    /// be reported.
    @Test
    fun applyRetentionReturnsEveryVisitedSeqNotJustRewrittenOnes() = runBlocking {
        val store = makeStore()
        val insertAt = ms(10)
        store.insertHistory(
            listOf(
                event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                    put("command", "already gone"); put("expired", true); put("exit_code", 1)
                }),
                liveLog(2, command = "still here"),
                event(3, type = JournalEventType.DIFF, payload = buildJsonObject {
                    put("file_path", "/w/A.swift"); put("diff", "+ a")
                }),
            ),
            now = insertAt,
        )
        val before = db.eventDao().byId(1)!!.payload
        val seqs = store.applyRetention(now = insertAt + 40 * day)
        assertEquals("the already-tombstoned row must still be reported: its search row is stale",
            listOf(1L, 2L, 3L), seqs.sorted())
        assertEquals("a no-op row must be reported, not rewritten", before, db.eventDao().byId(1)!!.payload)
        assertFalse("snippet" in rawPayload(store, 2))
        assertFalse("diff" in rawPayload(store, 3))
    }

    /// A tool_output that was never a live log has no `expired_snippet` at
    /// insert time; once retention tombstones it, the list has nothing but
    /// the command to show, so the sweep refreshes the columns of the
    /// conversations it touched.
    @Test
    fun retentionRefreshesTheConversationColumns() = runBlocking {
        val store = makeStore()
        store.applyJournal(
            event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "legacy"); put("snippet", "durable")
            }),
            now = ms(2),
        )
        assertNull(row().expiredSnippet)
        store.applyRetention(now = ms(1) + 31 * day)
        assertEquals("$ legacy", row().expiredSnippet)
        assertEquals("$ legacy", store.conversations(now = ms(1) + 31 * day).first().snippet)
    }

    @Test
    fun wipeResetsAllThreeWatermarksAndTheMaintenanceStamp() = runBlocking {
        val store = makeStore()
        val sweepAt = ms(100) + 31 * day
        store.purgeExpiredToolOutputSnippets(now = sweepAt)
        store.applyRetention(now = sweepAt)
        store.recordSearchRetirement(upTo = sweepAt)
        store.recordMaintenanceRun(at = sweepAt)
        assertNotNull(watermark(JournalStore.SNIPPET_TTL_WATERMARK_KEY))
        assertNotNull(watermark(JournalStore.RETENTION_WATERMARK_KEY))
        assertNotNull(watermark(JournalStore.SEARCH_RETENTION_WATERMARK_KEY))
        assertNotNull(store.maintenanceLastRun())

        store.wipe()
        assertNull(watermark(JournalStore.SNIPPET_TTL_WATERMARK_KEY))
        assertNull(watermark(JournalStore.RETENTION_WATERMARK_KEY))
        assertNull(watermark(JournalStore.SEARCH_RETENTION_WATERMARK_KEY))
        assertNull(store.maintenanceLastRun())
    }

    // MARK: pendingSearchRetirements / recordSearchRetirement

    @Test
    fun pendingSearchRetirementsFindsToolOutputAndDiffSeqsPastTheWindow() = runBlocking {
        val store = makeStore()
        store.insertHistory(
            listOf(
                event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                    put("command", "old"); put("snippet", "out"); put("exit_code", 0)
                }),
                event(2, type = JournalEventType.DIFF, payload = buildJsonObject {
                    put("file_path", "/w/A.swift"); put("diff", "+ a")
                }),
                event(3, payload = buildJsonObject { put("body", "kept forever") }),
            ),
            now = ms(3),
        )
        val now = ms(3) + 31 * day
        val pending = store.pendingSearchRetirements(now = now)
        assertEquals("text rows are never retention-tombstoned", listOf(1L, 2L), pending.seqs.sorted())
        assertEquals("an uninterrupted scan's cutoff is the full retention cutoff",
            now - EventTombstone.RETENTION_WINDOW_MS, pending.cutoffMs)
    }

    /// `applyRetention` advancing `retention_ts` must NOT be mistaken for
    /// search coverage — the two watermarks are independent.
    @Test
    fun pendingSearchRetirementsIsIndependentOfTheRetentionWatermark() = runBlocking {
        val store = makeStore()
        store.insertHistory(
            listOf(event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "old"); put("snippet", "out"); put("exit_code", 0)
            })),
            now = ms(3),
        )
        val now = ms(3) + 31 * day
        store.applyRetention(now = now)
        assertNotNull("precondition: retention already ran", watermark(JournalStore.RETENTION_WATERMARK_KEY))
        assertEquals("the retention watermark advancing must not hide this seq from search retirement",
            listOf(1L), store.pendingSearchRetirements(now = now).seqs)
    }

    @Test
    fun recordSearchRetirementAdvancesItsWatermarkSoASecondCallSeesNothingPending() = runBlocking {
        val store = makeStore()
        store.insertHistory(
            listOf(event(1, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject {
                put("command", "old"); put("snippet", "out"); put("exit_code", 0)
            })),
            now = ms(3),
        )
        val now = ms(3) + 31 * day
        val first = store.pendingSearchRetirements(now = now)
        assertEquals(listOf(1L), first.seqs)
        store.recordSearchRetirement(upTo = first.cutoffMs)
        assertEquals("a second call after recording must see nothing pending",
            emptyList<Long>(), store.pendingSearchRetirements(now = now + 60_000).seqs)
    }

    /// 1200 rows sharing ONE `ts` (a batch apply stamps many rows at exactly
    /// the same millisecond, routinely) must ALL be visited in a single
    /// call, proving the keyset `(ts, seq)` paging walks ties by `seq`
    /// rather than a chunk boundary silently dropping same-`ts` siblings.
    @Test
    fun pendingSearchRetirementsVisitsAllSameTimestampSiblingsAcrossChunkBoundaries() = runBlocking {
        val store = makeStore()
        val sharedTS = ms(1)
        store.insertHistory((1L..1200L).map { liveLog(it, command = "c$it", ts = sharedTS) }, now = sharedTS)
        val now = sharedTS + 31 * day
        val pending = store.pendingSearchRetirements(now = now)
        assertEquals("every row sharing the tie timestamp must be visited, not just the first chunk",
            (1L..1200L).toList(), pending.seqs.sorted())
        store.recordSearchRetirement(upTo = pending.cutoffMs)
        assertEquals("the watermark must now cover every tied sibling",
            emptyList<Long>(), store.pendingSearchRetirements(now = now).seqs)
    }

    @Test
    fun maintenanceLastRunRoundTrips() = runBlocking {
        val store = makeStore()
        assertNull(store.maintenanceLastRun())
        store.recordMaintenanceRun(at = 1_700_000_000_000)
        assertEquals(1_700_000_000_000L, store.maintenanceLastRun())
    }

    @Test
    fun rowCountsCountBothTables() = runBlocking {
        val store = makeStore()
        for (seq in 1L..3L) store.applyJournal(event(seq, convo = if (seq == 3L) "c2" else "c1"), now = ms(10))
        assertEquals(JournalStore.RowCounts(events = 3, conversations = 2), store.rowCounts())
    }
}
