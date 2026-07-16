package chat.matron.android.journal

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/// Scriptable fake socket. Push server frames with [serve]; closing finishes the
/// incoming stream so [receiveText] throws `SocketClosed`.
class FakeWebSocketConnection : WebSocketConnection {
    private val lock = Any()
    private val incoming = ArrayDeque<String>()
    private val waiters = ArrayDeque<Continuation<String>>()
    private var closed = false
    private val _sent = mutableListOf<String>()
    val sent: List<String> get() = synchronized(lock) { _sent.toList() }
    var pingError: Throwable? = null

    fun serve(text: String) {
        val waiter: Continuation<String>?
        synchronized(lock) {
            waiter = waiters.removeFirstOrNull()
            if (waiter == null) incoming.addLast(text)
        }
        waiter?.resume(text)
    }

    fun closeFromServer() {
        val pending: List<Continuation<String>>
        synchronized(lock) {
            closed = true
            pending = waiters.toList()
            waiters.clear()
        }
        pending.forEach { it.resumeWithException(JournalConnectionError.SocketClosed) }
    }

    override suspend fun sendText(text: String) {
        synchronized(lock) {
            if (closed) throw JournalConnectionError.SocketClosed
            _sent.add(text)
        }
    }

    override suspend fun receiveText(): String {
        synchronized(lock) {
            incoming.removeFirstOrNull()?.let { return it }
            if (closed) throw JournalConnectionError.SocketClosed
        }
        return suspendCancellableCoroutine { continuation ->
            synchronized(lock) {
                if (closed) {
                    continuation.resumeWithException(JournalConnectionError.SocketClosed)
                    return@suspendCancellableCoroutine
                }
                incoming.removeFirstOrNull()?.let {
                    continuation.resume(it)
                    return@suspendCancellableCoroutine
                }
                waiters.addLast(continuation)
            }
            continuation.invokeOnCancellation {
                synchronized(lock) { waiters.remove(continuation) }
            }
        }
    }

    override suspend fun ping() {
        pingError?.let { throw it }
    }

    override fun close() = closeFromServer()

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /// Convenience: last sent frame decoded as a JSON object.
    val lastSentObject: JsonObject? get() = sent.lastOrNull()?.let { parseJsonObjectOrNull(it) }
}

/// Hands out pre-built fake connections in order; records connect calls.
class FakeConnector(connections: List<FakeWebSocketConnection>) : WebSocketConnecting {
    private val lock = Any()
    private val queue = ArrayDeque(connections)
    var connectCount = 0
        private set
    var connectError: Throwable? = null

    override suspend fun connect(url: String): WebSocketConnection = synchronized(lock) {
        connectCount++
        connectError?.let { throw it }
        queue.removeFirstOrNull() ?: throw JournalConnectionError.SocketClosed
    }
}
