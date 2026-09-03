package chat.matron.android.viewmodels

/// In-memory [KeyValueStore] double for tests — the analogue of the Swift
/// suite's throwaway `UserDefaults(suiteName:)`. Stores a defensive copy so a
/// caller mutating its list can't reach back into the store.
class InMemoryKeyValueStore : KeyValueStore {
    private val storage = mutableMapOf<String, List<String>>()
    private val booleans = mutableMapOf<String, Boolean>()
    private val integers = mutableMapOf<String, Int>()

    override fun getStringList(key: String): List<String>? = storage[key]?.toList()

    override fun setStringList(key: String, value: List<String>) {
        storage[key] = value.toList()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = booleans[key] ?: default

    override fun setBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }

    private val strings = mutableMapOf<String, String>()

    override fun getIntOrNull(key: String): Int? = integers[key]

    override fun getString(key: String): String? = strings[key]

    override fun setString(key: String, value: String) {
        strings[key] = value
    }

    override fun remove(key: String) {
        storage.remove(key); booleans.remove(key); integers.remove(key); strings.remove(key)
    }

    override fun setInt(key: String, value: Int) {
        integers[key] = value
    }

    /// Every key ever written across all three maps — lets a test assert
    /// nothing was persisted at all (e.g. agent-spawn resolution, which is
    /// derived from timeline items, never the store).
    val allKeys: Set<String> get() = storage.keys + booleans.keys + integers.keys
}
