package chat.matron.android.events

import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonObject

/// The `routine` journal event (protocol: "Coordinator routines → Marker
/// event"), appended into the Coordinator conversation on every routine
/// create / update / delete / fire. The journal means it as the apps' cue to
/// refetch `GET /routines`; there is no Routines screen yet, so here it is
/// only a one-line timeline row — which routine fired and whether it reached
/// the Coordinator. An undelivered fire leaves no other trace in the
/// transcript, so its reason is always on the row. Ported from matron-apple's
/// `Events/RoutineMarkerEvent.swift`.
data class RoutineMarkerEvent(
    val routineID: String,
    val name: String,
    val action: Action,
    /// `saved` only: a create rather than an edit.
    val created: Boolean = false,
    /// `saved`/`deleted`: `user`, or `agent` — only the Coordinator may write.
    val by: String? = null,
    /// `fired` only: `applied now`, `applied deferred`, `failed <code>` or
    /// `no_coordinator` (journal `src/routines-sweep.js`).
    val outcome: String? = null,
) {
    enum class Action(val wire: String) {
        SAVED("saved"), DELETED("deleted"), FIRED("fired");

        companion object {
            fun fromWire(value: String?): Action? = entries.firstOrNull { it.wire == value }
        }
    }

    /// Whether a fire never reached the Coordinator.
    val isUndelivered: Boolean
        get() {
            val o = outcome ?: return false
            return action == Action.FIRED && (o == "missed" || o == "no_coordinator" || o.startsWith("failed"))
        }

    /// The row's one line: "⏰ Routine fired · daily-sweep", "⚠️ Routine not
    /// delivered · daily-sweep — agent_unreachable", "⏰ You created a routine
    /// · deploy-window".
    val text: String
        get() {
            val icon = if (isUndelivered) "⚠️" else "⏰"
            val line = when (action) {
                Action.SAVED, Action.DELETED -> {
                    val who = if (by == "user") "You" else "Coordinator"
                    val verb = if (action == Action.DELETED) "deleted" else if (created) "created" else "updated"
                    "$who $verb a routine · $name"
                }
                Action.FIRED -> {
                    val o = outcome?.trim() ?: ""
                    when {
                        o == "missed" -> "Routine missed · $name"
                        o == "no_coordinator" -> "Routine not delivered · $name — no Coordinator box"
                        o.startsWith("failed") -> {
                            val code = o.removePrefix("failed").trim()
                            "Routine not delivered · $name" + if (code.isEmpty()) "" else " — $code"
                        }
                        o == "applied deferred" -> "Routine fired · $name — queued for the next idle point"
                        else -> "Routine fired · $name"
                    }
                }
            }
            return "$icon $line"
        }

    companion object {
        fun parse(payload: JsonObject): RoutineMarkerEvent? {
            val routineID = payload.stringOrNull("routine_id")?.takeIf { it.isNotEmpty() } ?: return null
            val name = payload.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val action = Action.fromWire(payload.stringOrNull("action")) ?: return null
            return RoutineMarkerEvent(
                routineID = routineID, name = name, action = action,
                created = payload.boolOrNull("created") ?: false,
                by = payload.stringOrNull("by"),
                outcome = payload.stringOrNull("outcome"),
            )
        }
    }
}
