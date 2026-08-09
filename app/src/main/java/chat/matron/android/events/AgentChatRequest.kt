package chat.matron.android.events

import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonObject

/// One agent asking the user's permission to talk to another agent — the
/// journal's server-minted consent card (matron-journal `src/ws.js`, the
/// `agent_invite`/`agent_join` park path).
///
/// It arrives as a `permission_request` event whose payload carries
/// `kind: "agent_chat"`, and it is *client-only*: the journal filters it out
/// of every agent-facing replay and refuses to let an agent mint one. Nothing
/// in the timeline answers it — the answer is `POST /agent-chat/answer`, over
/// HTTP, keyed on `roomID` + `targetDeviceID`.
///
/// Deliberately NOT modelled as an `AskUserEvent`: that type's generic
/// Allow/Deny buttons answer over `prompt_reply`, which never reaches the
/// parked row, so the card would render inertly and the ask would sit until
/// its 24h TTL expired. The two facts a consent decision rests on — who is
/// asking and why — have no home in `AskUserEvent` either.
data class AgentChatRequest(
    val ask: Ask,
    /// The room the decision is about — half the key `POST /agent-chat/answer`
    /// needs.
    val roomID: String,
    val fromDeviceID: Long,
    /// The requesting agent's device name, already sanitised and capped
    /// server-side. Empty if the journal had no name for it.
    val fromName: String,
    /// The device the parked row is filed under — the other half of the answer
    /// key. For a join this is the joiner itself, not the room owner.
    val targetDeviceID: Long,
    val topic: String?,
    val justification: String?,
) {
    /// Which way round the ask goes. An `INVITE` is the requesting agent
    /// pulling `targetDeviceID` into a room it owns; a `JOIN` is the requester
    /// asking to be let into someone else's room, in which case it
    /// self-targets (`fromDeviceID == targetDeviceID`).
    enum class Ask(val wire: String) {
        INVITE("invite"),
        JOIN("join"),
    }

    /// The name to show for the requester, falling back to the device id when
    /// the journal had no name (a revoked device mid-ask).
    val requesterLabel: String
        get() = fromName.ifEmpty { "Device $fromDeviceID" }

    /// One line stating what is being asked, in the user's terms.
    val headline: String
        get() = when (ask) {
            Ask.INVITE -> "$requesterLabel wants to start a chat with another agent."
            Ask.JOIN -> "$requesterLabel wants to join this chat."
        }

    companion object {
        /// Parses a `permission_request` payload, or null if this is not an
        /// agent-chat card. Strict about the four fields an answer needs
        /// (`room_id`, `target_device_id`, `from_device_id`, a known
        /// `request`): a card we cannot answer must fall back to the generic
        /// permission rendering rather than draw buttons that would 400.
        fun parse(payload: JsonObject): AgentChatRequest? {
            if (payload.stringOrNull("kind") != "agent_chat") return null
            val roomID = payload.stringOrNull("room_id")?.takeIf { it.isNotEmpty() } ?: return null
            val ask = Ask.entries.firstOrNull { it.wire == payload.stringOrNull("request") } ?: return null
            val from = payload.longOrNull("from_device_id") ?: return null
            val target = payload.longOrNull("target_device_id") ?: return null
            return AgentChatRequest(
                ask = ask,
                roomID = roomID,
                fromDeviceID = from,
                fromName = payload.stringOrNull("from_name") ?: "",
                targetDeviceID = target,
                topic = nonEmpty(payload.stringOrNull("topic")),
                justification = nonEmpty(payload.stringOrNull("justification")),
            )
        }

        /// The journal defaults `topic`/`justification` to `""` rather than
        /// omitting them, so "absent" and "empty" arrive identically —
        /// collapse both to null so the card can drop the row instead of
        /// drawing an empty quote.
        private fun nonEmpty(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/// Where one consent card is in its short life.
///
/// `Answered` has to be remembered by the client: answering is an HTTP call,
/// so unlike an ask-user reply it leaves no event in the timeline to read the
/// outcome back from. `Expired` is the server's 409 — the row is no longer
/// awaiting anyone, because it timed out or was decided on another device.
sealed class AgentChatCardState {
    data object Idle : AgentChatCardState()
    data object Sending : AgentChatCardState()
    data class Answered(val approved: Boolean) : AgentChatCardState()
    data object Expired : AgentChatCardState()
    data class Failed(val message: String) : AgentChatCardState()
}
