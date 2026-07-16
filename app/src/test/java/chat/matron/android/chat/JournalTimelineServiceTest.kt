package chat.matron.android.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.ConvoSummaryDTO
import chat.matron.android.journal.FakeConnector
import chat.matron.android.journal.FakeWebSocketConnection
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalStore
import chat.matron.android.journal.JournalSyncEngine
import chat.matron.android.journal.JournalSyncError
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.TimelineSendState
import chat.matron.android.models.UserSession
import chat.matron.android.search.SearchHit
import chat.matron.android.search.SearchService
import java.time.Instant
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Ported from matron-apple's `JournalTimelineServiceTests`. In-memory Room +
/// scripted fake socket under Robolectric; MockWebServer backs `api` for the
/// media/pagination HTTP paths. Real time + polling, matching the Apple suite.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalTimelineServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private suspend fun makeStore(convoID: String = "c1"): JournalStore {
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO(convoID, "", "running", 0, "", 0)), headSeq = 0)
        return store
    }

    private fun makeEngine(store: JournalStore, connector: chat.matron.android.journal.WebSocketConnecting, api: JournalApi) =
        JournalSyncEngine(api, store, connector, token = "t", ownSender = "user:dan", search = null, backoffBaseSeconds = 0.01)

    private fun makeSession() = UserSession("dan", "d1", "https://x", "t")

    private fun helloOK(head: Long) = """{"kind":"control","op":"hello_ok","seq":$head}"""

    private fun body(text: String) = buildJsonObject { put("body", text) }

    private fun journalFrame(
        seq: Long, convo: String = "c1", sender: String = "agent:a", type: String = "text", payload: JsonObject,
    ): String = buildJsonObject {
        put("kind", "journal"); put("seq", seq); put("convo_id", convo); put("ts", seq * 1000)
        put("sender", sender); put("type", type); put("payload", payload)
    }.toString()

    private fun ev(seq: Long, convo: String = "c1", sender: String = "agent:a", type: String = "text", payload: JsonObject) =
        JournalEvent(seq, convo, Instant.ofEpochMilli(seq * 1000), sender, type, payload)

    private suspend fun waitUntil(timeoutMs: Long = 3000, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(15)
        }
        fail("timed out waiting for condition")
    }

    private class ItemsCollector {
        private val values = Collections.synchronizedList(mutableListOf<List<TimelineItem>>())
        fun add(items: List<TimelineItem>) = values.add(items)
        fun last(): List<TimelineItem>? = synchronized(values) { values.lastOrNull() }
    }

    private fun CoroutineScope.collectItems(flow: Flow<List<TimelineItem>>): Pair<ItemsCollector, Job> {
        val collector = ItemsCollector()
        val job = launch { runCatching { flow.collect { collector.add(it) } } }
        return collector to job
    }

    private fun toolStreamFrame(ref: String, event: String) =
        """{"kind":"ephemeral","convo_id":"c1","message_ref":"$ref","tool_stream":$event}"""

    private fun toolStreamItem(items: List<TimelineItem>?, ref: String): TimelineItem? =
        items?.firstOrNull { it.id == "toolstream:$ref" }

    private fun viewingCount(socket: FakeWebSocketConnection): Int =
        socket.sent.mapNotNull { parseJsonObjectOrNull(it) }
            .count { it.stringOrNull("op") == "viewing" && it.stringOrNull("convo_id") != null }

    // MARK: (a) store events surface as mapped items

    @Test fun storeEventsSurfaceAsMappedItems() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, payload = body("hello")))
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(1)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync()
        engine.waitUntilReady()

        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last()?.size == 1 }
        val items = collector.last()!!
        assertEquals("1", items.first().id)
        assertEquals(TimelineSendState.Sent, items.first().sendState)
        assertEquals(TimelineItem.Kind.Text("hello", null), items.first().kind)

        task.cancel()
        engine.endSync()
    }

    // MARK: (b) ephemeral overlay inserts + finalize removes it

    @Test fun ephemeralOverlayInsertsAndFinalizeRemoves() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync()
        engine.waitUntilReady()

        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        socket.serve("""{"kind":"ephemeral","convo_id":"c1","message_ref":"m1","replace_text":"thinking…"}""")
        waitUntil { collector.last()?.any { it.id == "eph:m1" } == true }
        val overlaid = collector.last()!!
        assertEquals(1, overlaid.size)
        assertEquals(TimelineItem.Kind.Text("thinking…", null), overlaid.first().kind)
        assertFalse(overlaid.first().isOwn)

        socket.serve(journalFrame(1, type = "text", payload = buildJsonObject { put("body", "final answer"); put("message_ref", "m1") }))
        waitUntil {
            val last = collector.last() ?: return@waitUntil false
            last.size == 1 && last.none { it.id == "eph:m1" }
        }
        assertEquals("1", collector.last()!!.first().id)

        task.cancel()
        engine.endSync()
    }

    @Test fun activityIndicatorAppearsAndIdleClears() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()

        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        socket.serve("""{"kind":"ephemeral","convo_id":"c1","activity":{"state":"tool","detail":"Bash"}}""")
        waitUntil { collector.last()?.any { it.id == "activity" } == true }
        val withIndicator = collector.last()!!.first { it.id == "activity" }
        assertEquals(TimelineItem.Kind.ActivityIndicator("Running Bash"), withIndicator.kind)

        socket.serve("""{"kind":"ephemeral","convo_id":"c1","activity":{"state":"idle"}}""")
        waitUntil { collector.last()?.any { it.id == "activity" } == false }

        task.cancel(); engine.endSync()
    }

    @Test fun finalizeWithoutMessageRefRetiresOverlayByBody() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()

        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        socket.serve("""{"kind":"ephemeral","convo_id":"c1","message_ref":"m1","replace_text":"final answer"}""")
        waitUntil { collector.last()?.any { it.id == "eph:m1" } == true }

        socket.serve(journalFrame(1, type = "text", payload = body("final answer")))
        waitUntil {
            val last = collector.last() ?: return@waitUntil false
            last.size == 1 && last.none { it.id == "eph:m1" }
        }
        assertEquals("1", collector.last()!!.first().id)

        task.cancel(); engine.endSync()
    }

    // MARK: (c) sendText local echo -> op reaches socket -> reconciles

    @Test fun sendTextEmitsLocalEchoOpReachesSocketAndReconciles() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()

        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        service.sendText("hi there", inReplyTo = null)
        waitUntil { collector.last()?.any { it.sendState == TimelineSendState.Sending && it.isOwn } == true }
        val echoed = collector.last()!!
        assertEquals(1, echoed.size)
        assertEquals(TimelineSendState.Sending, echoed.first().sendState)
        assertEquals(TimelineItem.Kind.Text("hi there", null), echoed.first().kind)

        waitUntil { socket.lastSentObject?.stringOrNull("op") == "send" }
        val sent = socket.lastSentObject
        assertEquals("c1", sent?.stringOrNull("convo_id"))
        assertEquals("hi there", sent?.objectOrNull("payload")?.stringOrNull("body"))

        socket.serve(journalFrame(1, sender = "user:dan", type = "text", payload = body("hi there")))
        waitUntil {
            val last = collector.last() ?: return@waitUntil false
            last.size == 1 && last.first().sendState == TimelineSendState.Sent
        }
        val reconciled = collector.last()!!
        assertEquals("1", reconciled.first().id)
        assertTrue(reconciled.first().isOwn)

        task.cancel(); engine.endSync()
    }

    @Test fun sendTextWithReplyToSendsPromptReplyOp() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()

        service.sendText("yes please", inReplyTo = "3")
        waitUntil { socket.lastSentObject?.stringOrNull("op") == "prompt_reply" }
        val sent = socket.lastSentObject
        assertEquals(3L, sent?.longOrNull("target_seq"))
        assertEquals("yes please", sent?.stringOrNull("text"))
        assertNull(sent?.stringOrNull("choice"))
        assertEquals("c1", sent?.stringOrNull("convo_id"))

        engine.endSync()
    }

    @Test fun markAsReadSendsReadMarkerWithMaxSeq() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, payload = body("a")))
        store.applyJournal(ev(2, payload = body("b")))
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(2)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()

        service.markAsRead()
        waitUntil { socket.lastSentObject?.stringOrNull("op") == "read_marker" }
        val sent = socket.lastSentObject
        assertEquals(2L, sent?.longOrNull("up_to_seq"))
        assertEquals("c1", sent?.stringOrNull("convo_id"))

        engine.endSync()
    }

    // MARK: sendFile / sendImage upload then send a media op

    private fun mediaDispatcher(mediaID: String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path?.substringBefore('?') ?: ""
            return if (path == "/media") MockResponse().setResponseCode(200).setBody("""{"media_id":"$mediaID"}""")
            else MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}""")
        }
    }

    @Test fun sendFileUploadsMediaAndSendsFileOp() = runBlocking {
        val server = MockWebServer().apply { dispatcher = mediaDispatcher("blob-9"); start() }
        try {
            val store = makeStore()
            val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
            val api = JournalApi(server.url("/"))
            val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
            val service = JournalTimelineService("c1", store, engine, api, makeSession())
            engine.beginSync(); engine.waitUntilReady()

            service.sendFile("hello".toByteArray(), "notes.txt", "text/plain", null)
            waitUntil { socket.lastSentObject?.stringOrNull("op") == "send" }
            val sent = socket.lastSentObject
            assertEquals("file", sent?.stringOrNull("type"))
            assertEquals("blob-9", sent?.stringOrNull("blob_ref"))
            assertEquals("c1", sent?.stringOrNull("convo_id"))
            assertTrue(sent?.stringOrNull("local_id") != null)
            val payload = sent?.objectOrNull("payload")
            assertEquals("blob-9", payload?.stringOrNull("blob_ref"))
            assertEquals("notes.txt", payload?.stringOrNull("name"))
            assertEquals("text/plain", payload?.stringOrNull("content_type"))
            assertEquals(5L, payload?.longOrNull("size"))

            engine.endSync()
        } finally {
            server.shutdown()
        }
    }

    @Test fun sendImageUploadsMediaAndSendsImageOp() = runBlocking {
        val server = MockWebServer().apply { dispatcher = mediaDispatcher("blob-img"); start() }
        try {
            val store = makeStore()
            val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
            val api = JournalApi(server.url("/"))
            val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
            val service = JournalTimelineService("c1", store, engine, api, makeSession())
            engine.beginSync(); engine.waitUntilReady()

            service.sendImage("PNGBYTES".toByteArray(), "cat.png", "image/png", "what breed?")
            waitUntil { socket.lastSentObject?.stringOrNull("op") == "send" }
            val sent = socket.lastSentObject
            assertEquals("image", sent?.stringOrNull("type"))
            assertEquals("blob-img", sent?.stringOrNull("blob_ref"))
            val payload = sent?.objectOrNull("payload")
            assertEquals("image/png", payload?.stringOrNull("content_type"))
            assertEquals(8L, payload?.longOrNull("size"))
            assertEquals("what breed?", payload?.stringOrNull("caption"))

            engine.endSync()
        } finally {
            server.shutdown()
        }
    }

    // MARK: paginateBackward inserts history + feeds search, false on empty page

    @Test fun paginateBackwardInsertsHistoryAndIndexesSearch() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"events":[{"seq":8,"convo_id":"c1","ts":8000,"sender":"agent:a","type":"text","payload":{"body":"older msg"}}]}"""
                )
            )
            start()
        }
        try {
            val store = makeStore()
            store.applyJournal(ev(10, payload = body("recent")))
            val api = JournalApi(server.url("/"))
            val engine = makeEngine(store, FakeConnector(emptyList()), api)
            val search = RecordingSearchService()
            val service = JournalTimelineService("c1", store, engine, api, makeSession(), search = search)

            val hasMore = service.paginateBackward(20)
            assertTrue(hasMore)
            assertEquals(listOf(8L, 10L), store.events("c1").map { it.seq })

            val recorded = server.takeRequest()
            assertTrue(recorded.path!!.contains("before_seq=10"))
            assertTrue(recorded.path!!.contains("limit=20"))

            assertEquals(1, search.indexed.size)
            assertEquals("older msg", search.indexed.first().body)
            assertEquals("8", search.indexed.first().eventID)
        } finally {
            server.shutdown()
        }
    }

    @Test fun paginateBackwardReturnsFalseOnEmptyPage() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody("""{"events":[]}"""))
            start()
        }
        try {
            val store = makeStore()
            val api = JournalApi(server.url("/"))
            val engine = makeEngine(store, FakeConnector(emptyList()), api)
            val service = JournalTimelineService("c1", store, engine, api, makeSession())

            assertFalse(service.paginateBackward(20))
            assertTrue(store.events("c1").isEmpty())
        } finally {
            server.shutdown()
        }
    }

    // MARK: (f) sendText failure flips echo to failed and rethrows

    @Test fun sendTextFailureMarksEchoFailed() = runBlocking {
        val store = makeStore()
        val api = JournalApi("https://x")
        // No socket, beginSync never called: sendOp throws offline synchronously.
        val engine = makeEngine(store, FakeConnector(emptyList()), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())

        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        try {
            service.sendText("hi there", inReplyTo = null)
            fail("expected sendText to rethrow the offline send failure")
        } catch (e: JournalSyncError) {
            assertEquals(JournalSyncError.Offline, e)
        }

        waitUntil {
            collector.last()?.any { it.isOwn && it.sendState == TimelineSendState.Failed("Not delivered") } == true
        }
        val failed = collector.last()!!
        assertEquals(1, failed.size)
        assertEquals(TimelineSendState.Failed("Not delivered"), failed.first().sendState)
        assertEquals(TimelineItem.Kind.Text("hi there", null), failed.first().kind)

        task.cancel()
    }

    @Test fun sendButtonResponseRejectsNonNumericPromptID() = runBlocking {
        val store = makeStore()
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(emptyList()), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        try {
            service.sendButtonResponse(listOf("Yes"), inReplyTo = "echo:abc")
            fail("expected invalidPromptReference")
        } catch (e: JournalChatError) {
            assertEquals(JournalChatError.InvalidPromptReference("echo:abc"), e)
        }
    }

    // MARK: OverlayState echo reconciliation (no engine)

    @Test fun reconcileSkipsFailedEchoOnDuplicateBody() = runBlocking {
        val overlay = JournalTimelineService.OverlayState(staleness = 30.seconds)
        overlay.addEcho("failed-one", "dup")
        overlay.markEchoFailed("failed-one")
        overlay.addEcho("delivered-one", "dup")
        overlay.reconcile(listOf(ev(1, sender = "user:dan", payload = body("dup"))), "user:dan")
        val echoes = overlay.echoes
        assertEquals(listOf("failed-one"), echoes.map { it.localID })
        assertTrue(echoes.first().failed)
    }

    @Test fun failedEchoSurvivesStalenessSweep() = runBlocking {
        val overlay = JournalTimelineService.OverlayState(staleness = 20.milliseconds)
        overlay.addEcho("gone", "pending one")
        overlay.addEcho("kept", "failed one")
        overlay.markEchoFailed("kept")
        delay(60)
        overlay.reconcile(emptyList(), "user:dan")
        assertEquals(listOf("kept"), overlay.echoes.map { it.localID })
    }

    @Test fun deliveredRetryClearsFailedEcho() = runBlocking {
        val overlay = JournalTimelineService.OverlayState(staleness = 30.seconds)
        overlay.addEcho("failed-one", "dup")
        overlay.markEchoFailed("failed-one")
        overlay.reconcile(listOf(ev(1, sender = "user:dan", payload = body("dup"))), "user:dan")
        assertTrue(overlay.echoes.isEmpty())
    }

    @Test fun oldHistoryRowDoesNotClearFailedEcho() = runBlocking {
        val overlay = JournalTimelineService.OverlayState(staleness = 30.seconds)
        val oldOwnRow = ev(5, sender = "user:dan", payload = body("dup"))
        overlay.reconcile(listOf(oldOwnRow), "user:dan")
        overlay.addEcho("fresh-fail", "dup")
        overlay.markEchoFailed("fresh-fail")
        overlay.reconcile(listOf(oldOwnRow), "user:dan")
        assertEquals(listOf("fresh-fail"), overlay.echoes.map { it.localID })
    }

    // MARK: (g) stalled overlay self-prunes via the periodic sweep

    @Test fun stalledOverlaySelfPrunesViaPeriodicSweep() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService(
            "c1", store, engine, api, makeSession(),
            overlayStaleness = 80.milliseconds, sweepInterval = 40.milliseconds,
        )
        engine.beginSync(); engine.waitUntilReady()

        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        socket.serve("""{"kind":"ephemeral","convo_id":"c1","message_ref":"m1","replace_text":"thinking…"}""")
        waitUntil { collector.last()?.any { it.id == "eph:m1" } == true }
        waitUntil(timeoutMs = 1500) { collector.last()?.none { it.id == "eph:m1" } == true }

        task.cancel(); engine.endSync()
    }

    // MARK: (h) tool_stream overlay

    @Test fun toolStreamAppendsCoalesceAndOverlapTrims() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()
        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":0,"chunk":"one"}"""))
        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":3,"chunk":"two"}"""))
        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":3,"chunk":"twoXYZ"}"""))

        waitUntil {
            val kind = toolStreamItem(collector.last(), "tu1")?.kind as? TimelineItem.Kind.ToolStreamLive
            kind?.text == "onetwoXYZ"
        }
        task.cancel(); engine.endSync()
    }

    @Test fun toolStreamSyncReplacesContentAndSuppliesMeta() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()
        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":0,"chunk":"junk"}"""))
        socket.serve(toolStreamFrame("tu1", """{"event":"sync","meta":{"tool":"Bash","command":"make"},"offset":0,"content":"$ make\n","head_truncated":false}"""))

        waitUntil {
            val kind = toolStreamItem(collector.last(), "tu1")?.kind as? TimelineItem.Kind.ToolStreamLive
            kind?.command == "make" && kind.text == "$ make\n" && !kind.headTruncated
        }
        task.cancel(); engine.endSync()
    }

    @Test fun toolStreamGapDropsChunkAndResendsViewing() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()
        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }
        waitUntil { viewingCount(socket) == 1 }

        socket.serve(toolStreamFrame("tu1", """{"event":"sync","meta":{"tool":"Bash","command":"make"},"offset":0,"content":"ab","head_truncated":false}"""))
        waitUntil { toolStreamItem(collector.last(), "tu1") != null }
        assertEquals(1, viewingCount(socket))

        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":999,"chunk":"lost"}"""))
        waitUntil { viewingCount(socket) == 2 }
        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":999,"chunk":"lost"}"""))
        socket.serve(journalFrame(1, payload = body("marker")))
        waitUntil { collector.last()?.any { it.id == "1" } == true }

        assertEquals(2, viewingCount(socket))
        val kind = toolStreamItem(collector.last(), "tu1")?.kind as? TimelineItem.Kind.ToolStreamLive
        assertEquals("ab", kind?.text)

        task.cancel(); engine.endSync()
    }

    @Test fun toolStreamMidJoinWithoutSyncRequestsViewing() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()
        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }
        waitUntil { viewingCount(socket) == 1 }

        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":512,"chunk":"tail"}"""))
        waitUntil { viewingCount(socket) == 2 }
        assertNull(toolStreamItem(collector.last(), "tu1"))

        task.cancel(); engine.endSync()
    }

    @Test fun toolStreamEndRemovesTile() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()
        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":0,"chunk":"x"}"""))
        waitUntil { toolStreamItem(collector.last(), "tu1") != null }
        socket.serve(toolStreamFrame("tu1", """{"event":"end","reason":"stale"}"""))
        waitUntil { toolStreamItem(collector.last(), "tu1") == null }

        task.cancel(); engine.endSync()
    }

    @Test fun toolStreamEndRetiresRefAndLateAppendIsIgnored() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()
        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":0,"chunk":"x"}"""))
        waitUntil { toolStreamItem(collector.last(), "tu1") != null }
        socket.serve(toolStreamFrame("tu1", """{"event":"end","reason":"stale"}"""))
        waitUntil { toolStreamItem(collector.last(), "tu1") == null }
        val viewingsBeforeLate = viewingCount(socket)

        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":1,"chunk":"late"}"""))
        socket.serve(journalFrame(1, payload = body("marker")))
        waitUntil { collector.last()?.any { it.id == "1" } == true }

        assertNull(toolStreamItem(collector.last(), "tu1"))
        assertEquals(viewingsBeforeLate, viewingCount(socket))

        task.cancel(); engine.endSync()
    }

    @Test fun durableToolOutputRetiresTileAndLateAppendIsIgnored() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService("c1", store, engine, api, makeSession())
        engine.beginSync(); engine.waitUntilReady()
        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":0,"chunk":"$ make\n"}"""))
        waitUntil { toolStreamItem(collector.last(), "tu1") != null }
        val viewingsBeforeRetire = viewingCount(socket)

        socket.serve(journalFrame(1, type = "tool_output", payload = buildJsonObject {
            put("message_ref", "tu1"); put("command", "make"); put("exit_code", 0); put("denied", false)
            put("truncated", false); put("snippet", "$ make"); put("blob_ref", "b1"); put("live_log", true)
        }))
        waitUntil {
            val last = collector.last() ?: return@waitUntil false
            last.any { it.id == "1" } && last.none { it.id == "toolstream:tu1" }
        }

        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":7,"chunk":"late"}"""))
        socket.serve(journalFrame(2, payload = body("marker")))
        waitUntil { collector.last()?.any { it.id == "2" } == true }

        assertNull(toolStreamItem(collector.last(), "tu1"))
        assertEquals(viewingsBeforeRetire, viewingCount(socket))

        task.cancel(); engine.endSync()
    }

    @Test fun toolStreamSurvivesTextStalenessSweepButNotToolStaleness() = runBlocking {
        val store = makeStore()
        val socket = FakeWebSocketConnection().also { it.serve(helloOK(0)) }
        val api = JournalApi("https://x")
        val engine = makeEngine(store, FakeConnector(listOf(socket)), api)
        val service = JournalTimelineService(
            "c1", store, engine, api, makeSession(),
            overlayStaleness = 80.milliseconds, sweepInterval = 40.milliseconds, toolStreamStaleness = 400.milliseconds,
        )
        engine.beginSync(); engine.waitUntilReady()
        val (collector, task) = collectItems(service.items())
        waitUntil { collector.last() != null }

        socket.serve(toolStreamFrame("tu1", """{"event":"append","offset":0,"chunk":"quiet build"}"""))
        waitUntil { toolStreamItem(collector.last(), "tu1") != null }

        delay(160)
        assertTrue(toolStreamItem(collector.last(), "tu1") != null)

        waitUntil(timeoutMs = 1500) { toolStreamItem(collector.last(), "tu1") == null }

        task.cancel(); engine.endSync()
    }
}

/// Records every `index(...)` call so pagination tests can assert on the fed
/// body + event id. Ported from the Apple `RecordingSearchService`.
private class RecordingSearchService : SearchService {
    data class Indexed(val roomID: String, val eventID: String, val sender: String, val body: String)
    val indexed = Collections.synchronizedList(mutableListOf<Indexed>())

    override suspend fun index(roomID: String, eventID: String, sender: String, timestamp: Instant, body: String) {
        indexed.add(Indexed(roomID, eventID, sender, body))
    }
    override suspend fun remove(eventID: String) {}
    override suspend fun query(text: String, limit: Int): List<SearchHit> = emptyList()
    override suspend fun wipe() {}
    override suspend fun recordBackfillProgress(roomID: String, indexedCount: Int, oldestEventID: String?, complete: Boolean) {}
    override suspend fun backfillComplete(roomID: String): Boolean = true
    override suspend fun eventCount(roomID: String): Int = 0
    override suspend fun contains(eventID: String): Boolean = false
}
