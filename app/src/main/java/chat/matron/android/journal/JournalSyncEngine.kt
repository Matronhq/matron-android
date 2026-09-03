package chat.matron.android.journal

import chat.matron.android.journal.db.OutboxEntity
import chat.matron.android.models.MatronDebug
import chat.matron.android.models.SessionStatusUpdate
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.sync.SyncService
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
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
import kotlinx.coroutines.cancelAndJoin
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

/// How many reconnect-replay journal frames land per store transaction. The
/// value matron-apple #85 settled on: big enough that a 10k-frame backlog is
/// ~40 commits instead of 10k, small enough that the first screenful paints
/// without waiting for the whole backlog.
private const val REPLAY_BATCH_SIZE = 250

/// Lifecycle-level errors surfaced by the engine. The messages surface
/// verbatim in UI banners — without them, an offline send rendered as the
/// class's toString gibberish (Dan's 2026-07-30 screenshot on iOS).
sealed class JournalSyncError(message: String) : Exception(message) {
    data object Offline : JournalSyncError("No connection to the server.")
    data object AuthRevoked : JournalSyncError("This device was signed out by the server.")
}

/// An agent's answer to `agentRequest` — either the method's result (raw JSON,
/// caller decodes) or the bridge/server error code.
sealed interface RPCReply {
    data class Ok(val result: JsonElement) : RPCReply
    data class Failure(val code: String, val detail: String?) : RPCReply
}

sealed class RPCRequestError(message: String) : Exception(message) {
    /// No answer within the deadline. At-most-once: the caller re-asks.
    data object Timeout : RPCRequestError("The agent didn't answer in time.")
    /// No live journal connection to send on (or it died mid-request).
    data object Offline : RPCRequestError("No connection to the server.")
}

/// Sink for full-text search indexing of applied journal events. Optional
/// (`null` when the local search DB failed to open — [chat.matron.android.AppDependencies]
/// degrades to "search disabled" rather than failing to launch); when present,
/// [chat.matron.android.search.SearchServiceLive] indexes every applied event on the fly.
interface SearchIndexer {
    suspend fun index(roomID: String, eventID: String, sender: String, timestamp: Instant, body: String)

    /// Clears all backfill bookkeeping while keeping the indexed messages.
    /// Called when the local journal mirror re-bootstraps from a snapshot
    /// (`coldStartIfNeeded`): the unbridgeable replay gap means "complete"
    /// flags may now hide head-side holes, so the backfill sweep must re-walk
    /// every room from its newest page (cheap — already-indexed rows are
    /// re-indexed idempotently). Ported from matron-apple's
    /// `SearchService.resetBackfill`. Default no-op so indexing-only fakes
    /// stay small; [chat.matron.android.search.SearchServiceLive] overrides.
    suspend fun resetBackfill() {}

