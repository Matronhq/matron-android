package chat.matron.android.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.SyncConnectionState
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Ported from matron-apple JournalSyncEngineTests. The store is a real
/// in-memory Room database (under Robolectric); the socket is scripted via
/// [FakeWebSocketConnection]. Timing follows the Apple suite: tiny real backoff
/// plus polling, not virtual time (the originals do the same).
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalSyncEngineTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun journalLine(
        seq: Long, convo: String = "c1", sender: String = "agent:a", type: String = "text", body: String = "m",
    ) = """{"kind":"journal","seq":$seq,"convo_id":"$convo","ts":${seq * 1000},"sender":"$sender","type":"$type","payload":{"body":"$body$seq"}}"""

    private fun helloOK(head: Long) = """{"kind":"control","op":"hello_ok","seq":$head}"""

    private suspend fun seededStore(): JournalStore {
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("c1", "", "running", 0, "", 0)), headSeq = 0)
        return store
    }

    private fun makeEngine(
        store: JournalStore,
        connector: WebSocketConnecting,
        snapshot: SnapshotSource = FakeSnapshotSource(),
        backoffBaseSeconds: Double = 0.01,
    ) = JournalSyncEngine(
        api = snapshot, store = store, connector = connector, token = "t",
        ownSender = "user:dan", search = null, backoffBaseSeconds = backoffBaseSeconds,
    )

    private suspend fun waitUntil(timeoutMs: Long = 2000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
    }

    private fun sentAgentRequests(socket: FakeWebSocketConnection) =
        socket.sent.mapNotNull { parseJsonObjectOrNull(it) }.filter { it.stringOrNull("op") == "agent_request" }

    /// Collects a flow into an unbounded channel so a test can pull items in
    /// order, mirroring the Apple suite's `makeAsyncIterator()`.
    private class FlowProbe<T>(scope: CoroutineScope, flow: Flow<T>) {
        private val channel = Channel<T>(Channel.UNLIMITED)
        private val job: Job = scope.launch { flow.collect { channel.send(it) } }
        suspend fun next(timeoutMs: Long = 2000): T = withTimeout(timeoutMs) { channel.receive() }
        fun cancel() = job.cancel()
    }

    @Test
    fun replayAppliesToStoreAndReachesRunning() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(3))
        socket.serve(journalLine(1))
        socket.serve(journalLine(2))
        socket.serve(journalLine(3))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        assertEquals(3L, store.cursor())
        assertEquals(listOf(1L, 2L, 3L), store.events("c1").map { it.seq })
        engine.endSync()
    }

    @Test
    fun reconnectResumesFromCursorAfterSocketDeath() = runBlocking {
        val first = FakeWebSocketConnection()
        first.serve(helloOK(2)); first.serve(journalLine(1)); first.serve(journalLine(2))
        val second = FakeWebSocketConnection()
        second.serve(helloOK(4)); second.serve(journalLine(3)); second.serve(journalLine(4))
        val store = seededStore()
        val connector = FakeConnector(listOf(first, second))
        val engine = makeEngine(store, connector)
        engine.beginSync()
        engine.waitUntilReady()
        first.closeFromServer()

        waitUntil { store.cursor() >= 4 }
        assertEquals(4L, store.cursor())
        assertEquals(2, connector.connectCount)
        val hello = parseJsonObjectOrNull(second.sent.first())!!
        assertEquals(2L, hello.longOrNull("cursor"))
        engine.endSync()
    }

    @Test
    fun duplicateReplayFramesAreIdempotent() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(2)); socket.serve(journalLine(1)); socket.serve(journalLine(1)); socket.serve(journalLine(2))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        assertEquals(2, store.events("c1").size)
        engine.endSync()
    }

    @Test
    fun ephemeralFanOut() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        val probe = FlowProbe(this, engine.ephemerals("c1"))
        delay(50)
        socket.serve("""{"kind":"ephemeral","convo_id":"c1","message_ref":"m1","replace_text":"working…"}""")
        assertEquals("working…", probe.next().replaceText)
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun toolStreamFanOutToMatchingConvoOnly() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        val probeC1 = FlowProbe(this, engine.toolStreams("c1"))
        val probeC2 = FlowProbe(this, engine.toolStreams("c2"))
        delay(50)
        socket.serve("""{"kind":"ephemeral","convo_id":"c2","message_ref":"tu9","tool_stream":{"event":"end","reason":"stale"}}""")
        socket.serve("""{"kind":"ephemeral","convo_id":"c1","message_ref":"tu1","tool_stream":{"event":"append","offset":0,"chunk":"hi"}}""")
        assertEquals(
            ToolStreamUpdate("c1", "tu1", ToolStreamUpdate.Event.Append(0, "hi")),
            probeC1.next(),
        )
        assertEquals(
            ToolStreamUpdate("c2", "tu9", ToolStreamUpdate.Event.End("stale")),
            probeC2.next(),
        )
        probeC1.cancel(); probeC2.cancel()
        engine.endSync()
    }

    @Test
    fun sessionStatusFanOutToMatchingConvoOnly() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        val probeC1 = FlowProbe(this, engine.sessionStatus("c1"))
        delay(50)
        socket.serve("""{"kind":"ephemeral","convo_id":"c2","status":{"model":"other"}}""")
        socket.serve("""{"kind":"ephemeral","convo_id":"c1","status":{"context":{"tokens":265000,"window":1000000,"pct":27}}}""")
        val update = probeC1.next()
        assertEquals("c1", update.convoID)
        assertEquals(27, update.context?.pct)
        probeC1.cancel()
        engine.endSync()
    }

    @Test
    fun sessionStatusReplaysCachedFrameOnSubscribe() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        socket.serve("""{"kind":"ephemeral","convo_id":"c1","status":{"context":{"tokens":100,"window":1000,"pct":10}}}""")
        delay(50)
        val probe = FlowProbe(this, engine.sessionStatus("c1"))
        assertEquals(10, probe.next().context?.pct)
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun sessionStatusLiveFramesFollowReplay() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        socket.serve("""{"kind":"ephemeral","convo_id":"c1","status":{"context":{"tokens":100,"window":1000,"pct":10}}}""")
        delay(50)
        val probe = FlowProbe(this, engine.sessionStatus("c1"))
        assertEquals(10, probe.next().context?.pct)
        socket.serve("""{"kind":"ephemeral","convo_id":"c1","status":{"context":{"tokens":200,"window":1000,"pct":20}}}""")
        assertEquals(20, probe.next().context?.pct)
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun sessionStatusReplayDoesNotCrossConvos() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        socket.serve("""{"kind":"ephemeral","convo_id":"c1","status":{"context":{"tokens":100,"window":1000,"pct":10}}}""")
        delay(50)
        val probeC2 = FlowProbe(this, engine.sessionStatus("c2"))
        socket.serve("""{"kind":"ephemeral","convo_id":"c2","status":{"context":{"tokens":50,"window":500,"pct":99}}}""")
        val update = probeC2.next()
        assertEquals("c2", update.convoID)
        assertEquals(99, update.context?.pct)
        probeC2.cancel()
        engine.endSync()
    }

    @Test
    fun sessionStatusReplayMergesPartialFrames() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        socket.serve("""{"kind":"ephemeral","convo_id":"c1","status":{"model":"fable","context":{"tokens":100,"window":1000,"pct":10}}}""")
        socket.serve("""{"kind":"ephemeral","convo_id":"c1","status":{"limits":[{"label":"Session","percent":42}]}}""")
        delay(50)
        val probe = FlowProbe(this, engine.sessionStatus("c1"))
        val replayed = probe.next()
        assertEquals("fable", replayed.model)
        assertEquals(10, replayed.context?.pct)
        assertEquals(42, replayed.limits?.first()?.percent)
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun newConversationsEmittedOnlyForLiveBornConvos() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(1))
        socket.serve(journalLine(1))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()

        val probe = FlowProbe(this, engine.newConversations())
        delay(50)
        socket.serve(journalLine(2, convo = "c1"))
        socket.serve(journalLine(3, convo = "c2"))
        socket.serve(journalLine(4, convo = "c2"))
        socket.serve(journalLine(5, convo = "c3"))
        assertEquals("c2", probe.next())
        assertEquals("c3", probe.next())
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun reconnectBacklogNewConvosDoNotAutoOpen() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(3))
        socket.serve(journalLine(1, convo = "cX"))
        socket.serve(journalLine(2, convo = "cY"))
        socket.serve(journalLine(3, convo = "cZ"))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))

        val probe = FlowProbe(this, engine.newConversations())
        delay(20)
        engine.beginSync()
        engine.waitUntilReady()

        delay(20)
        socket.serve(journalLine(4, convo = "cLive"))
        assertEquals("cLive", probe.next())
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun liveBornChildDoesNotAutoOpen() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(1))
        socket.serve(journalLine(1))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()

        val probe = FlowProbe(this, engine.newConversations())
        delay(50)
        socket.serve("""{"kind":"journal","seq":2,"convo_id":"c1:sub:a1","ts":2000,"sender":"agent:a","type":"convo_meta","payload":{"title":"explore","parent_convo_id":"c1"}}""")
        socket.serve(journalLine(3, convo = "cLive"))
        assertEquals("cLive", probe.next())
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun liveBornChildWhoseFirstFrameIsNotMetaDoesNotAutoOpen() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(1))
        socket.serve(journalLine(1))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()

        val probe = FlowProbe(this, engine.newConversations())
        delay(50)
        socket.serve(journalLine(2, convo = "c1:sub:a1", type = "text"))
        socket.serve(journalLine(3, convo = "cLive"))
        assertEquals("cLive", probe.next())
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun liveBornTopLevelConvoMetaStillAutoOpens() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(1))
        socket.serve(journalLine(1))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()

        val probe = FlowProbe(this, engine.newConversations())
        delay(50)
        socket.serve("""{"kind":"journal","seq":2,"convo_id":"cTop","ts":2000,"sender":"agent:a","type":"convo_meta","payload":{"title":"new session"}}""")
        assertEquals("cTop", probe.next())
        probe.cancel()
        engine.endSync()
    }

    // MARK: Agent RPC

    private suspend fun runningEngine(): Pair<JournalSyncEngine, FakeWebSocketConnection> {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val engine = makeEngine(seededStore(), FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        return engine to socket
    }

    @Test
    fun agentRequestHappyPath() = runBlocking {
        val (engine, socket) = runningEngine()
        val reply = async { engine.agentRequest(9, "start", """{"workdir":"~/dev"}""") }
        waitUntil { sentAgentRequests(socket).isNotEmpty() }
        val request = sentAgentRequests(socket).first()
        assertEquals(9L, request.longOrNull("agent_device_id"))
        assertEquals("start", request.stringOrNull("method"))
        assertEquals("~/dev", request.objectOrNull("params")?.stringOrNull("workdir"))
        val rid = request.stringOrNull("request_id")!!
        socket.serve("""{"kind":"rpc","response":{"request_id":"$rid","agent_device_id":9,"ok":true,"result":{"convo_id":"c-new"}}}""")
        socket.serve("""{"kind":"rpc","response":{"request_id":"$rid","agent_device_id":9,"ok":true,"result":{"convo_id":"c-new"}}}""")
        val outcome = reply.await()
        assertTrue(outcome is RPCReply.Ok)
        assertEquals("c-new", (outcome as RPCReply.Ok).result.jsonObject.stringOrNull("convo_id"))
        engine.endSync()
    }

    @Test
    fun agentRequestCorrelatedErrorBecomesFailure() = runBlocking {
        val (engine, socket) = runningEngine()
        val reply = async { engine.agentRequest(9, "start", "{}") }
        waitUntil { sentAgentRequests(socket).isNotEmpty() }
        val rid = sentAgentRequests(socket).first().stringOrNull("request_id")!!
        socket.serve("""{"kind":"control","op":"error","code":"agent_unreachable","ref":"agent_request","request_id":"$rid"}""")
        assertEquals(RPCReply.Failure("agent_unreachable", null), reply.await())
        engine.endSync()
    }

    @Test
    fun agentRequestNotReadyResendsIdenticalFrame() = runBlocking {
        val (engine, socket) = runningEngine()
        val reply = async { engine.agentRequest(9, "recent_folders", "{}", notReadyBackoff = 10.milliseconds) }
        waitUntil { sentAgentRequests(socket).isNotEmpty() }
        val rid = sentAgentRequests(socket).first().stringOrNull("request_id")!!
        socket.serve("""{"kind":"control","op":"error","code":"not_ready","ref":"agent_request","request_id":"$rid"}""")
        waitUntil { sentAgentRequests(socket).size == 2 }
        val requests = sentAgentRequests(socket)
        assertEquals(2, requests.size)
        assertEquals(rid, requests[1].stringOrNull("request_id"))
        socket.serve("""{"kind":"rpc","response":{"request_id":"$rid","agent_device_id":9,"ok":true,"result":{"folders":[]}}}""")
        assertTrue(reply.await() is RPCReply.Ok)
        engine.endSync()
    }

    @Test
    fun agentRequestNotReadyGivesUpAfterMaxResends() = runBlocking {
        val (engine, socket) = runningEngine()
        val reply = async { engine.agentRequest(9, "recent_folders", "{}", notReadyBackoff = 5.milliseconds) }
        for (attempt in 1..3) {
            waitUntil { sentAgentRequests(socket).size == attempt }
            val rid = sentAgentRequests(socket).first().stringOrNull("request_id")!!
            socket.serve("""{"kind":"control","op":"error","code":"not_ready","ref":"agent_request","request_id":"$rid"}""")
        }
        assertEquals(RPCReply.Failure("not_ready", null), reply.await())
        assertEquals(3, sentAgentRequests(socket).size)
        engine.endSync()
    }

    @Test
    fun agentRequestTimesOut() = runBlocking {
        val (engine, socket) = runningEngine()
        try {
            engine.agentRequest(9, "start", "{}", timeout = 50.milliseconds)
            fail("expected timeout")
        } catch (e: RPCRequestError) {
            assertEquals(RPCRequestError.Timeout, e)
        }
        socket.isClosed // keep alive
        engine.endSync()
    }

    @Test
    fun agentRequestFailsWhenSocketDies() = runBlocking {
        val (engine, socket) = runningEngine()
        val reply = async { runCatching { engine.agentRequest(9, "start", "{}") } }
        waitUntil { sentAgentRequests(socket).isNotEmpty() }
        socket.closeFromServer()
        val outcome = reply.await()
        assertTrue(outcome.isFailure)
        assertEquals(RPCRequestError.Offline, outcome.exceptionOrNull())
        engine.endSync()
    }

    @Test
    fun agentRequestWithoutConnectionThrowsOffline() = runBlocking {
        val engine = makeEngine(seededStore(), FakeConnector(emptyList()))
        try {
            engine.agentRequest(9, "start", "{}")
            fail("expected offline")
        } catch (e: RPCRequestError) {
            assertEquals(RPCRequestError.Offline, e)
        }
    }

    @Test
    fun stateStreamTransitions() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        val probe = FlowProbe(this, engine.stateStream)
        assertEquals(SyncConnectionState.Connecting, probe.next())
        engine.beginSync()
        var sawRunning = false
        for (i in 0 until 4) {
            if (probe.next() == SyncConnectionState.Running) { sawRunning = true; break }
        }
        assertTrue("expected .running", sawRunning)
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun chaosResumeConvergence() = runBlocking {
        val journal = (1..200).map { journalLine(it.toLong()) }
        val connector = ChaosServerConnector(journal, headSeq = 200)
        val store = seededStore()
        val engine = makeEngine(store, connector, backoffBaseSeconds = 0.001)
        engine.beginSync()
        waitUntil(timeoutMs = 30_000) { store.cursor() >= 200 }
        assertEquals(200L, store.cursor())
        assertEquals((1L..200L).toList(), store.events("c1").map { it.seq })
        assertTrue(connector.connectCount > 3)
        engine.endSync()
    }

    @Test
    fun storeWriteFailureReconnectsRatherThanWedging() = runBlocking {
        val socket1 = FakeWebSocketConnection()
        socket1.serve(helloOK(3)); socket1.serve(journalLine(1)); socket1.serve(journalLine(2)); socket1.serve(journalLine(3))
        val socket2 = FakeWebSocketConnection()
        socket2.serve(helloOK(3)); socket2.serve(journalLine(2)); socket2.serve(journalLine(3))
        val store = seededStore()
        var hasFailedOnce = false
        store.failApplyForTesting = { seq ->
            if (seq == 2L && !hasFailedOnce) { hasFailedOnce = true; true } else false
        }
        val connector = FakeConnector(listOf(socket1, socket2))
        val engine = makeEngine(store, connector)
        engine.beginSync()
        waitUntil(timeoutMs = 5000) { store.cursor() >= 3 }
        assertEquals(3L, store.cursor())
        assertEquals(listOf(1L, 2L, 3L), store.events("c1").map { it.seq })
        assertTrue(connector.connectCount >= 2)
        engine.endSync()
    }

    @Test
    fun endSyncFailsReadyWaitersInsteadOfHanging() = runBlocking {
        val connector = FakeConnector(emptyList())
        connector.connectError = JournalConnectionError.SocketClosed
        val store = seededStore()
        val engine = makeEngine(store, connector, backoffBaseSeconds = 0.001)
        engine.beginSync()
        val waiter = async {
            try {
                engine.waitUntilReady(); "succeeded"
            } catch (e: JournalSyncError) {
                if (e == JournalSyncError.Offline) "offline" else "other"
            }
        }
        delay(20)
        engine.endSync()
        assertEquals("offline", withTimeout(5000) { waiter.await() })
    }

    @Test
    fun isRunningFalseAfterAuthRejected() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve("""{"kind":"control","op":"error","code":"auth"}""")
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        waitUntil { !engine.isRunning() }
        assertFalse(engine.isRunning())
        engine.endSync()
    }

    @Test
    fun waitUntilReadyOnNeverStartedEngineThrows() = runBlocking {
        val engine = makeEngine(seededStore(), FakeConnector(emptyList()))
        try {
            engine.waitUntilReady()
            fail("expected offline")
        } catch (e: JournalSyncError) {
            assertEquals(JournalSyncError.Offline, e)
        }
    }

    @Test
    fun waitUntilReadyAfterEndSyncThrowsInsteadOfHanging() = runBlocking {
        val connector = FakeConnector(emptyList())
        connector.connectError = JournalConnectionError.SocketClosed
        val store = seededStore()
        val engine = makeEngine(store, connector, backoffBaseSeconds = 0.001)
        engine.beginSync()
        delay(30)
        engine.endSync()
        val result = async {
            try {
                engine.waitUntilReady(); "succeeded"
            } catch (e: JournalSyncError) {
                if (e == JournalSyncError.Offline) "offline" else "other"
            }
        }
        assertEquals("offline", withTimeout(5000) { result.await() })
    }

    @Test
    fun snapshotRequiredWipesMirrorAndColdStarts() = runBlocking {
        val snapshot = FakeSnapshotSource()
        snapshot.response = SnapshotResponse(listOf(ConvoSummaryDTO("c9", "fresh", "running", 400, "s", 0)), 400)
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("c1", "", "running", 0, "", 0)), headSeq = 0)
        for (seq in 1L..5L) {
            store.applyJournal(JournalEvent(seq, "c1", java.time.Instant.now(), "agent:a", "text",
                parseJsonObjectOrNull("""{"body":"m$seq"}""")!!))
        }
        assertEquals(5L, store.cursor())

        val socket1 = FakeWebSocketConnection()
        socket1.serve(helloOK(500)); socket1.serve("""{"kind":"control","op":"snapshot_required"}""")
        val socket2 = FakeWebSocketConnection()
        socket2.serve(helloOK(400))
        val connector = FakeConnector(listOf(socket1, socket2))
        val engine = makeEngine(store, connector, snapshot, backoffBaseSeconds = 0.001)
        engine.beginSync()

        waitUntil(timeoutMs = 5000) { store.cursor() == 400L }
        assertEquals(400L, store.cursor())
        val convos = store.conversations()
        assertFalse(convos.any { it.id == "c1" })
        assertTrue(convos.any { it.id == "c9" })
        assertTrue(store.events("c1").isEmpty())
        val hello = parseJsonObjectOrNull(socket2.sent.first())!!
        assertEquals(400L, hello.longOrNull("cursor"))
        engine.endSync()
    }

    @Test
    fun snapshotRequiredForcesReconnectEvenIfSocketStaysOpen() = runBlocking {
        val snapshot = FakeSnapshotSource()
        snapshot.response = SnapshotResponse(listOf(ConvoSummaryDTO("c9", "fresh", "running", 400, "s", 0)), 400)
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("c1", "", "running", 0, "", 0)), headSeq = 0)
        for (seq in 1L..5L) {
            store.applyJournal(JournalEvent(seq, "c1", java.time.Instant.now(), "agent:a", "text",
                parseJsonObjectOrNull("""{"body":"m$seq"}""")!!))
        }

        val socket1 = FakeWebSocketConnection()
        socket1.serve(helloOK(500)); socket1.serve("""{"kind":"control","op":"snapshot_required"}""")
        // Deliberately not closed: engine must reconnect on its own.
        val socket2 = FakeWebSocketConnection()
        socket2.serve(helloOK(400))
        val connector = FakeConnector(listOf(socket1, socket2))
        val engine = makeEngine(store, connector, snapshot, backoffBaseSeconds = 0.001)
        engine.beginSync()

        waitUntil(timeoutMs = 5000) { connector.connectCount >= 2 }
        assertTrue(connector.connectCount >= 2)
        waitUntil(timeoutMs = 5000) { store.cursor() == 400L }
        assertEquals(400L, store.cursor())
        assertFalse(store.conversations().any { it.id == "c1" })
        assertTrue(store.conversations().any { it.id == "c9" })
        engine.endSync()
    }

    @Test
    fun sessionStatusCacheClearedOnSnapshotWipe() = runBlocking {
        val snapshot = FakeSnapshotSource()
        snapshot.response = SnapshotResponse(listOf(ConvoSummaryDTO("c1", "t", "running", 400, "s", 0)), 400)
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("c1", "", "running", 0, "", 0)), headSeq = 0)

        val socket1 = FakeWebSocketConnection()
        socket1.serve(helloOK(500))
        socket1.serve("""{"kind":"ephemeral","convo_id":"c1","status":{"context":{"tokens":100,"window":1000,"pct":10}}}""")
        socket1.serve("""{"kind":"control","op":"snapshot_required"}""")
        val socket2 = FakeWebSocketConnection()
        socket2.serve(helloOK(400))
        val connector = FakeConnector(listOf(socket1, socket2))
        val engine = makeEngine(store, connector, snapshot, backoffBaseSeconds = 0.001)
        engine.beginSync()
        waitUntil(timeoutMs = 5000) { store.cursor() == 400L }
        assertEquals(400L, store.cursor())

        val probe = FlowProbe(this, engine.sessionStatus("c1"))
        delay(50)
        socket2.serve("""{"kind":"ephemeral","convo_id":"c1","status":{"context":{"tokens":770,"window":1000,"pct":77}}}""")
        assertEquals(77, probe.next().context?.pct)
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun externalRefreshSummariesDiscardedAfterSnapshotRequiredWipe() = runBlocking {
        val snapshot = FakeSnapshotSource()
        snapshot.response = SnapshotResponse(listOf(ConvoSummaryDTO("c9", "fresh", "running", 400, "s", 0)), 400)
        snapshot.gatedRequestIndex = 1
        snapshot.gatedResponse = SnapshotResponse(listOf(ConvoSummaryDTO("c1", "stale", "running", 5, "s", 0)), 5)
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("c1", "", "running", 0, "", 0)), headSeq = 0)
        for (seq in 1L..5L) {
            store.applyJournal(JournalEvent(seq, "c1", java.time.Instant.now(), "agent:a", "text",
                parseJsonObjectOrNull("""{"body":"m$seq"}""")!!))
        }
        assertEquals(5L, store.cursor())

        val socket1 = FakeWebSocketConnection()
        socket1.serve(helloOK(500)); socket1.serve("""{"kind":"control","op":"snapshot_required"}""")
        val socket2 = FakeWebSocketConnection()
        socket2.serve(helloOK(400))
        val connector = FakeConnector(listOf(socket1, socket2))
        val engine = makeEngine(store, connector, snapshot, backoffBaseSeconds = 0.001)

        val refresh = async { engine.refreshSummaries() }
        withTimeout(5000) { snapshot.gateReached.await() }

        engine.beginSync()
        waitUntil(timeoutMs = 5000) { store.cursor() == 400L }
        assertEquals(400L, store.cursor())

        snapshot.releaseGate()
        refresh.await()

        assertFalse(store.conversations().any { it.id == "c1" })
        assertTrue(store.conversations().any { it.id == "c9" })
        assertEquals(400L, store.cursor())
        engine.endSync()
    }
}
