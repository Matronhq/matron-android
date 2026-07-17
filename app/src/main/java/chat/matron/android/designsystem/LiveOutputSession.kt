package chat.matron.android.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import chat.matron.android.events.LiveOutputEvent
import chat.matron.android.events.LiveOutputFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlin.coroutines.coroutineContext

/// Frame-stream factory behind a [LiveOutputSession], injectable for tests. The
/// production default ([OkHttpLiveOutputConnector]) connects a WebSocket and
/// emits decoded frames until close; a clean close completes the flow, a
/// failure throws into the collector.
fun interface LiveOutputConnector {
    fun connect(url: String): Flow<LiveOutputFrame>
}

/// State machine + socket client behind one `LiveOutputCard`. Connects to the
/// bridge's viewer WebSocket (`…/live/ws?token=…`), accumulates ANSI-rendered
/// output, and tracks the command's lifecycle. One instance per `tool_use_id`,
/// owned by [LiveOutputSessionStore] so a row that scrolls out of a LazyColumn
/// and back reuses the same accumulated output and connection.
///
/// Reconnect semantics: the viewer replays the whole log from offset 0 on every
/// connect. [consumedBytes] counts what's already rendered, and each (re)connect
/// skips that prefix — the byte-offset accounting matron-web does, minus its
/// IndexedDB layer (the server replay IS the cache).
///
/// State is exposed as Compose snapshot state so the card recomposes as [phase]
/// and [output] change; the mutable class replaces the Swift `@Observable`.
class LiveOutputSession(
    val event: LiveOutputEvent,
    private val connector: LiveOutputConnector = OkHttpLiveOutputConnector(),
) {
    sealed interface Phase {
        data object Idle : Phase
        data object Connecting : Phase
        data object Streaming : Phase
        data class Complete(val exitCode: Int?, val denied: Boolean, val truncated: Boolean) : Phase
        data object Expired : Phase
        /// Socket failed / closed abnormally and retries are exhausted.
        data object Disconnected : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    var output by mutableStateOf(AnnotatedString(""))
        private set
    var hasOutput by mutableStateOf(false)
        private set

    private val parser = AnsiSGRParser()
    private var consumedBytes = 0
    private var runJob: Job? = null
    private var expiryJob: Job? = null
    private var attempts = 0

    /// Terminal states stop the retry loop; anything else means "try again"
    /// until the budget runs out.
    private val isTerminal: Boolean
        get() = phase is Phase.Complete || phase is Phase.Expired

    /// Idempotent kick-off — call from the card's `LaunchedEffect`. [scope] owns
    /// the streaming job (production: the card's remembered scope; tests: the
    /// test scope).
    fun startIfNeeded(scope: CoroutineScope) {
        if (runJob?.isActive == true) return
        if (phase is Phase.Complete) return
        val socketURL = event.socketURL ?: run { phase = Phase.Disconnected; return }
        if (event.isExpired) { phase = Phase.Expired; return }
        scheduleExpiry(scope)
        runJob = scope.launch { run(socketURL) }
    }

    private suspend fun run(socketURL: String) {
        try {
            while (coroutineContext.isActive && !isTerminal && attempts < MAX_ATTEMPTS) {
                attempts++
                phase = Phase.Connecting
                // Each connect replays from offset 0 — skip what's rendered.
                var replayOffset = 0
                try {
                    connector.connect(socketURL).collect { frame ->
                        when (frame) {
                            is LiveOutputFrame.Data -> {
                                if (phase != Phase.Streaming) phase = Phase.Streaming
                                val bytes = frame.chunk.toByteArray(Charsets.UTF_8)
                                val size = bytes.size
                                if (replayOffset + size <= consumedBytes) {
                                    replayOffset += size // fully within the rendered prefix
                                    return@collect
                                }
                                var fresh = frame.chunk
                                if (replayOffset < consumedBytes) {
                                    // Frame straddles the replay boundary — drop the
                                    // rendered prefix. Byte-slicing UTF-8 is safe: the
                                    // boundary was itself a frame edge previously.
                                    val skip = consumedBytes - replayOffset
                                    fresh = String(bytes.copyOfRange(skip, size), Charsets.UTF_8)
                                }
                                replayOffset += size
                                consumedBytes = maxOf(consumedBytes, replayOffset)
                                appendRendering(fresh)
                            }
                            is LiveOutputFrame.Complete -> {
                                phase = Phase.Complete(frame.exitCode, frame.denied, frame.truncated)
                                expiryJob?.cancel()
                                throw CollectionComplete()
                            }
                        }
                    }
                    // Clean close without a `complete` frame (token expired
                    // server-side, or the log was GC'd).
                    if (!isTerminal) {
                        phase = if (event.isExpired) Phase.Expired else Phase.Disconnected
                    }
                } catch (done: CollectionComplete) {
                    // Terminal `Complete` already recorded; fall through to exit.
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    if (event.isExpired) { phase = Phase.Expired; return }
                    phase = Phase.Disconnected
                    if (attempts < MAX_ATTEMPTS) delay(attempts * 2000L)
                }
            }
        } finally {
            runJob = null
        }
    }

    private fun appendRendering(chunk: String) {
        var remaining = chunk
        while (remaining.isNotEmpty()) {
            val slice: String
            if (remaining.length > PARSE_SLICE_CHARS) {
                slice = remaining.substring(0, PARSE_SLICE_CHARS)
                remaining = remaining.substring(PARSE_SLICE_CHARS)
            } else {
                slice = remaining
                remaining = ""
            }
            val rendered = parser.append(slice)
            if (rendered.isNotEmpty()) {
                output += rendered
                hasOutput = true
            }
        }
        // Rolling tail: past `maxOutputChars`, trim the head down to
        // `trimTargetChars` (hysteresis so we don't re-trim every chunk).
        val count = output.length
        if (count > MAX_OUTPUT_CHARS) {
            output = output.subSequence(count - TRIM_TARGET_CHARS, count)
        }
    }

    /// Force-closes at `expires_at` so a still-streaming pane doesn't sit in
    /// `.streaming` on a socket the server is about to reject anyway.
    private fun scheduleExpiry(scope: CoroutineScope) {
        val expiresAt = event.expiresAt ?: return
        expiryJob?.cancel()
        expiryJob = scope.launch {
            val interval = expiresAt.toEpochMilli() - System.currentTimeMillis()
            if (interval > 0) delay(interval)
            if (!isTerminal) {
                runJob?.cancel()
                runJob = null
                phase = Phase.Expired
            }
        }
    }

    fun teardown() {
        runJob?.cancel()
        runJob = null
        expiryJob?.cancel()
        expiryJob = null
    }

    /// Control-flow signal to stop collecting once the terminal `complete`
    /// frame lands — swallowed by `run`, never surfaced.
    private class CollectionComplete : Exception()

    companion object {
        /// Rendered-output ceiling — a rolling tail so a giant replay can't
        /// balloon memory or hang rendering.
        private const val MAX_OUTPUT_CHARS = 200_000
        private const val TRIM_TARGET_CHARS = 150_000
        /// Parse a big connect-time replay in slices so it doesn't freeze a
        /// frame; a character-count proxy for the Swift UTF-8 byte budget.
        private const val PARSE_SLICE_CHARS = 64 * 1024
        private const val MAX_ATTEMPTS = 3
    }
}

/// Bounded-LRU registry of live-output sessions keyed by `tool_use_id`. A
/// LazyColumn recycles rows as the user scrolls; without this each re-mount
/// would open a fresh socket and replay the whole log.
class LiveOutputSessionStore(private val limit: Int = 8) {
    private val sessions = LinkedHashMap<String, LiveOutputSession>()

    fun session(event: LiveOutputEvent): LiveOutputSession {
        sessions.remove(event.toolUseID)?.let { existing ->
            sessions[event.toolUseID] = existing // move to MRU end
            return existing
        }
        val created = LiveOutputSession(event)
        sessions[event.toolUseID] = created
        if (sessions.size > limit) {
            val eldest = sessions.keys.first()
            sessions.remove(eldest)?.teardown()
        }
        return created
    }

    companion object {
        val shared = LiveOutputSessionStore()
    }
}

/// Live [OkHttpClient]-backed connector (the production default). Emits decoded
/// frames; a clean close finishes the flow, a failure throws into the collector.
class OkHttpLiveOutputConnector(
    private val client: OkHttpClient = defaultClient(),
) : LiveOutputConnector {
    override fun connect(url: String): Flow<LiveOutputFrame> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                LiveOutputFrame.decode(text)?.let { trySend(it) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                LiveOutputFrame.decode(bytes.utf8())?.let { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
        }
        val ws = client.newWebSocket(request, listener)
        awaitClose { ws.cancel() }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient()
    }
}
