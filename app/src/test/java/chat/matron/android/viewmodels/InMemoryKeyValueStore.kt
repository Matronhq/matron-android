package chat.matron.android.viewmodels

/// In-memory [KeyValueStore] double for tests — the analogue of the Swift
/// suite's throwaway `UserDefaults(suiteName:)`. Stores a defensive copy so a
/// caller mutating its list can't reach back into the store.
class InMemoryKeyValueStore : KeyValueStore {
    private val storage = mutableMapOf<String, List<String>>()

    override fun getStringList(key: String): List<String>? = storage[key]?.toList()

    override fun setStringList(key: String, value: List<String>) {
        storage[key] = value.toList()
    }
}
