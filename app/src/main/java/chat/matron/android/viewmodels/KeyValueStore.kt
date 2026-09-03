package chat.matron.android.viewmodels

import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/// Small injectable key-value store standing in for the Swift originals'
/// `UserDefaults`. Only the operations the view-model layer actually needs are
/// exposed (an ordered string list for `RecentStartFolders`, plus the flag and
/// counter [AppLockController] persists).
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

    /// The flag stored under [key], or [default] when unset.
    fun getBoolean(key: String, default: Boolean = false): Boolean

    fun setBoolean(key: String, value: Boolean)

    /// The string stored under [key], or `null` when unset.
    fun getString(key: String): String?

    fun setString(key: String, value: String)

    /// Removes [key] entirely — `UserDefaults.removeObject(forKey:)`, so a
    /// store whose last value was cleared reads as unset, not as an empty
    /// serialisation.
    fun remove(key: String)

    /// The integer stored under [key], or `null` when unset.
    ///
    /// Deliberately nullable rather than `getInt(key, default)`: the Swift
    /// original tripped over `UserDefaults.integer(forKey:)` returning `0` for
    /// both "unset" and a genuinely stored `0`, and had to probe
    /// `object(forKey:) != nil` first to tell them apart. `AppLockTimeout`'s
    /// `Immediately` case IS `0`, so that ambiguity would silently turn an
    /// unset timeout into "lock immediately". A nullable read makes the
    /// distinction unrepresentable instead of merely handled.
    fun getIntOrNull(key: String): Int?

    fun setInt(key: String, value: Int)
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

    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /// `contains` first, because `getInt`'s default can't be distinguished from
    /// a stored value — see the interface doc.
    override fun getIntOrNull(key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null

    override fun setInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    private companion object {
        val json = Json
        val listSerializer = ListSerializer(String.serializer())
    }
}
