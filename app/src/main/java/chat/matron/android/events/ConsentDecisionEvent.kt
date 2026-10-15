package chat.matron.android.events

import chat.matron.android.journal.stringOrNull
import chat.matron.android.journal.longOrNull
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
    /// Chat: room/device key; spawn: request id. Absent on older payloads.
    val askID: String? = null,
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
            val line = "$icon Coordinator $verb the $what"
            return oneLineReason?.let { "$line — $it" } ?: line
        }

    /// The card's badge: "Approved by the Coordinator — <reason>" /
    /// "Declined by the Coordinator".
    val badgeText: String
        get() {
            val line = if (decision == Decision.APPROVE) "Approved by the Coordinator" else "Declined by the Coordinator"
            return oneLineReason?.let { "$line — $it" } ?: line
        }

    private val oneLineReason: String?
        get() = (reason ?: "").lines().joinToString(" ").trim().takeIf { it.isNotEmpty() }

    companion object {
        fun parse(payload: JsonObject): ConsentDecisionEvent? {
            val kind = Kind.entries.firstOrNull { it.wire == payload.stringOrNull("kind") } ?: return null
            val decision = Decision.entries.firstOrNull { it.wire == payload.stringOrNull("decision") } ?: return null
            val askID = when (kind) {
                Kind.CHAT -> {
                    val room = payload.stringOrNull("room_id")?.takeIf { it.isNotEmpty() }
                    val target = payload.longOrNull("target_device_id")
                    if (room != null && target != null) AgentChatRequest.askID(room, target) else null
                }
                Kind.SPAWN -> payload.stringOrNull("request_id")?.takeIf { it.isNotEmpty() }
            }
            return ConsentDecisionEvent(kind, decision, payload.stringOrNull("reason"), askID)
        }
    }
}

/// The "Approved by the Coordinator" badge on a consent card the
/// Coordinator answered, and the one tap that overrules it. Spawn cards get
/// **Stop session** once the session has started; chat cards carry the
/// badge alone. Ported from matron-apple's `CoordinatorConsentBadge`.
data class CoordinatorConsentBadge(
    val decision: ConsentDecisionEvent,
    /// The spawned session's conversation, which Stop session stops. Null
    /// until the journal reports the session started, and for a chat.
    val stoppableConvoID: String? = null,
    val stop: SessionStopState = SessionStopState.Idle,
) {
    val text: String get() = decision.badgeText
}

/// Where a Stop session tap is: not tapped, sending `/stop`, sent, or failed
/// with a message to show. In memory only: the stopped session's own chat is
/// the durable record.
sealed interface SessionStopState {
    data object Idle : SessionStopState
    data object Sending : SessionStopState
    data object Sent : SessionStopState
    data class Failed(val message: String) : SessionStopState
}
