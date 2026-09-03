package chat.matron.android.viewmodels

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/// A box's capacity as it was last observed, paired with the moment it was
/// captured (epoch ms). The age is what lets a row say how old its numbers
/// are instead of presenting a week-old percentage as current.
data class CachedBoxCapacity(val capacity: BoxCapacity, val capturedAtMs: Long)

/// Last-known capacity per agent box, outliving both the view model and the
/// app process: the host now suspends idle boxes, so the chooser has to be
/// able to show which *offline* box has quota left without being able to ask
/// it. Keyed by agent device id, which is stable within a journal.
///
/// Injected so tests run against an in-memory double; production uses
/// [KeyValueBoxCapacityCache]. Display-only, like everything else in the
/// capacity path — a cache miss costs a quieter row, never a blocked pick.
/// Port of matron-apple's `BoxCapacityCaching` (#164).
interface BoxCapacityCaching {
    /// Every cached box. Callers decide what is too old to show.
    fun loadAll(): Map<Long, CachedBoxCapacity>

    /// Records a box's freshly parsed capacity, replacing any earlier one.
    fun save(capacity: BoxCapacity, agentID: Long, capturedAtMs: Long)

    /// Drops every box outside [keeping]. The roster is the authority on
    /// which boxes still exist — an unpaired box would otherwise sit in the
    /// cache forever, since nothing will ever refresh or supersede it.
    fun prune(keeping: Set<Long>)
}

/// [KeyValueStore]-backed [BoxCapacityCaching]: the whole map is one JSON
/// blob under a single key, rewritten on every mutation (a fleet is a handful
/// of boxes, so there is nothing to gain from finer granularity).
///
/// One blob per account. Namespacing by user id is not optional bookkeeping:
/// agent device ids are only unique **within a journal**, so two accounts on
/// the same device both allocate boxes 1, 2, 3. A shared key would render
/// account A's account email and quota on account B's box of the same number
/// — the separation `signOut()` already enforces by wiping the store, which is
/// why it removes this key too ([removeAll]).
class KeyValueBoxCapacityCache(userID: String, private val store: KeyValueStore) : BoxCapacityCaching {
    private val key = defaultsKey(userID)

    override fun loadAll(): Map<Long, CachedBoxCapacity> {
        val raw = store.getString(key) ?: return emptyMap()
        val payload = runCatching { json.decodeFromString(Payload.serializer(), raw) }.getOrNull() ?: return emptyMap()
        if (payload.version != Payload.CURRENT_VERSION) return emptyMap()
        // Last one wins on a corrupt blob carrying the same id twice — degrade, don't fail.
        return payload.boxes.associate { it.id to it.decoded() }
    }

    override fun save(capacity: BoxCapacity, agentID: Long, capturedAtMs: Long) {
        write(loadAll() + (agentID to CachedBoxCapacity(capacity, capturedAtMs)))
    }

    override fun prune(keeping: Set<Long>) {
        write(loadAll().filterKeys { it in keeping })
    }

    private fun write(entries: Map<Long, CachedBoxCapacity>) {
        // Sorted so the stored blob is stable across writes that changed
        // nothing — map order is not.
        val boxes = entries.entries.sortedBy { it.key }.map { (id, entry) -> Payload.Box.from(id, entry) }
        store.setString(key, json.encodeToString(Payload.serializer(), Payload(Payload.CURRENT_VERSION, boxes)))
    }

    /// The on-disk shape, deliberately its own type rather than making
    /// [BoxCapacity] serializable: that model mirrors the bridge's wire JSON
    /// (snake_case keys, every block optional), so a generated serializer
    /// would read nothing the bridge actually sends while looking like it
    /// could. Bumping [CURRENT_VERSION] retires every older payload.
    @Serializable
    private data class Payload(val version: Int, val boxes: List<Box>) {
        @Serializable
        data class Box(
            val id: Long,
            val capturedAt: Long,
            val liveSessions: Int?,
            val accountEmail: String?,
            val lines: List<Line>,
        ) {
            fun decoded() = CachedBoxCapacity(
                BoxCapacity(liveSessions, lines.map { it.decoded() }, accountEmail),
                capturedAt,
            )

            companion object {
                fun from(id: Long, entry: CachedBoxCapacity) = Box(
                    id = id,
                    capturedAt = entry.capturedAtMs,
                    liveSessions = entry.capacity.liveSessions,
                    accountEmail = entry.capacity.accountEmail,
                    lines = entry.capacity.limitLines.map { Line(it.id, it.label, it.percent, it.resetsAt) },
                )
            }
        }

        @Serializable
        data class Line(val id: String, val label: String, val percent: Int, val resetsAt: Long?) {
            fun decoded() = LimitLine(id, label, percent, resetsAt)
        }

        companion object {
            const val CURRENT_VERSION = 1
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun defaultsKey(userID: String) = "newChat.boxCapacityCache.$userID"

        /// Drops one account's cache, leaving every other account's alone.
        fun removeAll(userID: String, store: KeyValueStore) = store.remove(defaultsKey(userID))
    }
}
