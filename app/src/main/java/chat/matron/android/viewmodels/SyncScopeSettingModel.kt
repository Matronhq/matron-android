package chat.matron.android.viewmodels

import chat.matron.android.journal.SyncDayStats
import chat.matron.android.journal.SyncScope
import chat.matron.android.journal.SyncStatsRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/// Settings › Sync on this device: which [SyncScope] this device keeps. The
/// choice is saved on the device (never synced: one phone can follow For
/// you while a tablet keeps everything) and handed to the sync engine, which
/// reconnects under it.
class SyncScopeSettingModel(
    private val preferences: KeyValueStore,
    private val apply: suspend (SyncScope) -> Unit,
    private val scope: CoroutineScope,
    /// The day's sync counts, shown under the choice. `null` shows none.
    private val stats: SyncStatsRecorder? = null,
) {
    private val applying = Mutex()
    private val _days = MutableStateFlow(stats?.snapshot())
    /// Today's and yesterday's counts (yesterday `null` when nothing was
    /// recorded), or `null` without a recorder.
    val days: StateFlow<Pair<SyncDayStats, SyncDayStats?>?> = _days.asStateFlow()

    /// Reads the counts again (the screen calls this as it comes on screen).
    fun refreshStats() {
        _days.value = stats?.snapshot()
    }

    private val _selected = MutableStateFlow(SyncScope.stored(preferences))
    val selected: StateFlow<SyncScope> = _selected.asStateFlow()

    fun select(choice: SyncScope) {
        if (choice == _selected.value) return
        _selected.value = choice
        SyncScope.store(choice, preferences)
        scope.launch {
            // Applies the newest choice, one change at a time: a quick
            // second tap cannot be overtaken by the first's late apply.
            applying.withLock { apply(_selected.value) }
            refreshStats()
        }
    }
}
