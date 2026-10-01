package chat.matron.android.events

import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonObject

/// The journal's `consent_decision` event (protocol: "Coordinator consent →
/// What an answer does"): appended, client-only, on a consent card's
/// conversation when the Coordinator answered it — the parent conversation
/// for a spawn, the room for a chat. The timeline draws a one-line row so the
/// user can see who decided and why. Ported from matron-apple's
/// `Events/ConsentDecisionEvent.swift`.
data class ConsentDecisionEvent(
    val kind: Kind,
    val decision: Decision,
    /// The Coordinator's one-line reason, as the user reads it on the card.
    val reason: String? = null,
) {
    enum class Kind(val wire: String) { CHAT("chat"), SPAWN("spawn") }
    enum class Decision(val wire: String) { APPROVE("approve"), DECLINE("decline") }

    /// "✅ Coordinator approved the spawn request — <reason>" /
    /// "🚫 Coordinator declined the chat request".
    val text: String
        get() {
            val icon = if (decision == Decision.APPROVE) "✅" else "🚫"
            val verb = if (decision == Decision.APPROVE) "approved" else "declined"
            val what = if (kind == Kind.SPAWN) "spawn request" else "chat request"
            val oneLine = (reason ?: "").lines().joinToString(" ").trim()
            val line = "$icon Coordinator $verb the $what"
            return if (oneLine.isEmpty()) line else "$line — $oneLine"
        }

    companion object {
        fun parse(payload: JsonObject): ConsentDecisionEvent? {
            val kind = Kind.entries.firstOrNull { it.wire == payload.stringOrNull("kind") } ?: return null
            val decision = Decision.entries.firstOrNull { it.wire == payload.stringOrNull("decision") } ?: return null
            return ConsentDecisionEvent(kind, decision, payload.stringOrNull("reason"))
        }
    }
}
