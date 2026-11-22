package chat.matron.android.journal

import kotlinx.serialization.json.JsonObject

/// What the Boxes screen reads of `GET /roster`: which disconnected boxes the
/// journal can start again, and each box's top-level conversations that are
/// still open. Port of matron-apple's `RosterSnapshot` (the slice the Boxes
/// screen uses).
data class RosterSnapshot(
    /// Disconnected boxes the journal can wake. A disconnected box missing
    /// from this set is really offline, not asleep.
    val wakeableBoxes: Set<Long> = emptySet(),
    /// Open top-level conversations per box, in the journal's order.
    val sessionsByBox: Map<Long, List<RosterSession>> = emptyMap(),
) {
    companion object {
        /// Reads each conversation's `id`, `title`, `session_state`,
        /// `agent_device_id` and `last_ts`, and each agent's `wakeable`.
        /// Null without a `conversations` list (a malformed answer).
        fun decode(obj: JsonObject): RosterSnapshot? {
            val rows = obj.arrayOrNull("conversations")?.objects() ?: return null
            val sessions = linkedMapOf<Long, MutableList<RosterSession>>()
            for (row in rows) {
                val id = row.stringOrNull("id") ?: continue
                val state = row.stringOrNull("session_state")
                if (state != SessionState.RUNNING && state != "waiting") continue
                val box = row.longOrNull("agent_device_id") ?: continue
                sessions.getOrPut(box) { mutableListOf() } += RosterSession(
                    id = id,
                    title = row.stringOrNull("title") ?: "",
                    isRunning = state == SessionState.RUNNING,
                    lastActivityMs = row.longOrNull("last_ts"),
                )
            }
            val wakeable = obj.arrayOrNull("agents")?.objects().orEmpty()
                .filter { it.boolOrNull("wakeable") == true }
                .mapNotNull { it.longOrNull("device_id") }
                .toSet()
            return RosterSnapshot(wakeable, sessions)
        }
    }
}

/// One open conversation on a box, as the roster lists it.
data class RosterSession(
    val id: String,
    val title: String,
    /// True while a turn is running; false while it waits for its next one.
    val isRunning: Boolean,
    /// The conversation's last message (epoch ms); null when it has none.
    val lastActivityMs: Long?,
)
