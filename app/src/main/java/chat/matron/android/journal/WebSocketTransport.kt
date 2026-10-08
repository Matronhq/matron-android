package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/// Connection-establishment / socket-death errors. Modeled as exceptions so
/// they propagate through suspend calls the way the Swift `throw`s do.
sealed class JournalConnectionError(message: String? = null) : Exception(message) {
    data object AuthRejected : JournalConnectionError()
    data object BadHandshake : JournalConnectionError()
    data object SocketClosed : JournalConnectionError()
    data object HandshakeTimeout : JournalConnectionError()
}

/// Opens sockets. The OkHttp implementation is production; tests supply a fake.
interface WebSocketConnecting {
    suspend fun connect(url: String): WebSocketConnection
}

/// One WebSocket, exposing a pull-based receive over OkHttp's push listener.
interface WebSocketConnection {
    suspend fun sendText(text: String)
    suspend fun receiveText(): String
    /// One liveness round-trip; throws if the peer is gone.
    suspend fun ping()
    fun close()
}

/// OkHttp-backed [WebSocketConnecting]. `pingInterval` on the client drives
/// protocol-level keepalive: OkHttp sends a ping every interval and fails the
/// socket (receive path throws → engine reconnects) when a pong doesn't come
/// back. Without it a half-open connection could sit "connected" forever while
/// receiving nothing — the exact wedge class this protocol exists to kill —
/// so the default client enables it; callers supplying their own client must
/// do the same.
class OkHttpWebSocketConnector(
    private val client: OkHttpClient = defaultClient(),
    private val tokenHeader: String? = null,
) : WebSocketConnecting {

    companion object {
        /// 20s matches the server's own ping cadence (protocol spec §6).
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
    override suspend fun connect(url: String): WebSocketConnection {
        val builder = Request.Builder().url(url)
        tokenHeader?.let { builder.header("Authorization", "Bearer $it") }
        val connection = OkHttpWebSocketConnection()
        val ws = client.newWebSocket(builder.build(), connection.listener)
        connection.attach(ws)
        return connection
    }
}

/// Bridges OkHttp's callback stream to a suspend `receiveText()` via an
/// unbounded channel; socket death closes the channel with the failure cause so
/// `receiveText()` throws (mirroring URLSessionWebSocketTask.receive()).
internal class OkHttpWebSocketConnection : WebSocketConnection {
    private val incoming = Channel<String>(Channel.UNLIMITED)

    @Volatile
    private var webSocket: WebSocket? = null

    val listener: WebSocketListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            incoming.trySend(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            incoming.trySend(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            incoming.close(JournalConnectionError.SocketClosed)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            incoming.close(JournalConnectionError.SocketClosed)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            logRejectedUpgrade(response)
            incoming.close(t)
        }
    }

    fun attach(ws: WebSocket) {
        webSocket = ws
    }

    override suspend fun sendText(text: String) {
        val ws = webSocket ?: throw JournalConnectionError.SocketClosed
        if (!ws.send(text)) throw JournalConnectionError.SocketClosed
    }

    override suspend fun receiveText(): String = try {
        incoming.receive()
    } catch (e: ClosedReceiveChannelException) {
        throw JournalConnectionError.SocketClosed
    }

    /// OkHttp manages ping/pong at the protocol level (client `pingInterval`),
    /// so an app-level round-trip isn't exposed. Liveness surfaces as a socket
    /// failure on the receive path instead.
    override suspend fun ping() {}

    override fun close() {
        webSocket?.close(1000, null)
        incoming.close(JournalConnectionError.SocketClosed)
    }

    /// A refused HTTP upgrade (proxy 4xx/5xx, wrong endpoint, captive portal)
    /// surfaces the status only on the failure response. Log it un-gated.
    private fun logRejectedUpgrade(response: Response?) {
        val http = response ?: return
        MatronDebug.breadcrumb("ws upgrade rejected: HTTP ${http.code} from ${http.request.url.host}")
    }
}
