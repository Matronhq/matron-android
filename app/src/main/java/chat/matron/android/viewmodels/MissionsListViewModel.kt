package chat.matron.android.viewmodels

import chat.matron.android.journal.MissionsRefreshOutcome
import chat.matron.android.journal.MissionsStoreReading
import chat.matron.android.journal.MissionsSyncing
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionState
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Backs the Missions tab's list (spec: Apps → Missions tab). Open missions
/// sorted by latest milestone; closed ones in a collapsed section. Ported
/// from matron-apple's `MissionsListViewModel`; [scope] replaces the Swift
/// original's `@MainActor` tasks (the shell's session scope).
class MissionsListViewModel(
    private val store: MissionsStoreReading,
    private val sync: MissionsSyncing,
    private val scope: CoroutineScope,
) {
    private val _open = MutableStateFlow<List<Mission>>(emptyList())
    val open: StateFlow<List<Mission>> = _open.asStateFlow()

    private val _closed = MutableStateFlow<List<Mission>>(emptyList())
    val closed: StateFlow<List<Mission>> = _closed.asStateFlow()

    /// Tri-state, exactly like `DecisionsListModel.isSupported`: `null`
    /// until the first answer lands, `false` once the journal has 404'd
    /// `GET /missions`, `true` once a list fetch has actually succeeded.
    /// The shell hides the Missions tab until this is `true` and clamps
    /// off it when it flips `false`.
    private val _isSupported = MutableStateFlow<Boolean?>(null)
    val isSupported: StateFlow<Boolean?> = _isSupported.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /// The tab badge: how many items across every open mission are waiting
    /// on the user. Kept in lockstep with [open] (not a `stateIn`
    /// derivation: that would pin a collector to [scope] for the VM's life).
    private val _needsYouTotal = MutableStateFlow(0)
    val needsYouTotal: StateFlow<Int> = _needsYouTotal.asStateFlow()

    private var missionsJob: Job? = null
    private var supportedJob: Job? = null
    private var refreshJob: Job? = null

    fun start() {
        stop()
        missionsJob = scope.launch {
            store.missionsFlow(null).collect { missions ->
                val (open, closed) = sections(missions)
                _open.value = open
                _closed.value = closed
                _needsYouTotal.value = open.sumOf { it.needsYou }
            }
        }
        supportedJob = scope.launch { sync.isSupported.collect { _isSupported.value = it } }
        refreshJob = scope.launch { refresh() }
    }

    fun stop() {
        missionsJob?.cancel(); missionsJob = null
        supportedJob?.cancel(); supportedJob = null
        refreshJob?.cancel(); refreshJob = null
    }

    suspend fun refresh() {
        _isRefreshing.value = true
        try {
            // A failed refresh leaves the cached tables alone; the banner is
            // the only visible consequence (spec, Error handling). A later
            // success clears that banner instead of leaving it stuck.
            when (val outcome = sync.refresh()) {
                MissionsRefreshOutcome.Succeeded -> _error.value = null
                is MissionsRefreshOutcome.Failed -> _error.value = outcome.message
                MissionsRefreshOutcome.Unsupported, MissionsRefreshOutcome.Stopped -> Unit
            }
        } finally {
            _isRefreshing.value = false
        }
    }

    fun dismissError() {
        _error.value = null
    }

    companion object {
        /// Open: `lastMilestoneAt` desc with never-checkpointed missions
        /// last, `createdAt` desc as the tiebreak — the journal's own order,
        /// restated here so a cache assembled from several fetches still
        /// agrees with it. Closed: newest close first.
        fun sections(missions: List<Mission>): Pair<List<Mission>, List<Mission>> {
            val open = missions.filter { it.state == MissionState.OPEN }.sortedWith(
                compareByDescending<Mission> { it.lastMilestoneAt != null }
                    .thenByDescending { it.lastMilestoneAt ?: Instant.MIN }
                    .thenByDescending { it.createdAt },
            )
            val closed = missions.filter { it.state == MissionState.CLOSED }
                .sortedByDescending { it.closedAt ?: Instant.MIN }
            return open to closed
        }
    }
}
