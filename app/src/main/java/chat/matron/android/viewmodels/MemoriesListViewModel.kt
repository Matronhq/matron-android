package chat.matron.android.viewmodels

import chat.matron.android.journal.MemoriesRefreshOutcome
import chat.matron.android.journal.MemoriesSyncing
import chat.matron.android.models.Memory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Backs the Memories screen's list (spec 2026-09-27 memories, "Apps").
/// Same shape as [MissionsListViewModel]: the screen starts it when shown
/// and stops it when it leaves, and the first start fetches the list — a
/// journal without `/memories` answers "unsupported" here and nowhere else.
class MemoriesListViewModel(
    private val sync: MemoriesSyncing,
    private val scope: CoroutineScope,
) {
    private val _memories = MutableStateFlow<List<Memory>>(emptyList())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    private val _isSupported = MutableStateFlow<Boolean?>(null)
    val isSupported: StateFlow<Boolean?> = _isSupported.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var listJob: Job? = null
    private var supportedJob: Job? = null
    private var refreshJob: Job? = null

    fun start() {
        stop()
        listJob = scope.launch { sync.memories.collect { _memories.value = it } }
        supportedJob = scope.launch { sync.isSupported.collect { _isSupported.value = it } }
        refreshJob = scope.launch { refresh() }
    }

    fun stop() {
        listJob?.cancel(); listJob = null
        supportedJob?.cancel(); supportedJob = null
        refreshJob?.cancel(); refreshJob = null
    }

    suspend fun refresh() {
        _isRefreshing.value = true
        try {
            // A failed refresh keeps the last list; the banner is the only
            // visible consequence, and a later success clears it.
            when (val outcome = sync.refresh()) {
                MemoriesRefreshOutcome.Succeeded -> _error.value = null
                is MemoriesRefreshOutcome.Failed -> _error.value = outcome.message
                MemoriesRefreshOutcome.Unsupported, MemoriesRefreshOutcome.Stopped -> Unit
            }
        } finally {
            _isRefreshing.value = false
        }
    }

    fun dismissError() {
        _error.value = null
    }
}
