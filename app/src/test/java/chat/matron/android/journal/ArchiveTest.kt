package chat.matron.android.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// The synced archive (journal protocol "Archived conversations"): the
/// store's `archived_at` rules, [ArchiveSync]'s first sync, ordering and
/// rollback, the engine's hello and live frames, and the purge of
/// long-archived history.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ArchiveTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun ev(seq: Long, convo: String, tsMs: Long = seq * 1000, type: String = "text") =
        JournalEvent(seq, convo, Instant.ofEpochMilli(tsMs), "agent:box-a", type, buildJsonObject { put("body", "hi") })

    private suspend fun store(vararg ids: String): JournalStore {
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:alice")
        store.applyColdSnapshot(ids.map { ConvoSummaryDTO(it, it, "done", 0, "", 0) }, headSeq = 0)
        return store
    }

    private suspend fun JournalStore.visible() = conversations().map { it.id }.sorted()
    private suspend fun JournalStore.archived() = archivedConversationsFlow().first().map { it.id }.sorted()

    /// A journal that records each `POST /archive`, and can hold or refuse one.
    private class FakeWriter : ArchiveWriting {
        val calls = mutableListOf<Pair<List<String>, Boolean>>()
        var gate: CompletableDeferred<Unit>? = null
        var refuse: ((List<String>, Boolean) -> Boolean) = { _, _ -> false }
        override suspend fun setArchived(convoIDs: List<String>, archived: Boolean) {
            synchronized(calls) { calls += convoIDs to archived }
            gate?.let { it.await(); gate = null }
            if (refuse(convoIDs, archived)) throw JournalApiError.Transport("refused")
        }
    }

    // MARK: Store

    @Test
    fun archiveLeavesTheListAndUnarchiveBringsItBack() = runBlocking {
        val store = store("a", "b")
        store.setArchived(listOf("a"), archived = true, now = 100)
        assertEquals(listOf("b"), store.visible())
        assertEquals(listOf("a"), store.archived())
        store.setArchived(listOf("a"), archived = true, now = 200)
        assertEquals("archiving twice keeps the first time", 100L, store.conversation("a")?.archivedAt)
        store.setArchived(listOf("a"), archived = false, now = 300)
        assertEquals(listOf("a", "b"), store.visible())
        assertEquals(emptyList<String>(), store.archived())
    }

    @Test
    fun aNewerMessageUnarchivesButAReplayedOlderOneOrASubChatDoesNot() = runBlocking {
        val store = store("a")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("kid", "", "done", 0, "", 0, parentConvoID = "a")), headSeq = 0)
        store.setArchived(listOf("a", "kid"), archived = true, now = 5_000)
        store.applyJournal(ev(1, "a", tsMs = 4_000))
        assertEquals("older than the archive", listOf("a"), store.archived())
        store.applyJournal(ev(2, "a", tsMs = 6_000, type = "session_status"))
        assertEquals("not a message", listOf("a"), store.archived())
        store.applyJournal(ev(3, "kid", tsMs = 7_000))
        assertNotNull("sub-chats are left as they are", store.conversation("kid")?.archivedAt)
        store.applyJournal(ev(4, "a", tsMs = 8_000))
        assertEquals(emptyList<String>(), store.archived())
    }

    @Test
    fun aSnapshotSetsANewRowsArchiveButNeverAnExistingOnes() = runBlocking {
        val store = store("a")
        store.refreshSummaries(
            listOf(
                ConvoSummaryDTO("a", "a", "done", 0, "", 0, archivedAt = 50),
                ConvoSummaryDTO("n", "n", "done", 0, "", 0, archivedAt = 60),
            ),
        )
        assertNull(store.conversation("a")?.archivedAt)
        assertEquals(60L, store.conversation("n")?.archivedAt)
    }

    // MARK: ArchiveSync

    @Test
    fun firstSyncAdoptsTheJournalsSetCarriesLocalOnesOverThenStoresTheCursor() = runBlocking {
        val store = store("both", "local", "remote", "back")
        store.setArchived(listOf("both", "local", "back"), archived = true, now = 1)
        val writer = FakeWriter()
        val sync = ArchiveSync(store, writer, dispatcher = Dispatchers.IO)
        assertEquals(0L, sync.helloArchiveSince())
        sync.applyHello(
            ArchiveDelta(
                listOf(
                    ArchiveChange("both", 10),
                    ArchiveChange("remote", 11, auto = true),
                    ArchiveChange("back", 12),
                    ArchiveChange("back", null),
                ),
                at = 12,
            ),
        )
        sync.waitUntilIdle()
        assertEquals(listOf("both", "local", "remote"), store.archived())
        assertEquals(listOf(listOf("local") to true), writer.calls)
        assertEquals(12L, store.archiveSince())
        assertEquals(12L, sync.helloArchiveSince())
    }

    @Test
    fun aFailedCarryOverLeavesTheCursorUnsetSoTheNextConnectTriesAgain() = runBlocking {
        val store = store("local")
        store.setArchived(listOf("local"), archived = true, now = 1)
        val writer = FakeWriter().apply { refuse = { _, _ -> true } }
        val sync = ArchiveSync(store, writer, dispatcher = Dispatchers.IO)
        sync.applyHello(ArchiveDelta(emptyList(), at = 5))
        sync.waitUntilIdle()
        assertNull(store.archiveSince())
        assertEquals(listOf("local"), store.archived())
        writer.refuse = { _, _ -> false }
        sync.applyHello(ArchiveDelta(emptyList(), at = 6))
        sync.waitUntilIdle()
        assertEquals(6L, store.archiveSince())
    }

    @Test
    fun laterSyncsAndFramesApplyChangesAndMoveTheCursor() = runBlocking {
        val store = store("a", "b")
        store.setArchiveSince(10)
        val writer = FakeWriter()
        val sync = ArchiveSync(store, writer, dispatcher = Dispatchers.IO)
        assertEquals(10L, sync.helloArchiveSince())
        sync.applyHello(ArchiveDelta(listOf(ArchiveChange("a", 15)), at = 15))
        assertEquals(listOf("a"), store.archived())
        sync.apply(ArchiveDelta(listOf(ArchiveChange("a", null), ArchiveChange("b", 20, auto = true)), at = 20))
        assertEquals(listOf("b"), store.archived())
        assertEquals(20L, store.archiveSince())
        sync.apply(ArchiveDelta(emptyList(), at = 3))
        assertEquals("the cursor never moves back", 20L, store.archiveSince())
        assertTrue(writer.calls.isEmpty())
    }

    @Test
    fun onAJournalWithoutArchivingTheArchiveStaysOnThisDevice() = runBlocking {
        val store = store("a")
        val writer = FakeWriter()
        val sync = ArchiveSync(store, writer, dispatcher = Dispatchers.IO)
        sync.applyHello(null)
        sync.setArchived(listOf("a"), archived = true)
        sync.waitUntilIdle()
        assertEquals(listOf("a"), store.archived())
        assertTrue(writer.calls.isEmpty())
        assertNull(store.archiveSince())
    }

    @Test
    fun tapsGoOutOneAtATimeAndTheJournalEndsOnTheLatestChoice() = runBlocking {
        val store = store("x")
        store.setArchiveSince(1)
        val writer = FakeWriter().apply { gate = CompletableDeferred() }
        val sync = ArchiveSync(store, writer, dispatcher = Dispatchers.IO)
        sync.setArchived(listOf("x"), archived = true)
        withTimeout(2000) { while (synchronized(writer.calls) { writer.calls.isEmpty() }) kotlinx.coroutines.delay(5) }
        sync.setArchived(listOf("x"), archived = false)
        sync.setArchived(listOf("x"), archived = true)
        sync.setArchived(listOf("x"), archived = false)
        // A frame for a conversation with a write still going out keeps the
        // user's choice.
        sync.apply(ArchiveDelta(listOf(ArchiveChange("x", 9)), at = 9))
        assertEquals(emptyList<String>(), store.archived())
        writer.gate!!.complete(Unit)
        sync.waitUntilIdle()
        assertEquals(listOf(listOf("x") to true, listOf("x") to false), writer.calls)
        assertEquals(emptyList<String>(), store.archived())
    }

    @Test
    fun aRefusedWriteIsPutBackOnlyWhileItIsStillTheLatestChoice() = runBlocking {
        val store = store("x", "y")
        store.setArchiveSince(1)
        val writer = FakeWriter().apply { refuse = { _, _ -> true } }
        val sync = ArchiveSync(store, writer, dispatcher = Dispatchers.IO)
        sync.setArchived(listOf("x"), archived = true)
        sync.waitUntilIdle()
        assertEquals("refused: back in the list", emptyList<String>(), store.archived())

        writer.gate = CompletableDeferred()
        sync.setArchived(listOf("y"), archived = true)
        withTimeout(2000) { while (synchronized(writer.calls) { writer.calls.size < 2 }) kotlinx.coroutines.delay(5) }
        writer.refuse = { _, archived -> archived }
        sync.setArchived(listOf("y"), archived = false)
        writer.gate!!.complete(Unit)
        sync.waitUntilIdle()
        assertEquals(listOf("x", "y"), store.visible())
        assertEquals(listOf(listOf("y") to false), writer.calls.takeLast(1))
    }

    // MARK: Engine

    @Test
    fun theEngineAsksForTheArchiveAndAppliesHelloAndLiveFrames() = runBlocking {
        val store = store("c1", "c2")
        store.setArchiveSince(7)
        val socket = FakeWebSocketConnection()
        socket.serve(
            """{"kind":"control","op":"hello_ok","seq":0,"archive":{"changes":[{"convo_id":"c1","archived_at":8,"auto":true}],"at":8}}""",
        )
        val engine = JournalSyncEngine(
            api = FakeSnapshotSource(), store = store, connector = FakeConnector(listOf(socket)), token = "t",
            ownSender = "user:alice", search = null, backoffBaseSeconds = 0.01,
            archive = ArchiveSync(store, FakeWriter(), dispatcher = Dispatchers.IO),
        )
        engine.beginSync()
        withTimeout(2000) { while (store.archived() != listOf("c1")) kotlinx.coroutines.delay(10) }
        val hello = parseJsonObjectOrNull(socket.sent.first())!!
        assertEquals(7L, hello.longOrNull("archive_since"))
        socket.serve("""{"kind":"archive","changes":[{"convo_id":"c1","archived_at":null},{"convo_id":"c2","archived_at":9}],"at":9}""")
        withTimeout(2000) { while (store.archived() != listOf("c2")) kotlinx.coroutines.delay(10) }
        assertEquals(9L, store.archiveSince())
        engine.endSync()
    }

    // MARK: Purge

    @Test
    fun longArchivedHistoryIsDroppedWithItsSubChatsAndComesBackOnUnarchive() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        val now = 200 * day
        val store = store("old", "new", "live")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("kid", "", "done", 0, "", 0, parentConvoID = "old")), headSeq = 0)
        store.applyJournalBatch(listOf(ev(1, "old"), ev(2, "kid"), ev(3, "new"), ev(4, "live")))
        store.setArchived(listOf("old", "live"), archived = true, now = now - 100 * day)
        store.setArchived(listOf("new"), archived = true, now = now - 10 * day)

        assertEquals(listOf("live", "old"), store.archivedHistoryPurgeCandidates(now).sorted())
        val search = RecordingRooms()
        val maintenance = JournalMaintenance(
            store = store, search = search, now = { now }, firstRunDelayMs = 0,
            dispatcher = Dispatchers.IO, openConversations = { setOf("live") },
        )
        maintenance.runAfterCatchUp()
        assertEquals(0, store.eventsBefore("old", Long.MAX_VALUE, 10).size)
        assertEquals(0, store.eventsBefore("kid", Long.MAX_VALUE, 10).size)
        assertEquals("archived for 10 days: kept", 1, store.eventsBefore("new", Long.MAX_VALUE, 10).size)
        assertEquals("open on screen: kept", 1, store.eventsBefore("live", Long.MAX_VALUE, 10).size)
        assertEquals(listOf("old", "kid"), search.removed)
        assertTrue(store.pendingSearchRoomPurges().isEmpty())
        assertNotNull(store.conversation("old")?.historyPurgedAt)
        assertNotNull("sub-chats are marked too", store.conversation("kid")?.historyPurgedAt)
        assertTrue("old" !in store.allConversationIDs())
        assertTrue("kid" !in store.allConversationIDs())
        assertEquals("the row stays in Archived", listOf("live", "new", "old"), store.archived())

        store.setArchived(listOf("old"), archived = false, now = now)
        assertNull(store.conversation("old")?.historyPurgedAt)
        assertNull(store.conversation("kid")?.historyPurgedAt)
        assertTrue("old" in store.allConversationIDs())
        assertTrue("kid" in store.allConversationIDs())
    }

    @Test
    fun aSubChatOpenOnScreenKeepsItsParentsHistory() = runBlocking {
        val day = 24L * 60 * 60 * 1000
        val now = 200 * day
        val store = store("old")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("kid", "", "done", 0, "", 0, parentConvoID = "old")), headSeq = 0)
        store.applyJournalBatch(listOf(ev(1, "old"), ev(2, "kid")))
        store.setArchived(listOf("old"), archived = true, now = now - 100 * day)
        assertEquals(emptyList<String>(), store.purgeArchivedHistory(listOf("old"), now, keeping = setOf("kid")))
        assertEquals(1, store.eventsBefore("kid", Long.MAX_VALUE, 10).size)
        assertNull(store.conversation("old")?.historyPurgedAt)
    }

    @Test
    fun aRefusedUnarchivePutsBackTheArchiveTimeAndPurgeMarks() = runBlocking {
        val store = store("old")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("kid", "", "done", 0, "", 0, parentConvoID = "old")), headSeq = 0)
        store.setArchived(listOf("old"), archived = true, now = 1_000)
        store.purgeArchivedHistory(listOf("old"), now = 2_000)
        store.setArchiveSince(1)
        val writer = FakeWriter().apply { refuse = { _, _ -> true } }
        val sync = ArchiveSync(store, writer, now = { 9_000 }, dispatcher = Dispatchers.IO)
        sync.setArchived(listOf("old"), archived = false)
        sync.waitUntilIdle()
        assertEquals(1_000L, store.conversation("old")?.archivedAt)
        assertEquals(2_000L, store.conversation("old")?.historyPurgedAt)
        assertEquals(2_000L, store.conversation("kid")?.historyPurgedAt)
    }

    private class RecordingRooms : chat.matron.android.search.SearchService {
        val removed = mutableListOf<String>()
        override suspend fun removeRooms(roomIDs: List<String>) { removed += roomIDs }
        override suspend fun index(roomID: String, eventID: String, sender: String, timestamp: Instant, body: String) {}
        override suspend fun remove(eventID: String) {}
        override suspend fun query(text: String, limit: Int) = emptyList<chat.matron.android.search.SearchHit>()
        override suspend fun wipe() {}
        override suspend fun recordBackfillProgress(roomID: String, indexedCount: Int, oldestEventID: String?, complete: Boolean) {}
        override suspend fun backfillComplete(roomID: String): Boolean = true
        override suspend fun backfillOldestEventID(roomID: String): String? = null
        override suspend fun eventCount(roomID: String): Int = 0
        override suspend fun contains(eventID: String): Boolean = false
    }

    // MARK: Wire

    @Test
    fun wireShapes() {
        val hello = ServerFrame.decode("""{"kind":"control","op":"hello_ok","seq":3,"archive":{"changes":[{"convo_id":"a","archived_at":5},{"convo_id":"","archived_at":1},{"convo_id":"b","archived_at":"x"}],"at":5}}""")
        assertEquals(ArchiveDelta(listOf(ArchiveChange("a", 5)), 5), (hello as ServerFrame.HelloOK).archive)
        val older = ServerFrame.decode("""{"kind":"control","op":"hello_ok","seq":3}""") as ServerFrame.HelloOK
        assertNull(older.archive)
        val frame = ServerFrame.decode("""{"kind":"archive","changes":[{"convo_id":"a","archived_at":null,"auto":false}],"at":9}""")
        assertEquals(ServerFrame.ArchiveFrame(ArchiveDelta(listOf(ArchiveChange("a", null)), 9)), frame)
        val sent = parseJsonObjectOrNull(ClientOp.Hello("t", 4, archiveSince = 0).encoded())!!
        assertEquals(0L, sent.longOrNull("archive_since"))
        assertTrue("archive_since" !in parseJsonObjectOrNull(ClientOp.Hello("t", 4).encoded())!!)
        assertEquals(
            UserSettings(notices = true, autoArchiveDays = 30),
            UserSettingsDecoding.settings(parseJsonObjectOrNull("""{"notices":true,"auto_archive_days":30}""")),
        )
        assertEquals(UserSettings(notices = false), UserSettingsDecoding.settings(parseJsonObjectOrNull("""{"notices":false}""")))
    }
}
