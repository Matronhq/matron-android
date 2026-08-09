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
    /// The device on the far end of the ask, for display only. For an invite
    /// that is [targetDeviceID]; for a join it is the room's *owner*, which
    /// [targetDeviceID] is emphatically not. Never follow [targetDeviceID] to
    /// label the far end.
    val toName: String = "",
    /// The two sessions, id and title each. The id is the stable handle —
    /// titles are agent-written and change — and the title is what the user
    /// recognises from their conversation list. Empty when the requesting
    /// bridge named no conversation.
    val fromConvoID: String = "",
    val fromConvoTitle: String = "",
    val toConvoID: String = "",
    val toConvoTitle: String = "",
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

    /// The name to show for the far end, same fallback as [requesterLabel].
    /// For a join the far end is the room's owner, so this deliberately does
    /// not fall back to [targetDeviceID] — that id is the joiner.
    val targetLabel: String
        get() = when {
            toName.isNotEmpty() -> toName
            ask == Ask.INVITE -> "Device $targetDeviceID"
            else -> "the room's owner"
        }

    /// "dan-mac — 2:69 text carry and fitting parity", or just the device name
    /// when no session was named.
    val fromLabel: String
        get() = endpointLabel(requesterLabel, fromConvoID, fromConvoTitle)

    val toLabel: String
        get() = endpointLabel(targetLabel, toConvoID, toConvoTitle)

    /// One line stating what is being asked, in the user's terms. Names the
    /// far end: "another agent" is not something a user can consent to.
    val headline: String
        get() = when (ask) {
            Ask.INVITE -> "$requesterLabel wants to start a chat with $targetLabel."
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
                // Display-only, and all optional: a journal that predates them
                // still yields an answerable card, just a less specific one.
                toName = payload.stringOrNull("to_name") ?: "",
                fromConvoID = payload.stringOrNull("from_convo_id") ?: "",
                fromConvoTitle = payload.stringOrNull("from_convo_title") ?: "",
                toConvoID = payload.stringOrNull("to_convo_id") ?: "",
                toConvoTitle = payload.stringOrNull("to_convo_title") ?: "",
                topic = nonEmpty(payload.stringOrNull("topic")),
                justification = nonEmpty(payload.stringOrNull("justification")),
            )
        }

        /// The journal defaults `topic`/`justification` to `""` rather than
        /// omitting them, so "absent" and "empty" arrive identically —
        /// collapse both to null so the card can drop the row instead of
        /// drawing an empty quote.
        private fun nonEmpty(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

        /// A session rendered the way the conversation list renders it: the
        /// first two characters of its id, then its title.
        ///
        /// Bridges seed a session title as `"<box>:<first two of the id>
        /// words"`, which is the string the list shows — so when the title
        /// already opens with those two characters this returns the title
        /// untouched rather than stuttering ("69 · 2:69 text carry…"). Rooms
        /// and sub-chats carry no such prefix, which is why the id is sent
        /// alongside and the short form is derived from it rather than
        /// trusted to be in the words.
        ///
        /// Null when the journal named no conversation — the card drops the
        /// row rather than showing an empty one.
        fun sessionLabel(id: String, title: String): String? {
            val short = id.take(2)
            val trimmed = title.trim()
            if (short.isEmpty()) return trimmed.ifEmpty { null }
            if (trimmed.isEmpty()) return short
            val firstWord = trimmed.takeWhile { !it.isWhitespace() }
            val carriesShortID = firstWord == short || firstWord.endsWith(":$short")
            return if (carriesShortID) trimmed else "$short · $trimmed"
        }

        private fun endpointLabel(device: String, convoID: String, convoTitle: String): String {
            val session = sessionLabel(convoID, convoTitle) ?: return device
            return "$device — $session"
        }
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
