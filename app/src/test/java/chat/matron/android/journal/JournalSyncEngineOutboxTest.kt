package chat.matron.android.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.journal.db.OutboxEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Offline outbox behaviour, ported from matron-apple's
/// `JournalSyncEngineOutboxTests`: `sendMessage` queues instead of throwing
/// when offline, queued rows flush FIFO with their original `local_id` on
/// (re)connect (server-side idem dedup makes resends safe), and delivery is
/// confirmed — and the row deleted — by the own-text journal frame.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalSyncEngineOutboxTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun helloOK(head: Long) = """{"kind":"control","op":"hello_ok","seq":$head}"""

    private fun ownTextLine(seq: Long, convo: String = "c1", body: String) =
        """{"kind":"journal","seq":$seq,"convo_id":"$convo","ts":${seq * 1000},"sender":"user:dan","type":"text","payload":{"body":"$body"}}"""

    private suspend fun seededStore(): JournalStore {
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("c1", "", "running", 0, "", 0)), headSeq = 0)
        return store
    }

    private fun makeEngine(store: JournalStore, connector: WebSocketConnecting) = JournalSyncEngine(
        api = FakeSnapshotSource(), store = store, connector = connector, token = "t",
        ownSender = "user:dan", search = null, backoffBaseSeconds = 0.01,
    )

    /// Decoded `op: send` frames a fake socket captured, in order.
    private fun sentSendOps(socket: FakeWebSocketConnection) =
        socket.sent.mapNotNull { parseJsonObjectOrNull(it) }.filter { it.stringOrNull("op") == "send" }

    private suspend fun waitUntil(timeoutMs: Long = 3000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
    }

    @Test
    fun sendMessageOfflineQueuesWithoutThrowing() = runBlocking {
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(emptyList()))
        // Engine never started: no connection at all.
        engine.sendMessage("c1", "hello", "L1")
        val pending = store.outboxPending()
        assertEquals(listOf("L1"), pending.map { it.localID })
        assertEquals("no connection — never attempted", 0, pending.first().attempts)
    }

    @Test
    fun sendMessageOnlineSendsWithLocalIDAndKeepsRowUntilConfirmed() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        engine.sendMessage("c1", "hello", "L1")
        waitUntil { sentSendOps(socket).isNotEmpty() }
        val sends = sentSendOps(socket)
        assertEquals(1, sends.size)
        assertEquals("L1", sends.first().stringOrNull("local_id"))
        // Row survives the socket write — only the journal frame confirms.
        assertEquals(listOf("L1"), store.outboxPending().map { it.localID })
        assertEquals(1, store.outboxPending().first().attempts)
        engine.endSync()
    }

    @Test
    fun connectFlushesPreexistingQueueFIFO() = runBlocking {
        val store = seededStore()
        store.outboxInsert("A", "c1", "first", now = 1)
        store.outboxInsert("B", "c1", "second", now = 2)
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        waitUntil { sentSendOps(socket).size >= 2 }
        assertEquals(listOf("A", "B"), sentSendOps(socket).map { it.stringOrNull("local_id") })
        engine.endSync()
    }

    @Test
    fun ownTextFrameDeletesConfirmedRow() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        engine.sendMessage("c1", "hello", "L1")
        waitUntil { sentSendOps(socket).isNotEmpty() }
        // Server journals the send and broadcasts it back.
        socket.serve(ownTextLine(1, body = "hello"))
        waitUntil { store.outboxPending().isEmpty() }
        assertTrue("journal frame is the delivery confirmation", store.outboxPending().isEmpty())
        engine.endSync()
    }

    @Test
    fun otherSendersFrameDoesNotDeleteQueuedRow() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        engine.sendMessage("c1", "hello", "L1")
        waitUntil { sentSendOps(socket).isNotEmpty() }
        socket.serve(
            """{"kind":"journal","seq":1,"convo_id":"c1","ts":1000,"sender":"agent:a","type":"text","payload":{"body":"hello"}}"""
        )
        // Give the frame time to apply, then confirm the row survived.
        waitUntil { store.events("c1").size == 1 }
        assertEquals(listOf("L1"), store.outboxPending().map { it.localID })
        engine.endSync()
    }

    @Test
    fun socketDeathMidQueueResendsSameLocalIDOnReconnect() = runBlocking {
        val store = seededStore()
        store.outboxInsert("A", "c1", "first")
        val first = FakeWebSocketConnection()
        first.serve(helloOK(0))
        val second = FakeWebSocketConnection()
        second.serve(helloOK(0))
        val engine = makeEngine(store, FakeConnector(listOf(first, second)))
        engine.beginSync()
        engine.waitUntilReady()
        waitUntil { sentSendOps(first).isNotEmpty() }
        assertEquals("A", sentSendOps(first).first().stringOrNull("local_id"))
        // No journal confirmation arrives; the socket dies.
        first.closeFromServer()
        waitUntil { sentSendOps(second).isNotEmpty() }
        // Reconnect resends the unconfirmed row with the SAME local_id — the
        // server's idem key dedups if the first copy actually landed.
        assertEquals("A", sentSendOps(second).first().stringOrNull("local_id"))
        engine.endSync()
    }

    @Test
    fun retryOutboxItemRequeuesFailedRowAndFlushes() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        store.outboxInsert("F", "c1", "stuck")
        store.outboxMarkFailed("F", "rejected")
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        // Failed rows are excluded from the automatic connect flush.
        delay(50)
        assertTrue(sentSendOps(socket).isEmpty())
        engine.retryOutboxItem("F")
        waitUntil { sentSendOps(socket).isNotEmpty() }
        assertEquals("F", sentSendOps(socket).first().stringOrNull("local_id"))
        engine.endSync()
    }

    @Test
    fun serverRejectionMarksOldestUnconfirmedSendFailed() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        engine.sendMessage("c1", "bad one", "R1")
        waitUntil { sentSendOps(socket).isNotEmpty() }
        // The server rejects the op — validation errors can never succeed on
        // retry, so the row must surface as failed instead of silently
        // re-flushing on every reconnect forever.
        socket.serve("""{"kind":"control","op":"error","code":"bad_request","ref":"send","detail":"nope"}""")
        waitUntil { store.outboxPending().isEmpty() }
        val rows = store.outboxRows("c1")
        assertEquals(listOf(OutboxEntity.STATE_FAILED), rows.map { it.state })
        assertEquals("nope", rows.first().lastError)
        engine.endSync()
    }

    @Test
    fun mediaRejectionDoesNotFailQueuedTextRow() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        // A media send goes over the wire as `op:"send"` too — write it FIRST,
        // then queue a text behind it, so the rejection FIFO is [M1, T1].
        engine.sendOp(
            ClientOp.SendMedia(
                convoID = "c1", type = MediaKind.IMAGE, blobRef = "b1", name = "pic.png",
                contentType = "image/png", size = 3, caption = null, localID = "M1",
            )
        )
        engine.sendMessage("c1", "hello", "T1")
        waitUntil { sentSendOps(socket).size >= 2 }
        assertEquals(listOf("M1", "T1"), sentSendOps(socket).map { it.stringOrNull("local_id") })
        // The server rejects the media op. The rejection must be consumed by
        // the media FIFO slot, not misattributed to the unconfirmed text row.
        socket.serve("""{"kind":"control","op":"error","code":"too_large","ref":"send","detail":"blob too big"}""")
        // A trailing journal frame proves the error was processed (the frame
        // loop is sequential) before we assert nothing was marked failed.
        socket.serve("""{"kind":"journal","seq":1,"convo_id":"c1","ts":1000,"sender":"user:bob","type":"text","payload":{"body":"hi"}}""")
        waitUntil { store.cursor() >= 1 }
        assertEquals(listOf("T1"), store.outboxPending().map { it.localID })
        assertEquals(listOf(OutboxEntity.STATE_QUEUED), store.outboxRows("c1").map { it.state })
        // A second rejection now belongs to the text send — FIFO attribution
        // still works past the consumed media slot.
        socket.serve("""{"kind":"control","op":"error","code":"bad_request","ref":"send","detail":"nope"}""")
        waitUntil { store.outboxPending().isEmpty() }
        assertEquals(listOf(OutboxEntity.STATE_FAILED), store.outboxRows("c1").map { it.state })
        engine.endSync()
    }

    @Test
    fun deliveredMediaSlotDoesNotSwallowTextRejection() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        engine.sendOp(
            ClientOp.SendMedia(
                convoID = "c1", type = MediaKind.IMAGE, blobRef = "b1", name = "pic.png",
                contentType = "image/png", size = 3, caption = null, localID = "M1",
            )
        )
        engine.sendMessage("c1", "hello", "T1")
        waitUntil { sentSendOps(socket).size >= 2 }
        // The server journals the media send — delivery confirmed retires its
        // rejection-FIFO slot…
        socket.serve("""{"kind":"journal","seq":1,"convo_id":"c1","ts":1000,"sender":"user:dan","type":"image","payload":{"blob_ref":"b1","name":"pic.png","content_type":"image/png","size":3}}""")
        waitUntil { store.cursor() >= 1 }
        // …so a rejection arriving after it must fail the text row, not be
        // swallowed by the stale media slot.
        socket.serve("""{"kind":"control","op":"error","code":"bad_request","ref":"send","detail":"nope"}""")
        waitUntil { store.outboxPending().isEmpty() }
        assertEquals(listOf(OutboxEntity.STATE_FAILED), store.outboxRows("c1").map { it.state })
        assertEquals("nope", store.outboxRows("c1").first().lastError)
        engine.endSync()
    }

    @Test
    fun duplicateRejectionOfRetriedRowDoesNotFailLaterSend() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve(helloOK(0))
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(listOf(socket)))
        engine.beginSync()
        engine.waitUntilReady()
        // R1 goes out, the user impatient-retries it on the same connection
        // (two writes in flight, two FIFO slots), then T2 goes out.
        engine.sendMessage("c1", "dup", "R1")
        waitUntil { sentSendOps(socket).size >= 1 }
        engine.retryOutboxItem("R1")
        waitUntil { sentSendOps(socket).size >= 2 }
        engine.sendMessage("c1", "second", "T2")
        waitUntil { sentSendOps(socket).size >= 3 }
        assertEquals(listOf("R1", "R1", "T2"), sentSendOps(socket).map { it.stringOrNull("local_id") })
        // The server rejects both identical writes of R1. The first flips R1
        // to failed; the second must be absorbed by R1's duplicate slot — not
        // fall through and fail the still-in-flight T2.
        socket.serve("""{"kind":"control","op":"error","code":"bad_request","ref":"send","detail":"nope"}""")
        socket.serve("""{"kind":"control","op":"error","code":"bad_request","ref":"send","detail":"nope"}""")
        // A trailing journal frame proves both errors were processed.
        socket.serve("""{"kind":"journal","seq":1,"convo_id":"c1","ts":1000,"sender":"user:bob","type":"text","payload":{"body":"hi"}}""")
        waitUntil { store.cursor() >= 1 }
        val states = store.outboxRows("c1").associate { it.localID to it.state }
        assertEquals(OutboxEntity.STATE_FAILED, states["R1"])
        assertEquals(OutboxEntity.STATE_QUEUED, states["T2"])
        engine.endSync()
    }

    @Test
    fun discardOutboxItemDeletesRow() = runBlocking {
        val store = seededStore()
        val engine = makeEngine(store, FakeConnector(emptyList()))
        engine.sendMessage("c1", "oops", "D1")
        engine.discardOutboxItem("D1")
        assertTrue(store.outboxRows("c1").isEmpty())
    }
}
