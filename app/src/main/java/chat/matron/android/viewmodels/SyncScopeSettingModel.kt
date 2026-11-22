package chat.matron.android.viewmodels

import chat.matron.android.journal.SyncScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Settings › Sync on this device: which [SyncScope] this device keeps. The
/// choice is saved on the device (never synced: one phone can follow For
/// you while a tablet keeps everything) and handed to the sync engine, which
/// reconnects under it.
class SyncScopeSettingModel(
    private val preferences: KeyValueStore,
    private val apply: suspend (SyncScope) -> Unit,
    private val scope: CoroutineScope,
) {
    private val _selected = MutableStateFlow(SyncScope.stored(preferences))
    val selected: StateFlow<SyncScope> = _selected.asStateFlow()

    fun select(choice: SyncScope) {
        if (choice == _selected.value) return
        _selected.value = choice
        SyncScope.store(choice, preferences)
        scope.launch { apply(choice) }
    }
}
