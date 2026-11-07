package chat.matron.android.events

import chat.matron.android.chat.TimelineItem
import chat.matron.android.journal.stringOrNull
import chat.matron.android.models.ItemResolution
import kotlinx.serialization.json.JsonObject

/// An agent's ask for an invite link (protocol.md "Contacts across
/// journals", *An agent's ask*): a `permission_request` with `kind:
/// "contact_invite"`. The agent never gets the link; the user's Approve
/// makes one and the journal hands it to this device, this once.
///
/// Answered by `POST /contacts/invites/asks/:id/answer`, never by
/// `prompt_reply`. Only [askId] is required.
data class ContactInviteAsk(
    val askId: String,
    /// The asking agent's device name.
    val fromName: String? = null,
    val fromConvoId: String? = null,
) {
    companion object {
        const val KIND = "contact_invite"
        const val LABEL = "Invite link request"
        const val HEADLINE = "Your agent asks for an invite link for someone on another Matron"
        const val SHOWN_ONCE = "Shown only on this device, this once. Send it to them yourself."
        const val APPROVED_ELSEWHERE = "Approved on another device."
        const val DECLINED = "Declined"
        const val EXPIRED = "Expired — nobody answered within 24 hours"

        fun parse(payload: JsonObject): ContactInviteAsk? {
            if (payload.stringOrNull("kind") != KIND) return null
            val askId = payload.stringOrNull("ask_id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return ContactInviteAsk(
                askId = askId,
                fromName = payload.stringOrNull("from_name")?.trim()?.takeIf { it.isNotEmpty() },
                fromConvoId = payload.stringOrNull("from_convo_id")?.takeIf { it.isNotEmpty() },
            )
        }

        /// How each ask card in [items] ended, read from its tracker
        /// mirror's `closed` marker (consent `invite`): one open ask per
        /// conversation, so a close answers the latest card before it.
        /// Cards still waiting are absent.
        fun resolutions(items: List<TimelineItem>): Map<String, InviteAskState> {
            val out = mutableMapOf<String, InviteAskState>()
            var open: String? = null
            for (item in items) {
                when (val kind = item.kind) {
                    is TimelineItem.Kind.ContactInviteAskCard -> open = kind.eventID
                    is TimelineItem.Kind.ItemMarker -> {
                        val marker = kind.marker
                        if (marker.consent != "invite" || marker.action != ItemMarkerEvent.Action.CLOSED) continue
                        val card = open ?: continue
                        out[card] = when {
                            marker.resolution == ItemResolution.CANCELLED -> InviteAskState.Expired
                            marker.comment?.body?.trimStart()?.startsWith("Declined") == true -> InviteAskState.Declined
                            else -> InviteAskState.ApprovedElsewhere
                        }
                        open = null
                    }
                    else -> {}
                }
            }
            return out
        }
    }
}

/// Where one invite ask card is. The link an approve brings back is not
/// part of it: it goes to the session's [chat.matron.android.journal.RevealedInviteLinks],
/// which shows it once.
sealed class InviteAskState {
    data object Idle : InviteAskState()
    data object Sending : InviteAskState()
    data object Approved : InviteAskState()
    data object ApprovedElsewhere : InviteAskState()
    data object Declined : InviteAskState()
    data object Expired : InviteAskState()
    data class Failed(val message: String) : InviteAskState()
    /// No answerer wired (previews), or the ask is not this user's.
    data object Unavailable : InviteAskState()

    val answerable: Boolean get() = this is Idle || this is Failed

    /// The resolved line under an answered card; null while it waits.
    val resolvedText: String?
        get() = when (this) {
            Approved -> "Approved"
            ApprovedElsewhere -> ContactInviteAsk.APPROVED_ELSEWHERE
            Declined -> ContactInviteAsk.DECLINED
            Expired -> ContactInviteAsk.EXPIRED
            Unavailable -> "This request is no longer waiting for an answer."
            else -> null
        }
}
