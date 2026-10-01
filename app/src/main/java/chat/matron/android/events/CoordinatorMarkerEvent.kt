package chat.matron.android.events

import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonObject

/// The journal's `coordinator` event (Coordinator redesign spec §1a, §3e):
/// appended into the conversation that gained the role (`assigned`) and the
/// one that lost it (`released`). The timeline draws it as a one-line marker.
/// Ported from matron-apple's `Events/CoordinatorMarkerEvent.swift`.
data class CoordinatorMarkerEvent(val role: Role) {
    enum class Role(val wire: String) { ASSIGNED("assigned"), RELEASED("released") }

    /// The marker's one line — contract copy.
    val text: String
        get() = when (role) {
            Role.ASSIGNED -> "This chat is now the Coordinator"
            Role.RELEASED -> "This chat is no longer the Coordinator"
        }

    companion object {
        fun parse(payload: JsonObject): CoordinatorMarkerEvent? =
            Role.entries.firstOrNull { it.wire == payload.stringOrNull("role") }?.let(::CoordinatorMarkerEvent)
    }
}
