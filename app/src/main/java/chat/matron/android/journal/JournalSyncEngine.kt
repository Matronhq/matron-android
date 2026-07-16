package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import chat.matron.android.models.SessionStatusUpdate
import chat.matron.android.models.SyncConnectionState
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/// Lifecycle-level errors surfaced by the engine.
sealed class JournalSyncError : Exception() {
    data object Offline : JournalSyncError()
    data object AuthRevoked : JournalSyncError()
}

/// An agent's answer to `agentRequest` — either the method's result (raw JSON,
/// caller decodes) or the bridge/server error code.
sealed interface RPCReply {
    data class Ok(val result: JsonElement) : RPCReply
    data class Failure(val code: String, val detail: String?) : RPCReply
}

sealed class RPCRequestError : Exception() {
    /// No answer within the deadline. At-most-once: the caller re-asks.
    data object Timeout : RPCRequestError()
    /// No live journal connection to send on (or it died mid-request).
    data object Offline : RPCRequestError()
}

/// Optional sink for full-text search indexing of applied journal events.
/// Search itself is out of scope for this port; the seam is kept so the
/// index-on-apply behavior can light up when a search backend lands.
interface SearchIndexer {
    suspend fun index(roomID: String, eventID: String, sender: String, timestamp: Instant, body: String)
}

