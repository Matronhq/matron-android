package chat.matron.android.viewmodels

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// This device's copy of the user's Coordinator conversation (app shell,
/// spec §5b). Since the Coordinator redesign (spec §3a) the journal holds
/// the setting and `CoordinatorSync` mirrors it here: views read [convoID]
/// (a `StateFlow`, so the shell's tab and the Settings row follow a change
/// at once), and only `CoordinatorSync` writes it — user picks go through
/// `CoordinatorSync.set`. The cache is what shows before the first connect.
/// Stored in the app's plain preference store under
/// `coordinator.convoID.<userID>` — the same per-user key shape as
/// [KeyValueBoxCapacityCache]. Ported from matron-apple's
/// `CoordinatorSetting`.
class CoordinatorSetting(userID: String, private val store: KeyValueStore) {
    private val key = defaultsKey(userID)
    private val migratedDefaultsKey = migratedKey(userID)
    private val _convoID = MutableStateFlow(store.getString(key)?.takeIf { it.isNotEmpty() })
    val convoID: StateFlow<String?> = _convoID.asStateFlow()

    /// Writes (or, with `null`, clears) the cached coordinator conversation.
    fun set(convoID: String?) {
        if (convoID.isNullOrEmpty()) store.remove(key) else store.setString(key, convoID)
        _convoID.value = convoID?.takeIf { it.isNotEmpty() }
    }

    /// Set once this device has reconciled with a journal that knows the
    /// setting, so the first-launch "push my cached id" rule runs once.
    var migrated: Boolean
        get() = store.getBoolean(migratedDefaultsKey)
        set(value) = store.setBoolean(migratedDefaultsKey, value)

    /// What a reconcile does with the journal's answer.
    sealed interface Reconcile {
        /// Take this value into the cache (null clears it).
        data class Adopt(val convoID: String?) : Reconcile
        /// `PUT` this cached id: the journal has none and this device is the
        /// first to upgrade (a pick made while the setting was device-local).
        data class Push(val convoID: String) : Reconcile
    }

    companion object {
        fun defaultsKey(userID: String): String = "coordinator.convoID.$userID"

        fun migratedKey(userID: String): String = "coordinator.migrated.$userID"

        /// The spec §3a rule. The journal's value always wins; a cached id
        /// is pushed only before this device's first successful reconcile.
        fun reconcile(journal: String?, cached: String?, migrated: Boolean): Reconcile = when {
            journal != null -> Reconcile.Adopt(journal)
            !migrated && !cached.isNullOrEmpty() -> Reconcile.Push(cached)
            else -> Reconcile.Adopt(null)
        }

        /// Removes both keys outright (sign-out teardown of per-account state).
        fun clear(userID: String, store: KeyValueStore) {
            store.remove(defaultsKey(userID))
            store.remove(migratedKey(userID))
        }
    }
}
