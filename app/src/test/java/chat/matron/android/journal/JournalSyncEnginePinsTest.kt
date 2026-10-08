package chat.matron.android.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// The engine's two journal-held settings feeds: pins (`hello_ok`, live
/// `pins` frames, the snapshot) and the Coordinator (`hello_ok` plus live
/// `coordinator` events, with a reconnect's replayed events dropped).
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalSyncEnginePinsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val pinJSON = """{"convo_id":"c1","label":"Inbox triage","emoji":"📮","position":0,"device_id":7}"""

    private fun coordinatorLine(seq: Long, convo: String, role: String) =
        """{"kind":"journal","seq":$seq,"convo_id":"$convo","ts":${seq * 1000},"sender":"journal","type":"coordinator","payload":{"role":"$role"}}"""

    private suspend fun seededStore(): JournalStore {
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:alice")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("c1", "", "running", 0, "", 0)), headSeq = 0)
        return store
    }

    private fun makeEngine(store: JournalStore, socket: FakeWebSocketConnection, snapshot: SnapshotSource = FakeSnapshotSource()) =
        JournalSyncEngine(
            api = snapshot, store = store, connector = FakeConnector(listOf(socket)), token = "t",
            ownSender = "user:alice", search = null, backoffBaseSeconds = 0.01,
        )

    private class FlowProbe<T>(scope: CoroutineScope, flow: Flow<T>) {
        private val channel = Channel<T>(Channel.UNLIMITED)
        private val job: Job = scope.launch { flow.collect { channel.send(it) } }
        suspend fun next(timeoutMs: Long = 2000): T = withTimeout(timeoutMs) { channel.receive() }
        suspend fun none(timeoutMs: Long = 200): T? = withTimeoutOrNull(timeoutMs) { channel.receive() }
        fun cancel() = job.cancel()
    }

    @Test
    fun helloPinsThenALiveFrameReachTheFeedAndReplayToALateSubscriber() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve("""{"kind":"control","op":"hello_ok","seq":0,"pins":[$pinJSON]}""")
        val engine = makeEngine(seededStore(), socket)
        val probe = FlowProbe(this, engine.pinsUpdates())
        engine.beginSync()
        assertEquals(listOf("c1"), probe.next()!!.map { it.convoID })
        socket.serve("""{"kind":"pins","pins":[]}""")
        assertEquals(emptyList<ConvoPin>(), probe.next())

        val late = FlowProbe(this, engine.pinsUpdates())
        assertEquals("a new subscriber gets the last list", emptyList<ConvoPin>(), late.next())
        probe.cancel(); late.cancel()
        engine.endSync()
    }

    @Test
    fun aHelloWithoutPinsMeansAnOlderJournal() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve("""{"kind":"control","op":"hello_ok","seq":0}""")
        val engine = makeEngine(seededStore(), socket)
        val probe = FlowProbe(this, engine.pinsUpdates())
        engine.beginSync()
        assertNull(probe.next())
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun aSnapshotWithoutPinsDoesNotUndoTheHello() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve("""{"kind":"control","op":"hello_ok","seq":0,"pins":[$pinJSON]}""")
        val engine = makeEngine(seededStore(), socket) // the fake snapshot carries no pins key
        val probe = FlowProbe(this, engine.pinsUpdates())
        engine.beginSync()
        engine.waitUntilReady()
        assertEquals(listOf("c1"), probe.next()!!.map { it.convoID })
        engine.refreshSummaries()
        assertNull(probe.none())
        probe.cancel()
        engine.endSync()
    }

    @Test
    fun theCoordinatorFollowsTheHelloAndLiveEventsButNotTheReplay() = runBlocking {
        val socket = FakeWebSocketConnection()
        // Head 2: the two events below it are the reconnect backlog, already
        // reflected in the hello's answer.
        socket.serve("""{"kind":"control","op":"hello_ok","seq":2,"coordinator_convo_id":"c1"}""")
        socket.serve(coordinatorLine(1, "c0", "released"))
        socket.serve(coordinatorLine(2, "c1", "assigned"))
        val engine = makeEngine(seededStore(), socket)
        val probe = FlowProbe(this, engine.coordinatorUpdates())
        engine.beginSync()
        assertEquals(CoordinatorUpdate.Snapshot("c1"), probe.next())
        engine.waitUntilReady()
        assertNull("replayed events are old news", probe.none())

        socket.serve(coordinatorLine(3, "c1", "released"))
        assertEquals(CoordinatorUpdate.Released("c1"), probe.next())
        socket.serve(coordinatorLine(4, "c5", "assigned"))
        assertEquals(CoordinatorUpdate.Assigned("c5"), probe.next())

        val late = FlowProbe(this, engine.coordinatorUpdates())
        assertEquals(CoordinatorUpdate.Snapshot("c5"), late.next())
        probe.cancel(); late.cancel()
        engine.endSync()
    }

    @Test
    fun aJournalWithoutTheFieldSaysNothingAboutTheCoordinator() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve("""{"kind":"control","op":"hello_ok","seq":0}""")
        val engine = makeEngine(seededStore(), socket)
        val probe = FlowProbe(this, engine.coordinatorUpdates())
        engine.beginSync()
        engine.waitUntilReady()
        assertNull(probe.none())
        probe.cancel()
        engine.endSync()
    }
}