    /// Monotonic count of [resetBackfill] calls in this process. The backfill
    /// walk snapshots it per room and refuses to write progress once it moved:
    /// the walk's bookkeeping (resume point, complete flag) predates the reset,
    /// and re-asserting it would resurrect exactly the head-side-hole-hiding
    /// rows the reset just deleted (bugbot "Backfill races cold-start reset").
    /// In-memory only — the race is in-process; a restart starts fresh walks.
    /// Deviation from matron-apple, which has the same race unguarded (its
    /// coordinator is an actor, but the engine's cold-start reset goes
    /// straight to the SearchService, not through the coordinator). Default 0
    /// pairs with the no-op [resetBackfill]: a fake that never resets never
    /// moves the generation.
    suspend fun backfillGeneration(): Long = 0
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
) : SyncService {
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

    // MARK: Offline outbox state (guarded by `lock`)

    /// Rows already written to the CURRENT socket. A row stays in the outbox
    /// until its journal frame confirms delivery, so without this set every
    /// extra flush pass on the same connection would resend it. Cleared on
    /// each new connection: the server's idem key (folded from `local_id`)
    /// dedups the once-per-connection resend of anything that actually landed
    /// but wasn't confirmed before the socket died.
    private val sentOnThisConnection = mutableSetOf<String>()

    /// FIFO of the same localIDs, in write order. A server rejection frame
    /// (`op:'error', ref:'send'`) names only the op, not the row — but the
    /// socket is processed in order on both ends, so the rejection belongs to
    /// the oldest write that hasn't been confirmed or failed yet.
    private val sendOrderThisConnection = mutableListOf<String>()

    /// LocalID → blobRef of media sends ([ClientOp.SendMedia]) on the current
    /// socket. Media goes over the wire as `op:"send"` too, so it must occupy
    /// its slot in [sendOrderThisConnection]: a rejection that pops a media
    /// entry is consumed there (media has no durable row to mark) instead of
    /// falling through and failing an unrelated queued text row (bugbot "Media
    /// send errors fail outbox text"). A slot is retired when the own media
    /// journal frame echoes its blobRef back — delivery confirmed — so a stale
    /// entry can't swallow a later text rejection (bugbot "Stale media slots
    /// swallow send errors").
    private val mediaSendsThisConnection = mutableMapOf<String, String>()

    /// Single-flight latch for [flushOutbox].
    private var flushingOutbox = false

    /// Set when a flush is requested while one is running: the running flush
    /// re-drains before releasing the latch, so a row enqueued after the
    /// in-flight flush's last outbox read can't strand until the next
    /// reconnect (bugbot "Concurrent flush task dropped").
    private var flushRequestedWhileBusy = false

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
    //
    // [SyncService] conformance. The engine keeps its own `beginSync`/`endSync`
    // names (called directly by engine tests and the composition root);
    // `start`/`stop` delegate so the service protocol layers on cleanly.

    override suspend fun start() = beginSync()

    override suspend fun stop() = endSync()

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
        backoff?.cancel()
        refresh?.cancel()
        conn?.close()
        waiters.forEach { it.resumeWith(Result.failure(JournalSyncError.Offline)) }
        rpc.forEach { it.continuation.resumeWith(Result.failure(RPCRequestError.Offline)) }
        // Kill the run loop FIRST and wait for it to fully die. Everything that
        // spawns into the engine scope (indexForSearch, refreshJob, RPC resends)
        // originates from the run loop, so once it's gone nothing new can appear.
        // A one-shot children snapshot (the earlier approach) raced the loop's
        // cancellation unwind: between cancel() and the next suspension it could
        // run one more handleFrame/reconnect step and launch a fresh detached
        // writer AFTER the snapshot — a straggler that could still land a write
        // after signOut()'s wipe(). Joining the run loop closes that spawn source,
        // then we drain any remaining detached children, looping until the scope
        // is quiescent so a child spawned during another's unwind can't slip
        // through. Waiters/RPCs are already failed above, so this join can't
        // reintroduce the hang `endSyncFailsReadyWaitersInsteadOfHanging` guards;
        // `self` is excluded so endSync is deadlock-free from within engine work.
        val self = coroutineContext[Job]
        if (job !== self) job?.cancelAndJoin()
        while (true) {
            val remaining = scope.coroutineContext[Job]?.children
                ?.filter { it !== self }?.toList().orEmpty()
            if (remaining.isEmpty()) break
            remaining.forEach { it.cancelAndJoin() }
        }
        // Don't clobber a terminal offline reason (e.g. auth revocation) set
        // before endSync() was called.
        if (_state.value !is SyncConnectionState.Offline) {
            setState(SyncConnectionState.Offline(null))
        }
    }

    override fun isRunning(): Boolean = synchronized(lock) { runJob != null }

    override suspend fun waitUntilReady() {
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
        // Media shares the `op:"send"` wire namespace with outbox flushes, so
        // it must take its slot in the rejection-attribution FIFO.
        if (op is ClientOp.SendMedia) {
            synchronized(lock) {
                mediaSendsThisConnection[op.localID] = op.blobRef
                sendOrderThisConnection.add(op.localID)
            }
        }
    }

    // MARK: Offline outbox

    /// Queue-and-flush text send — the offline-tolerant replacement for
    /// `sendOp(ClientOp.Send(...))`. The message is durably enqueued first (it
    /// survives relaunch and renders as a queued/sending echo via
    /// [JournalStore.outboxFlow]), then flushed immediately when a connection
    /// is live. Never throws for being offline; only a store write failure
    /// (disk) escapes, so the composer can keep the text.
    suspend fun sendMessage(convoID: String, body: String, localID: String) {
        store.outboxInsert(localID = localID, convoID = convoID, body = body)
        if (synchronized(lock) { liveConnection } != null) {
            scope.launch { flushOutbox() }
        }
    }

    /// Tap-to-retry for a failed (or stuck-queued) outbox row: requeues it,
    /// clears its sent-marker so it's eligible on this connection again, and
    /// kicks a flush — or, when offline, cancels any backoff sleep so the
    /// reconnect (and its connect-flush) happens now.
    suspend fun retryOutboxItem(localID: String) {
        runCatching { store.outboxRequeue(localID) }
        val live = synchronized(lock) {
            sentOnThisConnection.remove(localID)
            liveConnection != null
        }
        if (live) scope.launch { flushOutbox() } else nudge()
    }

    /// Removes an unsent message the user chose to discard.
    suspend fun discardOutboxItem(localID: String) {
        runCatching { store.outboxDelete(localID) }
        synchronized(lock) { sentOnThisConnection.remove(localID) }
    }

    /// Whether any queued sends are still awaiting delivery confirmation. The
    /// background catch-up worker polls this so a send-then-pocket flush can
    /// finish before it returns.
    suspend fun hasPendingOutbox(): Boolean =
        runCatching { store.outboxPending().isNotEmpty() }.getOrDefault(false)

    /// A post-hello `{op:'error', ref:'send'}` frame: the server REJECTED a
    /// send op (validation), so retrying it unchanged can never succeed — flip
    /// the row to failed (surfacing "Not delivered — tap to retry") instead of
    /// leaving it silently re-flushing on every reconnect forever (bugbot
    /// "Send rejections never mark rows failed"). The frame carries no row id;
    /// FIFO ordering picks the victim (see [sendOrderThisConnection]) — one
    /// slot per WRITE, so a retried row legitimately holds two slots. Each
    /// popped slot is dispatched on its row's state: confirmed-deleted rows
    /// are skipped (in-order delivery means their op succeeded before this
    /// error was emitted), already-failed rows ABSORB the rejection (it's the
    /// duplicate write of the same rejected content — falling through would
    /// misattribute it to the next in-flight send; bugbot "Retry duplicates
    /// send rejection FIFO"), and queued rows are flipped to failed.
    private suspend fun handleSendRejected(code: String, detail: String?) {
        while (true) {
            val localID = synchronized(lock) {
                if (sendOrderThisConnection.isEmpty()) null else sendOrderThisConnection.removeAt(0)
            } ?: return
            if (synchronized(lock) { mediaSendsThisConnection.remove(localID) != null }) {
                // The rejected op was a media send: consume the rejection here.
                // There's no durable row to flip — the upload path surfaced any
                // synchronous error, and an async rejection is lost (as on iOS).
                MatronDebug.breadcrumb("server rejected media send $localID: $code ${detail ?: ""}")
                return
            }
            val row = runCatching { store.outboxRow(localID) }.getOrNull()
            when {
                row == null -> continue // confirmed-deleted (or discarded): its op succeeded
                row.state == OutboxEntity.STATE_FAILED -> {
                    MatronDebug.breadcrumb("server rejected duplicate write of failed send $localID: $code")
                    return
                }
                else -> {
                    MatronDebug.breadcrumb("server rejected send $localID: $code ${detail ?: ""}")
                    runCatching { store.outboxMarkFailed(localID, detail ?: code) }
                    synchronized(lock) { sentOnThisConnection.remove(localID) }
                    return
                }
            }
        }
    }

    /// Retires the rejection-FIFO slot of a delivered media send, matched by
    /// the `blob_ref` its journal payload echoes (oldest first when the same
    /// blob was sent twice).
    private fun confirmMediaSend(blobRef: String) {
        synchronized(lock) {
            val localID = sendOrderThisConnection.firstOrNull { mediaSendsThisConnection[it] == blobRef }
                ?: return
            sendOrderThisConnection.remove(localID)
            mediaSendsThisConnection.remove(localID)
        }
    }

    /// Sends every queued outbox row not yet written to the current
    /// connection, oldest first, coalescing concurrent callers behind a
    /// single-flight latch that re-drains when a request landed mid-flush.
    private suspend fun flushOutbox() {
        synchronized(lock) {
            if (flushingOutbox) {
                // A flush is mid-flight and may already have taken its last
                // outbox read; flag it to re-drain so the row that prompted
                // this call can't be skipped until the next reconnect.
                flushRequestedWhileBusy = true
                return
            }
            flushingOutbox = true
        }
        try {
            do {
                synchronized(lock) { flushRequestedWhileBusy = false }
                drainOutbox()
            } while (synchronized(lock) { flushRequestedWhileBusy })
        } finally {
            synchronized(lock) { flushingOutbox = false }
        }
    }

    /// One drain pass: sends every eligible queued row FIFO, stopping on the
    /// first transport failure (rows stay queued for the next connection's
    /// flush). [JournalStore.outboxMarkAttempt] runs BEFORE the write:
    /// delivery-confirmation deletes only attempted rows, and marking after a
    /// successful write would race the journal frame (a frame applied before
    /// the mark would skip the delete, and the dedup'd resend gets no fresh
    /// frame, so the row would never clear).
    private suspend fun drainOutbox() {
        while (true) {
            val connection = synchronized(lock) { liveConnection } ?: return
            val rows = runCatching { store.outboxPending() }.getOrDefault(emptyList())
            val next = rows.firstOrNull {
                synchronized(lock) { !sentOnThisConnection.contains(it.localID) }
            } ?: return
            // If the mark can't be persisted, don't send: confirmation-delete
            // and echo suppression only match rows with attempts > 0, so a
            // send that goes out unmarked leaves a permanent ghost "queued"
            // echo beside the delivered message. The row stays queued for a
            // later flush instead (bugbot "Silent markAttempt breaks
            // confirmation").
            if (runCatching { store.outboxMarkAttempt(next.localID) }.isFailure) {
                MatronDebug.breadcrumb("outbox flush stopped — markAttempt write failed for ${next.localID}")
                return
            }
            try {
                connection.send(ClientOp.Send(next.convoID, next.body, next.localID))
                synchronized(lock) {
                    sentOnThisConnection.add(next.localID)
                    sendOrderThisConnection.add(next.localID)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                MatronDebug.breadcrumb("outbox flush stopped — socket write failed: $e")
                return
            }
        }
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
        runCatching { store.replaceAgents(snapshot.agents) }
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

    override val stateStream: StateFlow<SyncConnectionState> get() = _state.asStateFlow()

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
    override fun newConversations(): Flow<String> = callbackFlow {
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
                workdir = update.workdir ?: held.workdir,
                vitals = update.vitals ?: held.vitals,
                modelOptions = update.modelOptions ?: held.modelOptions,
                effortLevels = update.effortLevels ?: held.effortLevels,
                // `Cleared` is a value, not null, so this `?:` carries the
                // clear into the cache rather than skipping it as an absent
                // field — a client attaching after a restart must not be
                // replayed the level the bridge just disowned.
                effort = update.effort ?: held.effort,
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
        val reply = when (val outcome = response.outcome) {
            is RPCResponse.Outcome.Success -> RPCReply.Ok(outcome.result)
            is RPCResponse.Outcome.Failure -> RPCReply.Failure(outcome.code ?: "unknown", outcome.detail)
        }
        pending.continuation.resumeWith(Result.success(reply))
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
                // Fresh socket: everything unconfirmed is eligible to resend
                // once (idem-dedup'd server-side), including messages queued
                // while offline. Launched as a child job so the frame loop
                // below starts consuming immediately.
                synchronized(lock) {
                    sentOnThisConnection.clear()
                    sendOrderThisConnection.clear()
                    mediaSendsThisConnection.clear()
                }
                scope.launch { flushOutbox() }
                // Behind the head: the backlog replay is about to stream in.
                // Surface CatchingUp (hosts render "Loading messages…") and
                // batch the replay below instead of committing per frame.
                var caughtUp = store.cursor() >= headSeq
                if (caughtUp) setState(SyncConnectionState.Running)
                else setState(SyncConnectionState.CatchingUp)

                val watchdog = launchWatchdog(connection)
                // Reconnect-replay buffer (port of matron-apple #85). During
                // catch-up each journal frame previously got its own Room
                // transaction — and each commit re-triggered every observer —
                // so history load was O(backlog) commits + requeries. Journal
                // frames buffer here and land REPLAY_BATCH_SIZE at a time in
                // one transaction. Live frames (after caughtUp) keep the
                // per-frame path: latency matters more than throughput there.
                val replayBuffer = mutableListOf<JournalEvent>()
                // Set when a flush ITSELF failed (store write error): the
                // teardown salvage below must not immediately retry a batch
                // the store just refused — reconnect-from-cursor owns that.
                var replayFlushFailed = false
                suspend fun drainReplay(connection: JournalConnection, appliedSinceAck: Long): Long {
                    val next = try {
                        flushReplay(replayBuffer, connection, appliedSinceAck)
                    } catch (e: Throwable) {
                        replayFlushFailed = true
                        throw e
                    }
                    if (!caughtUp && store.cursor() >= headSeq) {
                        caughtUp = true
                        setState(SyncConnectionState.Running)
                    }
                    return next
                }
                var appliedSinceAck = 0L
                try {
                    connection.frames().collect { frame ->
                        if (!caughtUp && frame is ServerFrame.Journal) {
                            replayBuffer += frame.event
                            // The replay is contiguous from our cursor to (at
                            // least) headSeq, so a frame at/past headSeq is
                            // the end of the backlog — flush without waiting
                            // for a full batch.
                            if (replayBuffer.size >= REPLAY_BATCH_SIZE || frame.event.seq >= headSeq) {
                                appliedSinceAck = drainReplay(connection, appliedSinceAck)
                            }
                        } else {
                            // Any interleaved non-journal frame flushes first
                            // so downstream consumers never observe it ahead
                            // of journal rows that preceded it on the wire.
                            if (replayBuffer.isNotEmpty()) {
                                appliedSinceAck = drainReplay(connection, appliedSinceAck)
                            }
                            appliedSinceAck = handleFrame(frame, connection, headSeq, appliedSinceAck)
                        }
                    }
                    // Socket ended with a partial batch pending: land it now.
                    if (replayBuffer.isNotEmpty()) {
                        appliedSinceAck = drainReplay(connection, appliedSinceAck)
                    }
                } catch (e: SnapshotRequiredExit) {
                    // Mirror already wiped in handleFrame; fall through to
                    // reconnect. The buffer is deliberately dropped — its
                    // frames predate the wipe.
                } catch (e: CancellationException) {
                    // endSync — dropping the buffer is safe: the cursor never
                    // advanced past those frames, so they replay next start.
                    throw e
                } catch (e: Throwable) {
                    // Socket died mid-replay: land what already arrived
                    // (matron-apple #85's teardown-flush lesson). Without
                    // this, a connection that never survives long enough to
                    // hit a flush trigger makes zero cursor progress and the
                    // replay restarts from scratch every reconnect — a
                    // livelock on flaky links. Skipped when the flush itself
                    // was what failed: reconnect-from-cursor retries that.
                    if (!replayFlushFailed && replayBuffer.isNotEmpty()) {
                        runCatching { appliedSinceAck = drainReplay(connection, appliedSinceAck) }
                    }
                    throw e
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

    /// Lands a buffered replay batch: outbox media confirms first (same order
    /// as the per-frame path), then one [JournalStore.applyJournalBatch]
    /// transaction, then search indexing for exactly the applied events.
    /// `publishNewConversation` is deliberately absent — the per-frame path
    /// only publishes while `isRunningState()`, which is never true mid-replay,
    /// so the batch path matches by construction. Clears [buffer] on success;
    /// a thrown store write leaves the cursor untouched and propagates to the
    /// reconnect path, identical to the per-frame failure shape.
    private suspend fun flushReplay(
        buffer: MutableList<JournalEvent>,
        connection: JournalConnection,
        appliedSinceAck: Long,
    ): Long {
        if (buffer.isEmpty()) return appliedSinceAck
        for (event in buffer) {
            if (event.sender == ownSender &&
                (event.type == JournalEventType.FILE || event.type == JournalEventType.IMAGE)
            ) {
                event.payload.stringOrNull("blob_ref")?.let { confirmMediaSend(it) }
            }
        }
        val applied = store.applyJournalBatch(buffer.toList())
        buffer.clear()
        applied.forEach { indexForSearch(it) }
        var count = appliedSinceAck + applied.size
        if (count >= 50) {
            runCatching { connection.send(ClientOp.Ack(store.cursor())) }
            count = 0
        }
        return count
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
                // An own media frame confirms that send's delivery: retire its
                // rejection-FIFO slot. In-order delivery means any error frame
                // still to come belongs to a newer write.
                if (event.sender == ownSender &&
                    (event.type == JournalEventType.FILE || event.type == JournalEventType.IMAGE)
                ) {
                    event.payload.stringOrNull("blob_ref")?.let { confirmMediaSend(it) }
                }
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
            is ServerFrame.Error -> {
                // Correlated RPC errors resume their waiter; a rejected send op
                // fails its outbox row; other post-hello control frames are
                // advisory.
                val requestID = frame.requestID
                when {
                    requestID != null -> failRPC(requestID, frame.code, frame.detail)
                    frame.ref == "send" -> handleSendRejected(frame.code, frame.detail)
                }
            }
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
                    .onFailure { MatronDebug.breadcrumb("snapshot_required: store.wipe failed: $it") }
                throw SnapshotRequiredExit()
            }
            is ServerFrame.DeviceMeta -> {
                // A device was renamed elsewhere — patch the local roster so
                // open chat lists relabel their chips without waiting for the
                // next snapshot.
                runCatching { store.renameAgent(frame.id, frame.name) }
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
        store.replaceAgents(snapshot.agents)
        // Cold snapshot means the events between the old cursor and the
        // snapshot head were never live-indexed, so any persisted "backfill
        // complete" flags may now hide head-side holes. Reset the bookkeeping
        // (messages stay indexed) so the backfill sweep re-walks every
        // conversation from its head. Best-effort: a failed reset just leaves
        // search coverage where it was. Ported from matron-apple's
        // `JournalSyncEngine` cold-start reset.
        search?.let { runCatching { it.resetBackfill() } }
    }

    /// Production liveness rides OkHttp's protocol-level `pingInterval`
    /// (see OkHttpWebSocketConnector.defaultClient): missing pongs fail the
    /// socket, `receiveText` throws, and the run loop reconnects. This
    /// watchdog is the app-level backstop for transports whose `ping()` does
    /// a real round-trip (the test fake; a future non-OkHttp transport) —
    /// on OkHttp `ping()` is a no-op and never trips it.
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
        val body = event.previewText()
        if (body.isNullOrEmpty()) return
        scope.launch {
            runCatching {
                search.index(event.convoID, event.seq.toString(), event.sender, event.ts, body)
            }
        }
    }
}
