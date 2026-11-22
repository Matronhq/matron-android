package chat.matron.android.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.viewmodels.InMemoryKeyValueStore
import chat.matron.android.viewmodels.SyncScopeSettingModel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// The "For you only" sync scope: wire format, the store's bookkeeping, and
/// the engine against a scripted socket and a scripted page source.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SyncScopeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun event(seq: Long, convo: String = "c1", type: String = "text") = JournalEvent.fromFrame(
        parseJsonObjectOrNull(journalLine(seq, convo, type))!!,
    )!!

    private fun journalLine(seq: Long, convo: String = "c1", type: String = "text") =
        """{"kind":"journal","seq":$seq,"convo_id":"$convo","ts":${seq * 1000},"sender":"agent:a","type":"$type","payload":{"body":"m$seq"}}"""

    private fun helloOK(head: Long, sync: String? = "for_you", coordinator: String? = null): String {
        val extra = buildString {
            if (sync != null) append(""","sync":"$sync"""")
            if (coordinator != null) append(""","coordinator_convo_id":"$coordinator"""")
        }
        return """{"kind":"control","op":"hello_ok","seq":$head$extra}"""
    }

    private fun cursorFrame(seq: Long) = """{"kind":"control","op":"cursor","seq":$seq}"""

    private fun newStore() = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:alice")

    private suspend fun seededStore(): JournalStore = newStore().also {
        it.applyColdSnapshot(listOf(ConvoSummaryDTO("c1", "", "running", 0, "", 0)), headSeq = 0)
    }

    /// Serves a conversation's journal newest-first in pages, like
    /// `GET /convo/:id/messages`, and records every call.
    private class FakePages(var events: List<JournalEvent> = emptyList()) : ConversationPageSource {
        val calls = CopyOnWriteArrayList<Triple<String, Long?, Int>>()
        @Volatile var failuresLeft = 0
        @Volatile var gate: CompletableDeferred<Unit>? = null

        override suspend fun messages(convoID: String, beforeSeq: Long?, limit: Int): List<JournalEvent> {
            calls += Triple(convoID, beforeSeq, limit)
            gate?.await()
            if (failuresLeft > 0) {
                failuresLeft -= 1
                error("offline")
            }
            return events.filter { it.convoID == convoID && (beforeSeq == null || it.seq < beforeSeq) }
                .sortedBy { it.seq }.takeLast(limit)
        }
    }

    private fun makeEngine(
        store: JournalStore,
        sockets: List<FakeWebSocketConnection>,
        pages: ConversationPageSource? = FakePages(),
        scope: SyncScope = SyncScope.FOR_YOU,
        snapshot: SnapshotSource = FakeSnapshotSource(),
        stats: SyncStatsRecorder? = null,
        inFront: () -> Boolean = { true },
    ) = JournalSyncEngine(
        api = snapshot, store = store, connector = FakeConnector(sockets), token = "t",
        ownSender = "user:alice", backoffBaseSeconds = 0.01, pages = pages, syncScope = scope,
        conversationSettleDelay = 20.milliseconds,
        stats = stats,
        isAppInFront = inFront,
    )

    private suspend fun waitUntil(timeoutMs: Long = 3000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
    }

    private fun sentOps(socket: FakeWebSocketConnection, op: String) =
        socket.sent.mapNotNull { parseJsonObjectOrNull(it) }.filter { it.stringOrNull("op") == op }

    // MARK: Wire

    @Test
    fun helloNamesTheScopeAndHelloOKSaysWhichItGot() {
        val hello = parseJsonObjectOrNull(ClientOp.Hello("t", 5, sync = SyncScope.FOR_YOU).encoded())!!
        assertEquals("for_you", hello.stringOrNull("sync"))
        val plain = parseJsonObjectOrNull(ClientOp.Hello("t", 5).encoded())!!
        assertNull(plain.stringOrNull("sync"))
        val ok = ServerFrame.decode(helloOK(9)) as ServerFrame.HelloOK
        assertEquals(SyncScope.FOR_YOU, ok.sync)
        val old = ServerFrame.decode(helloOK(9, sync = null)) as ServerFrame.HelloOK
        assertNull(old.sync)
    }

    @Test
    fun cursorControlFrameDecodes() {
        assertEquals(ServerFrame.Cursor(42), ServerFrame.decode(cursorFrame(42)))
        assertEquals(
            ServerFrame.UnknownControl("cursor"),
            ServerFrame.decode("""{"kind":"control","op":"cursor"}"""),
        )
    }

    @Test
    fun scopeIsStoredPerDeviceAndDefaultsToEverything() {
        val prefs = InMemoryKeyValueStore()
        assertEquals(SyncScope.EVERYTHING, SyncScope.stored(prefs))
        SyncScope.store(SyncScope.FOR_YOU, prefs)
        assertEquals(SyncScope.FOR_YOU, SyncScope.stored(prefs))
        prefs.setString(SyncScope.KEY, "something-else")
        assertEquals(SyncScope.EVERYTHING, SyncScope.stored(prefs))
    }

    @Test
    fun settingModelSavesAndAppliesTheChoice() = runBlocking {
        val prefs = InMemoryKeyValueStore()
        val applied = CompletableDeferred<SyncScope>()
        val model = SyncScopeSettingModel(prefs, { applied.complete(it) }, CoroutineScope(Dispatchers.Default))
        assertEquals(SyncScope.EVERYTHING, model.selected.value)
        model.select(SyncScope.FOR_YOU)
        assertEquals(SyncScope.FOR_YOU, model.selected.value)
        assertEquals("for_you", prefs.getString(SyncScope.KEY))
        assertEquals(SyncScope.FOR_YOU, withTimeout(2000) { applied.await() })
    }

    // MARK: Store

    @Test
    fun storeBookkeepingNeverMovesBack() = runBlocking {
        val store = seededStore()
        store.applyJournal(event(7))
        assertNull(store.forYouSince())
        assertEquals(0L, store.syncedSeq("c1"))
        store.markForYouStart()
        assertEquals(7L, store.forYouSince())
        store.applyJournal(event(9))
        store.markForYouStart()
        assertEquals("the first switch is kept", 7L, store.forYouSince())
        assertEquals(7L, store.syncedSeq("c1"))
        store.setSyncedSeq("c1", 12)
        store.setSyncedSeq("c1", 10)
        assertEquals(12L, store.syncedSeq("c1"))
        store.advanceCursor(20)
        store.advanceCursor(15)
        assertEquals(20L, store.cursor())
        store.wipe()
        assertNull(store.forYouSince())
        assertEquals(0L, store.syncedSeq("c1"))
    }

    @Test
    fun tailThatReachesTheStoredCopyKeepsIt() = runBlocking {
        val store = seededStore()
        store.insertHistory(listOf(event(1), event(2)))
        val dropped = store.insertConversationTail(listOf(event(3), event(4)), "c1", reachesSyncedSeq = true)
        assertTrue(dropped.isEmpty())
        assertEquals(listOf(1L, 2L, 3L, 4L), store.events("c1").map { it.seq })
        assertEquals(4L, store.syncedSeq("c1"))
        val row = store.conversations().single { it.id == "c1" }
        assertEquals("the chat list row learns the newest message", 4L, row.lastSeq)
        assertEquals("m4", row.snippet)
    }

    @Test
    fun aFetchedTailBringsTheChatsStateAndReadMarker() = runBlocking {
        val store = seededStore()
        fun raw(seq: Long, type: String, payload: String) = JournalEvent.fromFrame(parseJsonObjectOrNull(
            """{"kind":"journal","seq":$seq,"convo_id":"c1","ts":${seq * 1000},"sender":"agent:a","type":"$type","payload":$payload}""",
        )!!)!!
        store.insertConversationTail(
            listOf(
                event(3), event(4),
                raw(5, "session_status", """{"state":"running"}"""),
                raw(6, "read_marker", """{"up_to_seq":3}"""),
                event(7),
            ),
            "c1", reachesSyncedSeq = true,
        )
        val row = store.conversations().single { it.id == "c1" }
        assertEquals("running", row.sessionState)
        assertEquals(3L, row.readUpToSeq)
        assertEquals("4 and 7 are unread", 2, row.unreadCount)
    }

    private fun ownText(seq: Long, body: String) = JournalEvent.fromFrame(parseJsonObjectOrNull(
        """{"kind":"journal","seq":$seq,"convo_id":"c1","ts":${seq * 1000},"sender":"user:alice","type":"text","payload":{"body":"$body"}}""",
    )!!)!!

    @Test
    fun fetchingAnEventAgainDoesNotConfirmAnotherSend() = runBlocking {
        val store = seededStore()
        store.outboxInsert("a", "c1", "same", now = 0)
        store.outboxInsert("b", "c1", "same", now = 0)
        store.outboxMarkAttempt("a"); store.outboxMarkAttempt("b")
        store.outboxMarkFailed("b", "rejected")
        store.applyJournal(ownText(5, "same")) // confirms one of them
        val left = store.outboxRows("c1").map { it.localID }
        assertEquals(1, left.size)
        // The same event fetched again on a For you only open.
        store.insertConversationTail(listOf(ownText(5, "same")), "c1", reachesSyncedSeq = true)
        assertEquals(left, store.outboxRows("c1").map { it.localID })
    }

    @Test
    fun aScrollBackPageLandsOnlyIfTheOldestRowIsStillThere() = runBlocking {
        val store = seededStore()
        store.insertHistory(listOf(event(10), event(11)))
        assertFalse(store.insertHistoryIfOldestIs("c1", 9, listOf(event(5))))
        assertEquals(listOf(10L, 11L), store.events("c1").map { it.seq })
        assertTrue(store.insertHistoryIfOldestIs("c1", 10, listOf(event(5))))
        assertEquals(listOf(5L, 10L, 11L), store.events("c1").map { it.seq })
    }

    @Test
    fun thePreviewFollowsAFetchedMessageEvenAfterABookkeepingFrame() = runBlocking {
        val store = seededStore()
        store.applyJournal(event(1))
        // A live bookkeeping frame moves last_seq without a message.
        store.applyJournal(JournalEvent.fromFrame(parseJsonObjectOrNull(
            """{"kind":"journal","seq":9,"convo_id":"c1","ts":9000,"sender":"agent:a","type":"session_status","payload":{"state":"running"}}""",
        )!!)!!)
        store.insertConversationTail(listOf(event(5)), "c1", reachesSyncedSeq = true)
        val row = store.conversations().single { it.id == "c1" }
        assertEquals("m5", row.snippet)
        assertEquals(9L, row.lastSeq)
        assertEquals("running", row.sessionState)
    }

    @Test
    fun aSnapshotDoesNotUndoAReadMadeWhileItWasOut() = runBlocking {
        val store = seededStore()
        store.refreshSummaries(listOf(ConvoSummaryDTO("c2", "", "waiting", 3, "", 0, unreadCount = 0)))
        val marks = store.readMarks()
        // c1 is read while the snapshot is out.
        store.applyJournal(JournalEvent.fromFrame(parseJsonObjectOrNull(
            """{"kind":"journal","seq":4,"convo_id":"c1","ts":4000,"sender":"user:alice","type":"read_marker","payload":{"up_to_seq":4}}""",
        )!!)!!)
        store.refreshSummaries(
            listOf(
                ConvoSummaryDTO("c1", "", "waiting", 4, "", 0, unreadCount = 6),
                ConvoSummaryDTO("c2", "", "waiting", 3, "", 0, unreadCount = 2),
            ),
            adoptUnread = true, readMarksAtRequest = marks,
        )
        val rows = store.conversations().associateBy { it.id }
        assertEquals("read since the request: its own count stands", 0, rows["c1"]!!.unreadCount)
        assertEquals(2, rows["c2"]!!.unreadCount)
        store.refreshSummaries(
            listOf(ConvoSummaryDTO("c2", "", "waiting", 3, "", 0, unreadCount = 5)),
            adoptUnread = true, keepUnread = setOf("c2"),
        )
        assertEquals("the chat on screen keeps its count", 2, store.conversations().single { it.id == "c2" }.unreadCount)
    }

    @Test
    fun tailBelowAGapDropsTheStrandedOlderEvents() = runBlocking {
        val store = seededStore()
        store.insertHistory(listOf(event(1), event(2), event(3, convo = "c2")))
        val dropped = store.insertConversationTail(listOf(event(50), event(51)), "c1", reachesSyncedSeq = false)
        assertEquals(listOf(1L, 2L), dropped.sorted())
        assertEquals(listOf(50L, 51L), store.events("c1").map { it.seq })
        assertEquals("other chats are left alone", listOf(3L), store.events("c2").map { it.seq })
    }

    @Test
    fun refreshSummariesAdoptsTheJournalsUnreadOnlyWhenAsked() = runBlocking {
        val store = seededStore()
        val row = ConvoSummaryDTO("c1", "Title", "waiting", 5, "hi", 0, unreadCount = 4)
        store.refreshSummaries(listOf(row))
        assertEquals(0, store.conversations().single { it.id == "c1" }.unreadCount)
        store.refreshSummaries(listOf(row), adoptUnread = true)
        assertEquals(4, store.conversations().single { it.id == "c1" }.unreadCount)
        store.refreshSummaries(listOf(ConvoSummaryDTO("c9", "New", "waiting", 5, "hi", 0, unreadCount = 2)), adoptUnread = true)
        assertEquals(2, store.conversations().single { it.id == "c9" }.unreadCount)
    }

    // MARK: Engine

    @Test
    fun everythingIsNamedInTheHelloByDefault() = runBlocking {
        val socket = FakeWebSocketConnection().apply { serve(helloOK(0, sync = "everything")) }
        val engine = makeEngine(seededStore(), listOf(socket), scope = SyncScope.EVERYTHING)
        engine.beginSync()
        engine.waitUntilReady()
        assertEquals("everything", sentOps(socket, "hello").single().stringOrNull("sync"))
        engine.endSync()
    }

    @Test
    fun forYouReplayEndsAtTheCursorFrame() = runBlocking {
        val store = seededStore()
        val socket = FakeWebSocketConnection().apply {
            serve(helloOK(100))
            serve(journalLine(10, type = "convo_meta"))
            serve(journalLine(40, convo = "coord"))
            serve(cursorFrame(100))
        }
        val engine = makeEngine(store, listOf(socket))
        engine.beginSync()
        engine.waitUntilReady()
        assertEquals(100L, store.cursor())
        assertEquals("for_you", sentOps(socket, "hello").single().stringOrNull("sync"))
        assertEquals("the switch point is the cursor before the replay", 0L, store.forYouSince())
        waitUntil { sentOps(socket, "ack").any { it.longOrNull("cursor") == 100L } }
        assertTrue(sentOps(socket, "ack").any { it.longOrNull("cursor") == 100L })
        engine.endSync()
    }

    @Test
    fun syncWorkIsCountedForSettings() = runBlocking {
        val stats = SyncStatsRecorder(InMemoryKeyValueStore())
        val pages = FakePages(listOf(event(5)))
        val socket = FakeWebSocketConnection().apply {
            serve(helloOK(9)); serve(journalLine(3, type = "convo_meta")); serve(cursorFrame(9))
        }
        val engine = makeEngine(seededStore(), listOf(socket), pages, stats = stats)
        engine.beginSync()
        engine.waitUntilReady()
        engine.setViewing("c1")
        waitUntil { stats.snapshot().first.conversationFetches == 1 }
        val today = stats.snapshot().first
        assertEquals(1L, today.events)
        assertEquals(1, today.catchUps)
        assertEquals(1, today.conversationFetches)
        engine.endSync()
    }

    @Test
    fun aClosedChatIsNeverFetched() = runBlocking {
        val pages = FakePages(listOf(event(5), event(6)))
        val socket = FakeWebSocketConnection().apply { serve(helloOK(6)); serve(cursorFrame(6)) }
        val store = seededStore()
        val engine = makeEngine(store, listOf(socket), pages)
        engine.beginSync()
        engine.waitUntilReady()
        delay(100)
        assertTrue(pages.calls.isEmpty())
        assertTrue(store.events("c1").isEmpty())
        engine.endSync()
    }

    @Test
    fun openingAChatFetchesWhatItIsMissing() = runBlocking {
        val pages = FakePages(listOf(event(5), event(6)))
        val socket = FakeWebSocketConnection().apply { serve(helloOK(6)); serve(cursorFrame(6)) }
        val store = seededStore()
        val engine = makeEngine(store, listOf(socket), pages)
        engine.beginSync()
        engine.waitUntilReady()
        engine.setViewing("c1")
        waitUntil { store.events("c1").size == 2 }
        assertEquals(listOf(5L, 6L), store.events("c1").map { it.seq })
        assertEquals("c1", pages.calls.first().first)
        assertEquals(6L, store.syncedSeq("c1"))
        assertEquals("c1", sentOps(socket, "viewing").last().stringOrNull("convo_id"))
        // The settle read follows, one page.
        waitUntil { pages.calls.size >= 2 }
        assertEquals(2, pages.calls.size)
        waitUntil { engine.fetchingConversations.value.isEmpty() }
        assertTrue(engine.fetchingConversations.value.isEmpty())
        engine.endSync()
    }

    @Test
    fun aChatOpenedBeforeTheReplayEndsIsFetchedAtTheCursorFrame() = runBlocking {
        val pages = FakePages(listOf(event(5)))
        val socket = FakeWebSocketConnection().apply { serve(helloOK(9)) }
        val store = seededStore()
        val engine = makeEngine(store, listOf(socket), pages)
        engine.setViewing("c1")
        engine.beginSync()
        waitUntil { socket.sent.isNotEmpty() }
        delay(50)
        assertTrue("not before the replay is over", pages.calls.isEmpty())
        socket.serve(cursorFrame(9))
        waitUntil { store.events("c1").isNotEmpty() }
        assertEquals(listOf(5L), store.events("c1").map { it.seq })
        engine.endSync()
    }

    @Test
    fun theLoadingStateCoversTheFetch() = runBlocking {
        val pages = FakePages(listOf(event(5))).apply { gate = CompletableDeferred() }
        val socket = FakeWebSocketConnection().apply { serve(helloOK(6)); serve(cursorFrame(6)) }
        val engine = makeEngine(seededStore(), listOf(socket), pages)
        engine.beginSync()
        engine.waitUntilReady()
        engine.setViewing("c1")
        withTimeout(2000) { engine.fetchingConversations.first { "c1" in it } }
        pages.gate!!.complete(Unit)
        withTimeout(2000) { engine.fetchingConversations.first { it.isEmpty() } }
        engine.endSync()
    }

    @Test
    fun aGapTooLongToFillDropsTheOlderStoredEventsAndResetsTheTimeline() = runBlocking {
        val store = seededStore()
        store.insertHistory(listOf(event(1), event(2)))
        store.markForYouStart() // cursor 0: complete up to 0
        store.setSyncedSeq("c1", 2)
        val far = (1000L until 1000L + CONVERSATION_PAGE_SIZE * CONVERSATION_MAX_PAGES + 10).map { event(it) }
        val pages = FakePages(far)
        val socket = FakeWebSocketConnection().apply { serve(helloOK(5000)); serve(cursorFrame(5000)) }
        val engine = makeEngine(store, listOf(socket), pages)
        val resets = CompletableDeferred<Unit>()
        val listener = launch { engine.historyResets("c1").collect { resets.complete(Unit) } }
        delay(50)
        engine.beginSync()
        engine.waitUntilReady()
        engine.setViewing("c1")
        withTimeout(3000) { resets.await() }
        val seqs = store.events("c1").map { it.seq }
        assertEquals(CONVERSATION_PAGE_SIZE * CONVERSATION_MAX_PAGES, seqs.size)
        assertFalse(seqs.contains(1L))
        assertEquals(CONVERSATION_MAX_PAGES, pages.calls.count { it.third == CONVERSATION_PAGE_SIZE })
        listener.cancel()
        engine.endSync()
    }

    @Test
    fun leavingAChatRecordsItCompleteUpToTheCursor() = runBlocking {
        val pages = FakePages(listOf(event(5)))
        val socket = FakeWebSocketConnection().apply { serve(helloOK(6)); serve(cursorFrame(6)) }
        val store = seededStore()
        val engine = makeEngine(store, listOf(socket), pages)
        engine.beginSync()
        engine.waitUntilReady()
        engine.setViewing("c1")
        waitUntil { engine.fetchingConversations.value.isEmpty() && store.events("c1").isNotEmpty() }
        socket.serve(journalLine(7))
        waitUntil { store.cursor() == 7L }
        engine.setViewing("c2")
        waitUntil { store.syncedSeq("c1") == 7L }
        assertEquals(7L, store.syncedSeq("c1"))
        engine.endSync()
    }

    @Test
    fun clearViewingLeavesANewerChatViewed() = runBlocking {
        val socket = FakeWebSocketConnection().apply { serve(helloOK(0)); serve(cursorFrame(0)) }
        val engine = makeEngine(seededStore(), listOf(socket))
        engine.beginSync()
        engine.waitUntilReady()
        engine.setViewing("c1")
        engine.setViewing("c2")
        engine.clearViewing("c1")
        assertEquals(setOf("c2"), engine.viewedConversationIDs())
        engine.clearViewing("c2")
        assertTrue(engine.viewedConversationIDs().isEmpty())
        assertEquals(null, sentOps(socket, "viewing").last().stringOrNull("convo_id"))
        engine.endSync()
    }

    @Test
    fun aFailedFetchIsRetried() = runBlocking {
        val pages = FakePages(listOf(event(5))).apply { failuresLeft = 2 }
        val socket = FakeWebSocketConnection().apply { serve(helloOK(6)); serve(cursorFrame(6)) }
        val store = seededStore()
        val engine = makeEngine(store, listOf(socket), pages)
        engine.beginSync()
        engine.waitUntilReady()
        engine.setViewing("c1")
        waitUntil { store.events("c1").isNotEmpty() }
        assertEquals(listOf(5L), store.events("c1").map { it.seq })
        engine.endSync()
    }

    @Test
    fun aJournalWithoutTheScopeIsNeverAskedForPages() = runBlocking {
        val pages = FakePages(listOf(event(5)))
        val socket = FakeWebSocketConnection().apply { serve(helloOK(0, sync = null)) }
        val engine = makeEngine(seededStore(), listOf(socket), pages)
        engine.beginSync()
        engine.waitUntilReady()
        engine.setViewing("c1")
        delay(100)
        assertTrue(pages.calls.isEmpty())
        engine.endSync()
    }

    @Test
    fun aCursorFrameOnAnEverythingSocketIsIgnored() = runBlocking {
        val store = seededStore()
        val socket = FakeWebSocketConnection().apply {
            serve(helloOK(1, sync = "everything")); serve(journalLine(1)); serve(cursorFrame(99))
        }
        val engine = makeEngine(store, listOf(socket), scope = SyncScope.EVERYTHING)
        engine.beginSync()
        engine.waitUntilReady()
        delay(50)
        assertEquals(1L, store.cursor())
        engine.endSync()
    }

    @Test
    fun switchingToForYouKeepsTheMirrorAndReconnectsUnderTheScope() = runBlocking {
        val store = seededStore()
        val first = FakeWebSocketConnection().apply {
            serve(helloOK(2, sync = "everything")); serve(journalLine(1)); serve(journalLine(2))
        }
        val second = FakeWebSocketConnection().apply { serve(helloOK(2)); serve(cursorFrame(2)) }
        val engine = makeEngine(store, listOf(first, second), scope = SyncScope.EVERYTHING)
        engine.beginSync()
        engine.waitUntilReady()
        engine.setSyncScope(SyncScope.FOR_YOU)
        assertEquals(2L, store.forYouSince())
        waitUntil { second.sent.isNotEmpty() }
        assertEquals("for_you", sentOps(second, "hello").single().stringOrNull("sync"))
        assertEquals("nothing was dropped", listOf(1L, 2L), store.events("c1").map { it.seq })
        assertEquals(SyncScope.FOR_YOU, engine.syncScope.value)
        engine.endSync()
    }

    @Test
    fun switchingBackToEverythingRebuildsTheMirrorFromTheSnapshot() = runBlocking {
        val store = seededStore()
        store.insertHistory(listOf(event(1)))
        store.markForYouStart()
        val snapshot = FakeSnapshotSource().apply {
            response = SnapshotResponse(listOf(ConvoSummaryDTO("c1", "Fresh", "waiting", 30, "", 0)), 30)
        }
        val first = FakeWebSocketConnection().apply { serve(helloOK(0)); serve(cursorFrame(0)) }
        val second = FakeWebSocketConnection().apply { serve(helloOK(30, sync = "everything")) }
        val engine = makeEngine(store, listOf(first, second), snapshot = snapshot)
        engine.beginSync()
        engine.waitUntilReady()
        engine.setSyncScope(SyncScope.EVERYTHING)
        waitUntil { second.sent.isNotEmpty() }
        waitUntil { engine.stateStream.value is SyncConnectionState.Running && store.cursor() == 30L }
        assertNull(store.forYouSince())
        assertEquals(30L, store.cursor())
        assertTrue("the stale copy is gone", store.events("c1").isEmpty())
        assertEquals("Fresh", store.conversations().single { it.id == "c1" }.title)
        assertEquals(30L, sentOps(second, "hello").single().longOrNull("cursor"))
        engine.endSync()
    }

    @Test
    fun aSwitchBackMadeWhileOfflineStillRebuildsAtTheNextConnect() = runBlocking {
        val store = seededStore()
        store.insertHistory(listOf(event(1)))
        store.markForYouStart()
        val socket = FakeWebSocketConnection().apply { serve(helloOK(0, sync = "everything")) }
        val engine = makeEngine(store, listOf(socket), scope = SyncScope.EVERYTHING)
        engine.beginSync()
        engine.waitUntilReady()
        assertNull(store.forYouSince())
        assertTrue(store.events("c1").isEmpty())
        engine.endSync()
    }

    @Test
    fun onlyTheCoordinatorsSessionStateIsLiveOnForYou() = runBlocking {
        val socket = FakeWebSocketConnection().apply { serve(helloOK(0, coordinator = "coord")); serve(cursorFrame(0)) }
        val engine = makeEngine(seededStore(), listOf(socket))
        engine.beginSync()
        engine.waitUntilReady()
        assertTrue(engine.followsSessionStateLive("coord"))
        assertFalse(engine.followsSessionStateLive("c1"))
        engine.setSyncScope(SyncScope.EVERYTHING)
        assertTrue(engine.followsSessionStateLive("c1"))
        engine.endSync()
    }

    @Test
    fun theChatListRefreshIsPacedAndOnlyOnForYou() = runBlocking {
        val snapshots = java.util.concurrent.atomic.AtomicInteger()
        val source = object : SnapshotSource {
            override val wsUrl = "wss://x/ws"
            override suspend fun snapshot(): SnapshotResponse {
                snapshots.incrementAndGet()
                return SnapshotResponse(listOf(ConvoSummaryDTO("c1", "", "waiting", 3, "", 0, unreadCount = 3)), 3)
            }
        }
        var now = 1_000_000L
        val store = seededStore()
        val socket = FakeWebSocketConnection().apply { serve(helloOK(0)); serve(cursorFrame(0)) }
        val engine = JournalSyncEngine(
            api = source, store = store, connector = FakeConnector(listOf(socket)), token = "t",
            ownSender = "user:alice", backoffBaseSeconds = 0.01, pages = FakePages(), syncScope = SyncScope.FOR_YOU,
            clock = { now },
        )
        engine.beginSync()
        engine.waitUntilReady()
        delay(100)
        assertEquals("no chat list on screen: the connect reads no snapshot", 0, snapshots.get())
        engine.chatListDidAppear()
        waitUntil { snapshots.get() == 1 }
        engine.chatListDidDisappear()
        now += 30_000
        engine.chatListDidAppear()
        delay(100)
        assertEquals("within a minute of the last read", 1, snapshots.get())
        engine.chatListDidDisappear()
        now += 31_000
        engine.chatListDidAppear()
        waitUntil { snapshots.get() == 2 }
        assertEquals(2, snapshots.get())
        assertEquals("the journal's unread count is taken", 3, store.conversations().single { it.id == "c1" }.unreadCount)
        engine.endSync()
    }

    private class CountingSnapshots : SnapshotSource {
        override val wsUrl = "wss://x/ws"
        val count = java.util.concurrent.atomic.AtomicInteger()
        override suspend fun snapshot(): SnapshotResponse {
            count.incrementAndGet()
            return SnapshotResponse(listOf(ConvoSummaryDTO("c1", "", "waiting", 0, "", 0)), 0)
        }
    }

    @Test
    fun aForYouConnectReadsTheSnapshotOnlyWithTheChatListShownInFront() = runBlocking {
        for ((shown, inFront, expected) in listOf(Triple(false, true, 0), Triple(true, false, 0), Triple(true, true, 1))) {
            val snapshots = CountingSnapshots()
            val socket = FakeWebSocketConnection().apply { serve(helloOK(0)); serve(cursorFrame(0)) }
            val engine = makeEngine(seededStore(), listOf(socket), snapshot = snapshots, inFront = { inFront })
            if (shown) {
                engine.chatListDidAppear() // before the connect: nothing to refresh yet
            }
            engine.beginSync()
            engine.waitUntilReady()
            waitUntil(500) { snapshots.count.get() >= expected && expected > 0 }
            delay(50)
            assertEquals("list shown=$shown, in front=$inFront", expected, snapshots.count.get())
            engine.endSync()
        }
    }

    @Test
    fun anEverythingConnectAlwaysReadsTheSnapshot() = runBlocking {
        val snapshots = CountingSnapshots()
        val socket = FakeWebSocketConnection().apply { serve(helloOK(0, sync = "everything")) }
        val engine = makeEngine(seededStore(), listOf(socket), snapshot = snapshots, scope = SyncScope.EVERYTHING,
            inFront = { false })
        engine.beginSync()
        engine.waitUntilReady()
        waitUntil { snapshots.count.get() == 1 }
        assertEquals(1, snapshots.count.get())
        engine.endSync()
    }

    @Test
    fun returningToTheFrontRefreshesAChatListAlreadyShown() = runBlocking {
        val snapshots = CountingSnapshots()
        var now = 1_000_000L
        val socket = FakeWebSocketConnection().apply { serve(helloOK(0)); serve(cursorFrame(0)) }
        val engine = JournalSyncEngine(
            api = snapshots, store = seededStore(), connector = FakeConnector(listOf(socket)), token = "t",
            ownSender = "user:alice", backoffBaseSeconds = 0.01, pages = FakePages(), syncScope = SyncScope.FOR_YOU,
            clock = { now },
        )
        engine.beginSync()
        engine.waitUntilReady()
        engine.appCameToFront()
        delay(100)
        assertEquals("no list on screen: nothing to refresh", 0, snapshots.count.get())
        engine.chatListDidAppear()
        waitUntil { snapshots.count.get() == 1 }
        now += 70_000
        engine.appCameToFront()
        waitUntil { snapshots.count.get() == 2 }
        assertEquals(2, snapshots.count.get())
        engine.endSync()
    }

    @Test
    fun aScopeChangeDuringTheHandshakeReconnectsUnderTheNewScope() = runBlocking {
        val first = FakeWebSocketConnection() // no hello_ok yet
        val second = FakeWebSocketConnection().apply { serve(helloOK(0, sync = "everything")) }
        val engine = makeEngine(seededStore(), listOf(first, second))
        engine.beginSync()
        waitUntil { first.sent.isNotEmpty() }
        assertEquals("for_you", sentOps(first, "hello").single().stringOrNull("sync"))
        engine.setSyncScope(SyncScope.EVERYTHING)
        first.serve(helloOK(0))
        waitUntil { second.sent.isNotEmpty() }
        assertEquals("everything", sentOps(second, "hello").single().stringOrNull("sync"))
        engine.waitUntilReady()
        engine.endSync()
    }

    @Test
    fun aSettleReadIsNotWrittenAfterTheChatCloses() = runBlocking {
        val pages = FakePages(listOf(event(5)))
        val socket = FakeWebSocketConnection().apply { serve(helloOK(6)); serve(cursorFrame(6)) }
        val store = seededStore()
        val engine = JournalSyncEngine(
            api = FakeSnapshotSource(), store = store, connector = FakeConnector(listOf(socket)), token = "t",
            ownSender = "user:alice", backoffBaseSeconds = 0.01, pages = pages, syncScope = SyncScope.FOR_YOU,
            conversationSettleDelay = 300.milliseconds,
        )
        engine.beginSync()
        engine.waitUntilReady()
        engine.setViewing("c1")
        waitUntil { store.events("c1").isNotEmpty() }
        pages.events = listOf(event(5), event(9)) // only the settle read would bring 9
        engine.setViewing("c2")
        delay(600)
        assertEquals(listOf(5L), store.events("c1").map { it.seq })
        engine.endSync()
    }

    @Test
    fun theSettingAppliesTheNewestChoice() = runBlocking {
        val prefs = InMemoryKeyValueStore()
        val applied = CopyOnWriteArrayList<SyncScope>()
        val model = SyncScopeSettingModel(prefs, { delay(50); applied += it }, CoroutineScope(Dispatchers.Default))
        model.select(SyncScope.FOR_YOU)
        model.select(SyncScope.EVERYTHING)
        waitUntil { applied.size == 2 }
        delay(100)
        assertEquals(SyncScope.EVERYTHING, applied.last())
        assertEquals(SyncScope.EVERYTHING, SyncScope.stored(prefs))
    }
}
