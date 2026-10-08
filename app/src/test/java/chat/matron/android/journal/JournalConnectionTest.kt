package chat.matron.android.journal

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JournalConnectionTest {
    private val wsUrl = "wss://x/ws"

    @Test
    fun establishSendsHelloAndReturnsHead() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve("""{"kind":"control","op":"hello_ok","seq":7}""")
        val (connection, head) = JournalConnection.establish(
            FakeConnector(listOf(socket)), wsUrl, "tok", 3)
        assertEquals(7L, head)
        val hello = socket.lastSentObject!!
        assertEquals("hello", hello.stringOrNull("op"))
        assertEquals("tok", hello.stringOrNull("token"))
        assertEquals(3L, hello.longOrNull("cursor"))
        connection.close()
    }

    @Test
    fun establishThrowsOnAuthError() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve("""{"kind":"control","op":"error","code":"auth"}""")
        try {
            JournalConnection.establish(FakeConnector(listOf(socket)), wsUrl, "bad", 0)
            fail("expected authRejected")
        } catch (e: JournalConnectionError) {
            assertEquals(JournalConnectionError.AuthRejected, e)
        }
    }

    @Test
    fun framesStreamYieldsAndThrowsOnClose() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve("""{"kind":"control","op":"hello_ok","seq":0}""")
        val (connection, _) = JournalConnection.establish(
            FakeConnector(listOf(socket)), wsUrl, "t", 0)
        socket.serve("""{"kind":"journal","seq":1,"convo_id":"c1","ts":1000,"sender":"agent:a","type":"text","payload":{"body":"x"}}""")
        socket.serve("garbage that is skipped")
        socket.serve("""{"kind":"journal","seq":2,"convo_id":"c1","ts":2000,"sender":"agent:a","type":"text","payload":{"body":"y"}}""")

        val received = mutableListOf<Long>()
        try {
            connection.frames().collect { frame ->
                if (frame is ServerFrame.Journal) {
                    received.add(frame.event.seq)
                    if (frame.event.seq == 2L) socket.closeFromServer()
                }
            }
            fail("stream must throw when the socket dies")
        } catch (e: JournalConnectionError.SocketClosed) {
            // expected — FakeWebSocketConnection.closeFromServer() fails the
            // pending receiveText() with this specific error.
        }
        assertEquals(listOf(1L, 2L), received)
    }

    @Test
    fun sendEncodesOp() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve("""{"kind":"control","op":"hello_ok","seq":0}""")
        val (connection, _) = JournalConnection.establish(
            FakeConnector(listOf(socket)), wsUrl, "t", 0)
        connection.send(ClientOp.Ack(5))
        assertEquals("ack", socket.lastSentObject?.stringOrNull("op"))
    }

    @Test
    fun establishTimesOutWhenServerSilent() = runBlocking {
        val socket = FakeWebSocketConnection() // never serves hello_ok
        try {
            JournalConnection.establish(
                FakeConnector(listOf(socket)), wsUrl, "t", 0, handshakeTimeout = 100.milliseconds)
            fail("expected handshakeTimeout")
        } catch (e: JournalConnectionError) {
            assertEquals(JournalConnectionError.HandshakeTimeout, e)
        }
        assertTrue(socket.isClosed)
    }

    @Test
    fun framesTerminationClosesSocket() = runBlocking {
        val socket = FakeWebSocketConnection()
        socket.serve("""{"kind":"control","op":"hello_ok","seq":0}""")
        val (connection, _) = JournalConnection.establish(
            FakeConnector(listOf(socket)), wsUrl, "t", 0)
        val job = launch {
            connection.frames().collect { }
        }
        delay(50) // let the pump suspend in receiveText
        job.cancel() // terminates the stream -> onCompletion must close the socket
        repeat(100) {
            if (socket.isClosed) return@repeat
            delay(10)
        }
        assertTrue(socket.isClosed)
    }
}
