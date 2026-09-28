package chat.matron.android.journal

import chat.matron.android.events.MemoryMarkerEvent
import chat.matron.android.models.Memory
import chat.matron.android.models.SyncConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// What a [MemoriesSync.refresh] pass did. [Unsupported] is a real answer
/// from a journal predating `/memories` (404), not a transport fault.
sealed interface MemoriesRefreshOutcome {
    data object Succeeded : MemoriesRefreshOutcome
    data object Unsupported : MemoriesRefreshOutcome
    /// `stop()` landed mid-pass (sign-out / teardown).
    data object Stopped : MemoriesRefreshOutcome
    data class Failed(val message: String) : MemoriesRefreshOutcome
}

/// The surface the memory view models depend on. [MemoriesSync] implements
/// it; tests fake it.
interface MemoriesSyncing {
    /// The user's memories, sorted by name. Empty until the first refresh.
    val memories: StateFlow<List<Memory>>

    /// Tri-state like `MissionsSyncing.isSupported`: `null` until the first
    /// list fetch answers, `false` once `GET /memories` has 404'd, `true`
    /// once a fetch has succeeded. A transport failure never flips it.
    val isSupported: StateFlow<Boolean?>

    suspend fun refresh(): MemoriesRefreshOutcome
    suspend fun save(name: String, write: MemoryWrite): MemorySave
    suspend fun delete(name: String): Memory
}

/// The in-memory memory list (spec 2026-09-27 memories, "Apps"). There is
/// no Room cache: at most 200 short rows, fetched when the screen shows.
/// Nothing is fetched until a screen asks — against a journal that predates
/// `/memories` the 404 must stay on the Memories screen, never surface on
/// the Missions tab. Once loaded, two triggers refetch: a `memory` marker
/// (the journal appends the same change to the writer's AND the
/// Coordinator's conversation, so a pair costs one coalesced fetch) and a
/// reconnect. Writes go straight to the journal and then refetch the list,
/// so row order and any concurrent agent edits stay authoritative.
class MemoriesSync(
    private val api: MemoriesProviding,
    private val markers: () -> Flow<Pair<String, MemoryMarkerEvent>>,
    private val connectionStates: () -> Flow<SyncConnectionState>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MemoriesSyncing {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lock = Any()

    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    override val memories: StateFlow<List<Memory>> = _memories.asStateFlow()
    private val _isSupported = MutableStateFlow<Boolean?>(null)
    override val isSupported: StateFlow<Boolean?> = _isSupported.asStateFlow()

    // Guarded by `lock`.
    private var markerJob: Job? = null
    private var stateJob: Job? = null
    private var inFlightRefresh: Deferred<MemoriesRefreshOutcome>? = null
    /// A trigger joined a fetch that was already on the wire, so its answer
    /// may predate the change: one follow-up fetch runs when it completes.
    /// One flag, not a queue — a burst of markers costs one more GET.
    private var rerunPending = false
    private var stopped = false
    /// Set by the first refresh anyone asked for: markers and reconnects
    /// refetch only a list someone is (or was) looking at.
    private var loaded = false

    fun start() {
        synchronized(lock) {
            stopped = false
            if (markerJob != null) return
            markerJob = scope.launch {
                // Launched, not awaited: a burst of markers (the journal writes
                // one per conversation) then all join the one fetch on the
                // wire instead of replaying serially, one GET each.
                markers().collect { if (synchronized(lock) { loaded }) launch { refresh() } }
            }
            stateJob = scope.launch {
                connectionStates().collect { state ->
                    if (state is SyncConnectionState.Running && synchronized(lock) { loaded }) refresh()
                }
            }
        }
    }

    /// Cancels the marker/state jobs and any in-flight refresh and joins
    /// them, so a sign-out can never race a resuming fetch.
    suspend fun stop() {
        val toJoin: List<Job>
        synchronized(lock) {
            stopped = true
            toJoin = listOfNotNull(markerJob, stateJob, inFlightRefresh)
            markerJob = null; stateJob = null
        }
        toJoin.forEach { it.cancel() }
        toJoin.forEach { it.join() }
    }

    /// Full `GET /memories`. Joiners of a coalesced run get the SAME
    /// outcome, and the run is re-done once afterwards so a change that
    /// landed mid-fetch is never missed (CodeRabbit, #81).
    override suspend fun refresh(): MemoriesRefreshOutcome {
        val run: Deferred<MemoriesRefreshOutcome> = synchronized(lock) {
            loaded = true
            inFlightRefresh?.also { rerunPending = true } ?: run {
                lateinit var self: Deferred<MemoriesRefreshOutcome>
                self = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        refreshOnce()
                    } finally {
                        val rerun = synchronized(lock) {
                            if (inFlightRefresh === self) inFlightRefresh = null
                            val r = rerunPending && !stopped
                            rerunPending = false
                            r
                        }
                        if (rerun) scope.launch { refresh() }
                    }
                }
                inFlightRefresh = self
                self
            }
        }
        run.start()
        return try {
            run.await()
        } catch (cancel: CancellationException) {
            currentCoroutineContext().ensureActive()
            MemoriesRefreshOutcome.Stopped
        }
    }

    private suspend fun refreshOnce(): MemoriesRefreshOutcome {
        return try {
            val list = api.listMemories()
            if (synchronized(lock) { stopped } || !currentCoroutineContext().isActive) return MemoriesRefreshOutcome.Stopped
            _memories.value = list.sortedBy { it.name }
            _isSupported.value = true
            MemoriesRefreshOutcome.Succeeded
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (notFound: JournalApiError.NotFound) {
            _isSupported.value = false
            MemoriesRefreshOutcome.Unsupported
        } catch (error: Throwable) {
            MemoriesRefreshOutcome.Failed(error.message ?: error.toString())
        }
    }

    override suspend fun save(name: String, write: MemoryWrite): MemorySave {
        val saved = api.saveMemory(name, write)
        refresh()
        return saved
    }

    override suspend fun delete(name: String): Memory {
        val gone = api.deleteMemory(name)
        _memories.value = _memories.value.filterNot { it.name == name }
        refresh()
        return gone
    }
}
