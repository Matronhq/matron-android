package chat.matron.android.viewmodels

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// The designated coordinator conversation (app shell, spec §5b): one convo
/// id per signed-in journal user, stored in the app's plain preference
/// store under `coordinator.convoID.<userID>` — the same per-user key shape
/// as [KeyValueBoxCapacityCache]. `null` by default; clearing removes the
/// key. Nothing else about the conversation changes: it stays in the
/// Conversations list and opens from there as an ordinary chat. Ported from
/// matron-apple's `CoordinatorSetting`; [convoID] is a `StateFlow` (Apple
/// reads the key live through `@AppStorage`) so the shell's tab and the
/// Settings row follow a change at once — the shell holds ONE instance per
/// session and hands it to both.
class CoordinatorSetting(userID: String, private val store: KeyValueStore) {
    private val key = defaultsKey(userID)
    private val _convoID = MutableStateFlow(store.getString(key)?.takeIf { it.isNotEmpty() })
    val convoID: StateFlow<String?> = _convoID.asStateFlow()

    /// Sets (or, with `null`, clears) the coordinator conversation.
    fun set(convoID: String?) {
        if (convoID.isNullOrEmpty()) store.remove(key) else store.setString(key, convoID)
        _convoID.value = convoID?.takeIf { it.isNotEmpty() }
    }

    companion object {
        fun defaultsKey(userID: String): String = "coordinator.convoID.$userID"

        /// Removes the key outright (sign-out teardown of per-account state).
        fun clear(userID: String, store: KeyValueStore) = store.remove(defaultsKey(userID))
    }
}
