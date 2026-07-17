package chat.matron.android.viewmodels

import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/// Small injectable key-value store standing in for the Swift originals'
/// `UserDefaults`. Only the operations the view-model layer actually needs are
/// exposed (an ordered string list, which is all `RecentStartFolders` uses).
///
/// - Production: [SharedPreferencesKeyValueStore] serialises the ordered list as
///   a JSON array string so order survives (SharedPreferences' native
///   `StringSet` is unordered).
/// - Tests: [chat.matron.android.viewmodels.InMemoryKeyValueStore] keeps values
///   in a map so the contract is exercisable without an Android context.
interface KeyValueStore {
    /// The ordered string list stored under [key], or `null` when unset —
    /// matching `UserDefaults.stringArray(forKey:)` returning `[String]?`.
    fun getStringList(key: String): List<String>?

    /// Persists an ordered string list under [key].
    fun setStringList(key: String, value: List<String>)
}

/// `SharedPreferences`-backed [KeyValueStore]. Order-preserving via JSON.
class SharedPreferencesKeyValueStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {

    override fun getStringList(key: String): List<String>? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrNull()
    }

    override fun setStringList(key: String, value: List<String>) {
        prefs.edit().putString(key, json.encodeToString(listSerializer, value)).apply()
    }

    private companion object {
        val json = Json
        val listSerializer = ListSerializer(String.serializer())
    }
}
