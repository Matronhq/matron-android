package chat.matron.android.viewmodels

/// In-memory [BoxCapacityCaching] for tests; records prune calls.
class InMemoryBoxCapacityCache(initial: Map<Long, CachedBoxCapacity> = emptyMap()) : BoxCapacityCaching {
    private val entries = initial.toMutableMap()
    val pruneCalls = mutableListOf<Set<Long>>()

    override fun loadAll(): Map<Long, CachedBoxCapacity> = entries.toMap()

    override fun save(capacity: BoxCapacity, agentID: Long, capturedAtMs: Long) {
        entries[agentID] = CachedBoxCapacity(capacity, capturedAtMs)
    }

    override fun prune(keeping: Set<Long>) {
        pruneCalls.add(keeping)
        entries.keys.retainAll(keeping)
    }
}
