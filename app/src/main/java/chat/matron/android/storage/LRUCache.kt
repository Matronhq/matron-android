package chat.matron.android.storage

/// Tiny, ordered, fixed-capacity cache. Insertions and value-updates promote
/// the touched key to most-recently-used; once `count > limit`, the
/// least-recently-used entry is evicted. Reads do NOT promote (see below).
///
/// Ported from matron-apple's `LRUCache`. Backed by a `LinkedHashMap` (keys in
/// recency order, MRU last) — `n` is bounded by `limit`, so the O(n) recency
/// bookkeeping is cheap.
///
/// Reads are deliberately non-promoting: the Apple original made reads promote
/// via a `mutating get`, which caused an infinite render loop when the cache
/// lived inside an observable view-model (every read invalidated observers →
/// re-render → read again). Only writes touch recency, which keeps the cache
/// safe to read many times during a render while preserving the bounded
/// eviction invariant for matron's write-once-on-fetch access pattern.
///
/// Not thread-safe — callers provide their own isolation.
class LRUCache<K, V>(private val limit: Int) {
    init {
        require(limit > 0) { "LRU limit must be positive" }
    }

    // accessOrder = false: iteration/order is by insertion, and we manage
    // recency manually on write so that *reads* never reorder.
    private val map = LinkedHashMap<K, V>()

    val count: Int get() = map.size

    fun contains(key: K): Boolean = map.containsKey(key)

    /// Non-promoting read.
    operator fun get(key: K): V? = map[key]

    /// Insert/update ([value] non-null) promotes the key to MRU and evicts the
    /// LRU if over capacity; `null` removes the key.
    operator fun set(key: K, value: V?) {
        if (value == null) {
            map.remove(key)
            return
        }
        // Remove-then-reinsert so the key moves to the tail (MRU) on both
        // fresh inserts and updates.
        map.remove(key)
        map[key] = value
        while (map.size > limit) {
            val eldest = map.keys.iterator().next()
            map.remove(eldest)
        }
    }
}

/// Composite key for per-room timeline caching, shared by both app targets in
/// the Apple original. `data class` gives the equality + hashing the cache's
/// dictionary keying relies on.
data class TimelineCacheKey(val userID: String, val roomID: String)
