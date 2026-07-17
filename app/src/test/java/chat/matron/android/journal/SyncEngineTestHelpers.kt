package chat.matron.android.journal

import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred

/// Scriptable [SnapshotSource] for engine tests. Replaces the Apple suite's
/// global `URLProtocol` stub: `response` is served for every `snapshot()`
/// unless the call's 1-based order matches [gatedRequestIndex], in which case it
/// signals [gateReached] and blocks on [release] before answering with
/// [gatedResponse].
class FakeSnapshotSource(
    override val wsUrl: String = "wss://x/ws",
) : SnapshotSource {
    @Volatile
    var response: SnapshotResponse = SnapshotResponse(emptyList(), 0)

    @Volatile
    var gatedRequestIndex: Int? = null

    @Volatile
    var gatedResponse: SnapshotResponse? = null

    val gateReached = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val count = AtomicInteger(0)

    fun releaseGate() {
        release.complete(Unit)
    }

    override suspend fun snapshot(): SnapshotResponse {
        val myIndex = count.incrementAndGet()
        if (gatedRequestIndex == myIndex) {
            gateReached.complete(Unit)
            release.await()
            return gatedResponse ?: response
        }
        return response
    }
}

/// Simulates the journal server: replies to hello with events > cursor from a
/// canonical journal, then kills the connection after a random number of
/// frames. Every reconnect resumes from whatever cursor the client sends.
/// Ported from the Apple suite's `ChaosServerConnector`.
class ChaosServerConnector(
    private val journal: List<String>,
    private val headSeq: Long,
) : WebSocketConnecting {
    private val lock = Any()
    var connectCount = 0
        private set

    override suspend fun connect(url: String): WebSocketConnection {
        synchronized(lock) { connectCount++ }
        return ChaosServerConnection(journal, headSeq)
    }
}

private class ChaosServerConnection(
    private val journal: List<String>,
    private val headSeq: Long,
) : WebSocketConnection {
    private val inner = FakeWebSocketConnection()

    override suspend fun sendText(text: String) {
        val obj = parseJsonObjectOrNull(text)
        if (obj?.stringOrNull("op") != "hello") {
            inner.sendText(text)
            return
        }
        val cursor = obj.longOrNull("cursor") ?: 0
        inner.serve("""{"kind":"control","op":"hello_ok","seq":$headSeq}""")
        val remaining = journal.drop(cursor.toInt())
        val cutAfter = Random.nextInt(1, 13) // kill mid-replay, often mid-batch
        for ((offset, line) in remaining.withIndex()) {
            if (offset == cutAfter) {
                inner.closeFromServer()
                return
            }
            inner.serve(line)
        }
        // Served to the end without cutting: leave the connection open.
    }

    override suspend fun receiveText(): String = inner.receiveText()
    override suspend fun ping() = inner.ping()
    override fun close() = inner.close()
}
