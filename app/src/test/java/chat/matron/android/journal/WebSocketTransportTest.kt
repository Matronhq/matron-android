package chat.matron.android.journal

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/// Drives [OkHttpWebSocketConnection]'s [okhttp3.WebSocketListener] directly
/// with a fake [WebSocket] — no real network, no MockWebServer. This is the
/// only coverage of the production transport OkHttp adapter; the rest of the
/// suite exercises the engine against [FakeWebSocketConnection] instead.
class WebSocketTransportTest {
    /// Minimal fake: records sent text, lets a test force `send()` to report
    /// failure the way a dead/backpressured socket does in OkHttp.
    private class FakeWebSocket(private val sendResult: Boolean = true) : WebSocket {
        val sent = mutableListOf<String>()
        var closeCode: Int? = null
        override fun request(): Request = Request.Builder().url("wss://x/ws").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean {
            sent.add(text)
            return sendResult
        }
        override fun send(bytes: okio.ByteString): Boolean = sendResult
        override fun close(code: Int, reason: String?): Boolean {
            closeCode = code
            return true
        }
        override fun cancel() {}
    }

    private fun attached(sendResult: Boolean = true): Pair<OkHttpWebSocketConnection, FakeWebSocket> {
        val connection = OkHttpWebSocketConnection()
        val ws = FakeWebSocket(sendResult)
        connection.attach(ws)
        return connection to ws
    }

    @Test
    fun onClosingClosesTheChannelSoReceiveThrowsSocketClosed() = runBlocking {
        val (connection, ws) = attached()
        connection.listener.onClosing(ws, 1000, "bye")
        try {
            connection.receiveText()
            fail("expected SocketClosed")
        } catch (e: JournalConnectionError.SocketClosed) {
            // expected
        }
    }

    @Test
    fun onClosedClosesTheChannelSoReceiveThrowsSocketClosed() = runBlocking {
        val (connection, ws) = attached()
        connection.listener.onClosed(ws, 1000, "bye")
        try {
            connection.receiveText()
            fail("expected SocketClosed")
        } catch (e: JournalConnectionError.SocketClosed) {
            // expected
        }
    }

    @Test
    fun onFailureClosesTheChannelWithTheGivenCause() = runBlocking {
        // Pinned behavior: `onFailure` forwards whatever throwable OkHttp
        // reports as the channel's close cause verbatim (no wrapping into
        // SocketClosed) — receiveText() rethrows exactly that cause, so a
        // caller inspecting the exception sees the real failure reason.
        val (connection, ws) = attached()
        val cause = IOException("connection reset")
        connection.listener.onFailure(ws, cause, null)
        try {
            connection.receiveText()
            fail("expected the onFailure cause to propagate")
        } catch (e: IOException) {
            assertEquals("connection reset", e.message)
        }
    }

    @Test
    fun onMessageTextIsDeliveredToReceiveText() = runBlocking {
        val (connection, ws) = attached()
        connection.listener.onMessage(ws, """{"kind":"control"}""")
        assertEquals("""{"kind":"control"}""", connection.receiveText())
    }

    @Test
    fun binaryFramesDecodeAsUTF8() = runBlocking {
        val (connection, ws) = attached()
        val text = """{"kind":"journal","seq":1,"body":"héllo"}"""
        connection.listener.onMessage(ws, text.encodeUtf8())
        assertEquals(text, connection.receiveText())
    }

    @Test
    fun sendTextThrowsWhenWsSendReturnsFalse() = runBlocking {
        val (connection, ws) = attached(sendResult = false)
        try {
            connection.sendText("hello")
            fail("expected SocketClosed")
        } catch (e: JournalConnectionError.SocketClosed) {
            // expected
        }
        assertTrue("ws.send() must still have been attempted", ws.sent.contains("hello"))
    }

    @Test
    fun sendTextSucceedsWhenWsSendReturnsTrue() = runBlocking {
        val (connection, ws) = attached(sendResult = true)
        connection.sendText("hello")
        assertEquals(listOf("hello"), ws.sent)
    }

    @Test
    fun sendTextThrowsSocketClosedWhenNeverAttached() = runBlocking {
        val connection = OkHttpWebSocketConnection()
        try {
            connection.sendText("hello")
            fail("expected SocketClosed")
        } catch (e: JournalConnectionError.SocketClosed) {
            // expected
        }
    }

    @Test
    fun closeClosesTheUnderlyingSocketAndTheChannel() = runBlocking {
        val (connection, ws) = attached()
        connection.close()
        assertEquals(1000, ws.closeCode)
        try {
            connection.receiveText()
            fail("expected SocketClosed")
        } catch (e: JournalConnectionError.SocketClosed) {
            // expected
        }
    }
}