/// The single writer of the [JournalStore] and owner of the reconnect loop.
/// Any failure converges to "reconnect and resume from the store cursor" —
/// there is no other recovery path, so there is nothing to wedge.
///
/// The Apple original is a Swift `actor`. Here state is confined by a single
/// monitor [lock]; every critical section is synchronous (never suspends while
/// holding the lock), so serialization matches an actor's without reentrancy
/// hazards. Socket sends and store writes happen outside the lock.
///
/// Network-path monitoring (the Apple `NWPathMonitor` reconnect-on-path-change)
/// is intentionally not wired to a system callback here — the Android layer can
/// call [onNetworkAvailable] from a `ConnectivityManager` callback. The
/// `pathChangeReconnect` fast-path in the run loop is preserved.
class JournalSyncEngine(
    private val api: SnapshotSource,
    private val store: JournalStore,
    private val connector: WebSocketConnecting,
    private val token: String,
    private val ownSender: String,
    private val search: SearchIndexer? = null,
    private val backoffBaseSeconds: Double = 1.0,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val watchdogInterval: Duration = 20.seconds,
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lock = Any()

    private val _state = MutableStateFlow<SyncConnectionState>(SyncConnectionState.Connecting)

    // Guarded by `lock`.
    private var runJob: Job? = null
    private var liveConnection: JournalConnection? = null
    private var viewingConvoID: String? = null
    private var backoffJob: Job? = null
    private var refreshJob: Job? = null
    private var attempt = 0
    private var pathChangeReconnect = false
    private var storeEpoch = 0
    private val readyWaiters = mutableListOf<CancellableContinuation<Unit>>()
    private val lastSessionStatus = mutableMapOf<String, SessionStatusUpdate>()

    // Fan-out registries: id -> (convoID filter, delivery callback). Guarded by `lock`.
    private val ephemeralListeners = mutableMapOf<UUID, Pair<String, (EphemeralUpdate) -> Unit>>()
    private val activityListeners = mutableMapOf<UUID, Pair<String, (ActivityUpdate) -> Unit>>()
    private val toolStreamListeners = mutableMapOf<UUID, Pair<String, (ToolStreamUpdate) -> Unit>>()
    private val sessionStatusListeners = mutableMapOf<UUID, Pair<String, (SessionStatusUpdate) -> Unit>>()
    private val newConvoListeners = mutableMapOf<UUID, (String) -> Unit>()

    private class PendingRPC(
        val op: ClientOp,
        val notReadyBackoff: Duration,
        var resendsRemaining: Int,
        val continuation: CancellableContinuation<RPCReply>,
    )
    private val rpcPending = mutableMapOf<String, PendingRPC>()

    /// Signals the run loop's frame collection to exit on `snapshot_required`
    /// after the mirror has been wiped, falling through to reconnect.
    private class SnapshotRequiredExit : Exception()

    // MARK: Lifecycle

    fun beginSync() {
        synchronized(lock) {
            if (runJob != null) return
            attempt = 0
            runJob = scope.launch { runLoop() }
        }
    }

    suspend fun endSync() {
        val job: Job?
        val backoff: Job?
        val refresh: Job?
        val conn: JournalConnection?
        val waiters: List<CancellableContinuation<Unit>>
        val rpc: List<PendingRPC>
        synchronized(lock) {
            job = runJob; runJob = null
            backoff = backoffJob; backoffJob = null
            refresh = refreshJob; refreshJob = null
            conn = liveConnection; liveConnection = null
            pathChangeReconnect = false
            // Drop the status replay cache with the connection: values here are
            // only as fresh as the live stream, and a future beginSync()'s
            // `viewing` replay repopulates it.
            lastSessionStatus.clear()
            waiters = readyWaiters.toList(); readyWaiters.clear()
            rpc = rpcPending.values.toList(); rpcPending.clear()
        }
        job?.cancel()
        backoff?.cancel()
        refresh?.cancel()
        conn?.close()
        waiters.forEach { it.resumeWith(Result.failure(JournalSyncError.Offline)) }
        rpc.forEach { it.continuation.resumeWith(Result.failure(RPCRequestError.Offline)) }
        // Don't clobber a terminal offline reason (e.g. auth revocation) set
        // before endSync() was called.
        if (_state.value !is SyncConnectionState.Offline) {
            setState(SyncConnectionState.Offline(null))
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { runJob != null }

    suspend fun waitUntilReady() {
        suspendCancellableCoroutine<Unit> { cont ->
            synchronized(lock) {
                when {
                    _state.value is SyncConnectionState.Running -> cont.resumeWith(Result.success(Unit))
                    runJob == null -> cont.resumeWith(Result.failure(JournalSyncError.Offline))
                    else -> {
                        readyWaiters.add(cont)
                        cont.invokeOnCancellation { synchronized(lock) { readyWaiters.remove(cont) } }
                    }
                }
            }
        }
    }

    /// Cancels an in-flight backoff sleep so a reconnect is attempted now.
    fun nudge() {
        synchronized(lock) { backoffJob }?.cancel()
    }

    /// Android network-availability seam (analogue of the Apple path monitor).
    /// Mid-backoff, retry now on the fresh path; otherwise close the live
    /// socket and route the run loop straight back to `.connecting`.
    fun onNetworkAvailable() {
        val conn = synchronized(lock) {
            if (liveConnection == null) return@synchronized null
            pathChangeReconnect = true
            liveConnection
        }
        if (conn == null) nudge() else conn.close()
    }

    // MARK: Public surface

    suspend fun sendOp(op: ClientOp) {
        val connection = synchronized(lock) { liveConnection } ?: throw JournalSyncError.Offline
        connection.send(op)
    }

    suspend fun setViewing(convoID: String?) {
        synchronized(lock) { viewingConvoID = convoID }
        val connection = synchronized(lock) { liveConnection }
        runCatching { connection?.send(ClientOp.Viewing(convoID)) }
    }

    /// Inserts a placeholder conversation row for a convo id learned
    /// out-of-band. Routed through the engine so the store keeps a single
    /// writer; an existing row is never touched.
    suspend fun ensurePlaceholderConversation(id: String, title: String) {
        runCatching { store.ensureConversation(id, title) }
    }

    suspend fun refreshSummaries() {
        val epoch = synchronized(lock) { storeEpoch }
        val snapshot = runCatching { api.snapshot() }.getOrNull() ?: return
        if (synchronized(lock) { storeEpoch } != epoch) return // store wiped mid-flight; stale
        runCatching { store.refreshSummaries(snapshot.conversations) }
    }

    /// Sends a structured request to one of the user's agent devices and awaits
    /// the correlated answer. At-most-once on `.timeout`; `not_ready` (we raced
    /// our own hello replay) is retried internally with the identical frame.
    suspend fun agentRequest(
        agentDeviceID: Long,
        method: String,
        paramsJson: String,
        timeout: Duration = 15.seconds,
        notReadyBackoff: Duration = 1.seconds,
    ): RPCReply {
        val connection = synchronized(lock) { liveConnection } ?: throw RPCRequestError.Offline
        val requestID = UUID.randomUUID().toString()
        val op = ClientOp.AgentRequest(requestID, agentDeviceID, method, paramsJson)
        val deadline = scope.launch {
            delay(timeout)
            expireRPC(requestID)
        }
        try {
            return suspendCancellableCoroutine { cont ->
                synchronized(lock) {
                    rpcPending[requestID] = PendingRPC(op, notReadyBackoff, 2, cont)
                }
                cont.invokeOnCancellation { synchronized(lock) { rpcPending.remove(requestID) } }
                scope.launch {
                    runCatching { connection.send(op) }
                        .onFailure { dropRPC(requestID, RPCRequestError.Offline) }
                }
            }
        } finally {
            deadline.cancel()
        }
    }

    // MARK: Streams

    val stateStream: StateFlow<SyncConnectionState> get() = _state.asStateFlow()

    fun ephemerals(convoID: String): Flow<EphemeralUpdate> = callbackFlow {
        val id = UUID.randomUUID()
        synchronized(lock) { ephemeralListeners[id] = convoID to { u -> trySend(u) } }
        awaitClose { synchronized(lock) { ephemeralListeners.remove(id) } }
    }

    fun activities(convoID: String): Flow<ActivityUpdate> = callbackFlow {
        val id = UUID.randomUUID()
        synchronized(lock) { activityListeners[id] = convoID to { u -> trySend(u) } }
        awaitClose { synchronized(lock) { activityListeners.remove(id) } }
    }

    fun toolStreams(convoID: String): Flow<ToolStreamUpdate> = callbackFlow {
        val id = UUID.randomUUID()
        synchronized(lock) { toolStreamListeners[id] = convoID to { u -> trySend(u) } }
        awaitClose { synchronized(lock) { toolStreamListeners.remove(id) } }
    }

    /// Per-conversation session-status stream. Replays the last cached frame on
    /// subscribe (merged, absent-means-unchanged semantics) so a subscriber
    /// attaching on convo-open gets a populated header immediately, then live
    /// frames follow.
    fun sessionStatus(convoID: String): Flow<SessionStatusUpdate> = callbackFlow {
        val id = UUID.randomUUID()
        synchronized(lock) {
            sessionStatusListeners[id] = convoID to { u -> trySend(u) }
            lastSessionStatus[convoID]?.let { trySend(it) }
        }
        awaitClose { synchronized(lock) { sessionStatusListeners.remove(id) } }
    }

    /// Emits the id of a conversation created live (first-ever frame while
    /// `.running`). A reconnect backlog does NOT replay through here.
    fun newConversations(): Flow<String> = callbackFlow {
        val id = UUID.randomUUID()
        synchronized(lock) { newConvoListeners[id] = { c -> trySend(c) } }
        awaitClose { synchronized(lock) { newConvoListeners.remove(id) } }
    }

    // MARK: State plumbing

    private fun setState(new: SyncConnectionState) {
        val toResume: List<CancellableContinuation<Unit>> = synchronized(lock) {
            if (_state.value == new) return
            _state.value = new
            if (new is SyncConnectionState.Running) {
                val w = readyWaiters.toList(); readyWaiters.clear(); w
            } else {
                emptyList()
            }
        }
        toResume.forEach { it.resumeWith(Result.success(Unit)) }
    }

    private fun failReadyWaiters(error: Throwable) {
        val w = synchronized(lock) { val l = readyWaiters.toList(); readyWaiters.clear(); l }
        w.forEach { it.resumeWith(Result.failure(error)) }
    }

    private fun isRunningState(): Boolean = synchronized(lock) { _state.value is SyncConnectionState.Running }

    // MARK: Fan-out

    private fun fanOutEphemeral(update: EphemeralUpdate) = synchronized(lock) {
        ephemeralListeners.values.forEach { (convoID, deliver) -> if (convoID == update.convoID) deliver(update) }
    }

    private fun fanOutActivity(update: ActivityUpdate) = synchronized(lock) {
        activityListeners.values.forEach { (convoID, deliver) -> if (convoID == update.convoID) deliver(update) }
    }

    private fun fanOutToolStream(update: ToolStreamUpdate) = synchronized(lock) {
        toolStreamListeners.values.forEach { (convoID, deliver) -> if (convoID == update.convoID) deliver(update) }
    }

    private fun handleSessionStatus(update: SessionStatusUpdate) = synchronized(lock) {
        val held = lastSessionStatus[update.convoID]
        lastSessionStatus[update.convoID] = if (held != null) {
            SessionStatusUpdate(
                convoID = update.convoID,
                model = update.model ?: held.model,
                context = update.context ?: held.context,
                limits = update.limits ?: held.limits,
                email = update.email ?: held.email,
                taskRef = update.taskRef ?: held.taskRef,
            )
        } else {
            update
        }
        // Live subscribers get the raw frame (not the merged cache).
        sessionStatusListeners.values.forEach { (convoID, deliver) ->
            if (convoID == update.convoID) deliver(update)
        }
    }

    private fun publishNewConversation(convoID: String) = synchronized(lock) {
        newConvoListeners.values.forEach { it(convoID) }
    }

    // MARK: RPC correlator

    private fun resumeRPC(response: RPCResponse) {
        val pending = synchronized(lock) { rpcPending.remove(response.requestID) } ?: return
        if (response.ok) {
            pending.continuation.resumeWith(Result.success(RPCReply.Ok(response.result ?: JsonNull)))
        } else {
            pending.continuation.resumeWith(
                Result.success(RPCReply.Failure(response.errorCode ?: "unknown", response.errorDetail))
            )
        }
    }

    private fun failRPC(requestID: String, code: String, detail: String?) {
        // not_ready = our own hello replay hasn't finished; nothing was
        // forwarded, so the identical frame re-sends safely after a beat.
        val resend: Pair<ClientOp, Duration>?
        val fail: PendingRPC?
        synchronized(lock) {
            val p = rpcPending[requestID]
            if (code == "not_ready" && p != null && p.resendsRemaining > 0) {
                p.resendsRemaining -= 1
                resend = p.op to p.notReadyBackoff
                fail = null
            } else {
                resend = null
                fail = rpcPending.remove(requestID)
            }
        }
        if (resend != null) {
            val (op, backoff) = resend
            scope.launch {
                delay(backoff)
                resendRPC(requestID, op)
            }
            return
        }
        fail?.continuation?.resumeWith(Result.success(RPCReply.Failure(code, detail)))
    }

    private fun resendRPC(requestID: String, op: ClientOp) {
        val connection = synchronized(lock) {
            if (rpcPending[requestID] == null) return // timed out meanwhile
            liveConnection
        }
        if (connection == null) {
            dropRPC(requestID, RPCRequestError.Offline)
            return
        }
        scope.launch {
            runCatching { connection.send(op) }
                .onFailure { dropRPC(requestID, RPCRequestError.Offline) }
        }
    }

    private fun expireRPC(requestID: String) = dropRPC(requestID, RPCRequestError.Timeout)

    private fun dropRPC(requestID: String, error: Throwable) {
        val pending = synchronized(lock) { rpcPending.remove(requestID) } ?: return
        pending.continuation.resumeWith(Result.failure(error))
    }

    /// Connection teardown: every in-flight RPC fails now — the relay keeps no
    /// state, so an answer can never arrive on the next socket.
    private fun failAllRPC(error: Throwable) {
        val pending = synchronized(lock) { val l = rpcPending.values.toList(); rpcPending.clear(); l }
        pending.forEach { it.continuation.resumeWith(Result.failure(error)) }
    }

    // MARK: Run loop

    private suspend fun runLoop() {
        while (scope.isActive) {
            try {
                setState(SyncConnectionState.Connecting)
                coldStartIfNeeded()
                val cursor = store.cursor()
                val (connection, headSeq) = JournalConnection.establish(
                    connector = connector, wsUrl = api.wsUrl, token = token, cursor = cursor,
                )
                synchronized(lock) { liveConnection = connection; attempt = 0 }
                synchronized(lock) { viewingConvoID }?.let {
                    runCatching { connection.send(ClientOp.Viewing(it)) }
                }
                // Ack cursor progress on every connect: a dead socket can't take
                // a final flush, so this bounds the server's stored device
                // cursor to one reconnect's worth of frames.
                if (store.cursor() > 0) runCatching { connection.send(ClientOp.Ack(store.cursor())) }
                startRefreshSummaries()
                if (store.cursor() >= headSeq) setState(SyncConnectionState.Running)

                val watchdog = launchWatchdog(connection)
                try {
                    var appliedSinceAck = 0L
                    connection.frames().collect { frame ->
                        appliedSinceAck = handleFrame(frame, connection, headSeq, appliedSinceAck)
                    }
                } catch (e: SnapshotRequiredExit) {
                    // Mirror already wiped in handleFrame; fall through to reconnect.
                } finally {
                    watchdog.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: JournalConnectionError.AuthRejected) {
                MatronDebug.breadcrumb("server rejected auth — stopping sync (signed out by server)")
                synchronized(lock) { liveConnection = null; runJob = null }
                setState(SyncConnectionState.Offline("Signed out by server"))
                failReadyWaiters(JournalSyncError.AuthRevoked)
                failAllRPC(RPCRequestError.Offline)
                return
            } catch (e: Throwable) {
                // Fall through to backoff — but never silently.
                val n = synchronized(lock) { attempt + 1 }
                MatronDebug.breadcrumb("connect/stream failed (attempt $n): $e")
            }
            closeLive()
            failAllRPC(RPCRequestError.Offline)
            if (!scope.isActive) return
            if (takePathChangeReconnect()) {
                setState(SyncConnectionState.Connecting)
                continue
            }
            setState(SyncConnectionState.Offline(null))
            backoff()
        }
    }

    private suspend fun handleFrame(
        frame: ServerFrame,
        connection: JournalConnection,
        headSeq: Long,
        appliedSinceAck: Long,
    ): Long {
        when (frame) {
            is ServerFrame.Journal -> {
                val event = frame.event
                // Read before applyJournal creates the row: true exactly once
                // per new conversation (its first-ever frame).
                val isNewConvo = runCatching { store.conversationExists(event.convoID) }.getOrNull() == false
                var applied = appliedSinceAck
                // A thrown store write propagates (disk full / I/O error): the
                // cursor only advances inside the same transaction as a
                // successful write, so on failure it's untouched, and letting
                // the error escape routes to reconnect-from-cursor. `false`
                // (duplicate, seq <= cursor) is a legitimate no-op.
                if (store.applyJournal(event)) {
                    indexForSearch(event)
                    applied += 1
                    if (applied >= 50) {
                        runCatching { connection.send(ClientOp.Ack(store.cursor())) }
                        applied = 0
                    }
                    // Surface a conversation the bridge created while we're live
                    // and caught up. Two guards keep subagent children silent
                    // regardless of frame ordering: structural (`:sub:` in the
                    // id) and semantic (learned parent linkage).
                    if (isNewConvo && isRunningState() &&
                        !event.convoID.contains(JournalEventType.CHILD_CONVO_INFIX) &&
                        runCatching { store.parentConvoID(event.convoID) }.getOrNull() == null
                    ) {
                        publishNewConversation(event.convoID)
                    }
                }
                if (store.cursor() >= headSeq) setState(SyncConnectionState.Running)
                return applied
            }
            is ServerFrame.Ephemeral -> fanOutEphemeral(frame.update)
            is ServerFrame.Activity -> fanOutActivity(frame.update)
            is ServerFrame.ToolStream -> fanOutToolStream(frame.update)
            is ServerFrame.SessionStatusFrame -> handleSessionStatus(frame.update)
            is ServerFrame.RpcResponse -> resumeRPC(frame.response)
            is ServerFrame.Error -> frame.requestID?.let { failRPC(it, frame.code, frame.detail) }
            is ServerFrame.SnapshotRequired -> {
                // Gap too large to replay (server valve). Cancel any in-flight
                // refreshSummaries() first (its response is stale relative to
                // the wipe), bump the epoch to fence it, drop the status replay
                // cache, then wipe the mirror. Force the reconnect
                // deterministically by exiting the frame loop.
                MatronDebug.breadcrumb("snapshot_required: replay gap too large — wiping local mirror")
                synchronized(lock) {
                    refreshJob?.cancel()
                    storeEpoch += 1
                    lastSessionStatus.clear()
                }
                runCatching { store.wipe() }
                throw SnapshotRequiredExit()
            }
            is ServerFrame.HelloOK, is ServerFrame.UnknownControl -> Unit // post-hello control frames are advisory
        }
        return appliedSinceAck
    }

    private suspend fun coldStartIfNeeded() {
        val emptyConvos = runCatching { store.conversations().isEmpty() }.getOrDefault(true)
        if (!(store.cursor() == 0L && emptyConvos)) return
        val snapshot = api.snapshot()
        store.applyColdSnapshot(snapshot.conversations, snapshot.seq)
    }

    private fun launchWatchdog(connection: JournalConnection): Job = scope.launch {
        var misses = 0
        while (isActive) {
            delay(watchdogInterval)
            if (!isActive) return@launch
            try {
                connection.ping()
                misses = 0
            } catch (e: Throwable) {
                misses += 1
                if (misses >= 2) {
                    connection.close()
                    return@launch
                }
            }
        }
    }

    private suspend fun startRefreshSummaries() {
        synchronized(lock) {
            refreshJob?.cancel()
            refreshJob = scope.launch { refreshSummaries() } // title/state stopgap
        }
    }

    private suspend fun backoff() {
        val a = synchronized(lock) { attempt += 1; attempt }
        val capped = min(backoffBaseSeconds * 2.0.pow((a - 1).toDouble()), 60.0)
        val jittered = capped * (0.8 + Random.nextDouble() * 0.4)
        val job = scope.launch { runCatching { delay((jittered * 1000).toLong().milliseconds) } }
        synchronized(lock) { backoffJob = job }
        job.join() // nudge()/onNetworkAvailable() cancels this → immediate retry
        synchronized(lock) { backoffJob = null }
    }

    private fun closeLive() {
        synchronized(lock) { val c = liveConnection; liveConnection = null; c }?.close()
    }

    private fun takePathChangeReconnect(): Boolean = synchronized(lock) {
        val p = pathChangeReconnect; pathChangeReconnect = false; p
    }

    private fun indexForSearch(event: JournalEvent) {
        val search = this.search ?: return
        val payload = event.payload
        val body = when (event.type) {
            JournalEventType.TEXT -> payload.stringOrNull("body")
            JournalEventType.TOOL_OUTPUT -> payload.stringOrNull("snippet")
            // Mirror the timeline mapper's precedence (diff, then snippet).
            JournalEventType.DIFF -> payload.stringOrNull("diff") ?: payload.stringOrNull("snippet")
            else -> null
        }
        if (body.isNullOrEmpty()) return
        scope.launch {
            runCatching {
                search.index(event.convoID, event.seq.toString(), event.sender, event.ts, body)
            }
        }
    }
}
