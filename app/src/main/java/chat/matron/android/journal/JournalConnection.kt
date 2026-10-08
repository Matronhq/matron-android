package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withTimeout

/// One established, authenticated socket. Create via [establish], consume
/// [frames] until it throws, then let the caller reconnect — a resume is
/// indistinguishable from a continuation server-side.
///
/// [helloSettings] is the `settings` object the `hello_ok` carried (`null`
/// from a journal that sends none) — the engine fans it out so a screen
/// showing a setting catches a change made while this device was offline.
class JournalConnection internal constructor(
    private val socket: WebSocketConnection,
    val helloSettings: UserSettings? = null,
) {

    /// Cold flow of decoded server frames. Throws when the socket dies, and
    /// closes the socket on any completion (normal, error, or cancellation).
    fun frames(): Flow<ServerFrame> = flow {
        while (true) {
            val text = socket.receiveText()
            val frame = ServerFrame.decode(text)
            if (frame == null) {
                // The cursor still acks past this frame once its containing
                // batch applies — leave a trace of what was silently dropped.
                MatronDebug.breadcrumb("JournalConnection: undecodable frame, dropped: ${text.take(200)}")
            } else {
                emit(frame)
            }
        }
    }.onCompletion { socket.close() }

    suspend fun send(op: ClientOp) {
        socket.sendText(op.encoded())
    }

    suspend fun ping() {
        socket.ping()
    }

    fun close() {
        socket.close()
    }

    companion object {
        suspend fun establish(
            connector: WebSocketConnecting,
            wsUrl: String,
            token: String,
            cursor: Long,
            handshakeTimeout: Duration = 15.seconds,
        ): Pair<JournalConnection, Long> {
            val (connection, hello) = establishWithHello(connector, wsUrl, token, cursor, handshakeTimeout)
            return connection to hello.headSeq
        }

        /// [establish], keeping the whole `hello_ok`: on a journal that has
        /// them it carries the user's pins and Coordinator.
        suspend fun establishWithHello(
            connector: WebSocketConnecting,
            wsUrl: String,
            token: String,
            cursor: Long,
            handshakeTimeout: Duration = 15.seconds,
        ): Pair<JournalConnection, ServerFrame.HelloOK> {
            val socket = connector.connect(wsUrl)
            // Unlike URLSession, a channel-backed receive IS cancellable, so a
            // bounded handshake is just `withTimeout` around the receive loop.
            try {
                return withTimeout(handshakeTimeout) {
                    socket.sendText(ClientOp.Hello(token, cursor).encoded())
                    while (true) {
                        val text = socket.receiveText()
                        when (val frame = ServerFrame.decode(text)) {
                            null -> continue
                            is ServerFrame.HelloOK -> return@withTimeout JournalConnection(socket, frame.settings) to frame
                            is ServerFrame.Error ->
                                throw if (frame.code == "auth") JournalConnectionError.AuthRejected
                                else JournalConnectionError.BadHandshake
                            else -> throw JournalConnectionError.BadHandshake
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    error("unreachable")
                }
            } catch (e: TimeoutCancellationException) {
                socket.close()
                throw JournalConnectionError.HandshakeTimeout
            } catch (e: Throwable) {
                socket.close()
                throw e
            }
        }
    }
}
