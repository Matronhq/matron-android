package chat.matron.android.viewmodels

import chat.matron.android.chat.JournalTimelineMapper
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.models.QueuedReplyState
import kotlinx.serialization.json.JsonObject

/// Derives each of an item's replies' [QueuedReplyState] from the owner
/// conversation's `queued_release` cards and their releases. A reply the user
/// sends on an item reaches the agent as a turn; while the session is busy
/// the bridge holds that turn on its queue and posts a card naming the reply
/// (`item: {item_id, comment_id}`). The card's release row settles it.
object ItemQueuedReplies {
    /// The queue state of each of [itemID]'s replies, by comment id, from
    /// [rows] (oldest first). A delivered reply is absent. The earliest
    /// release wins: the realistic double is a committed `send` followed by a
    /// restart's `expired`, and the send is what happened. Only bridge rows
    /// (`agent:` senders) count: a card or release is the bridge's word about
    /// its own queue.
    fun derive(rows: List<JournalEvent>, itemID: String): Map<String, QueuedReplyState> {
        data class Card(val commentID: String, val convoID: String, val seq: Long, val sendOne: Boolean)
        val cards = LinkedHashMap<String, Card>()
        val released = HashMap<String, String>()
        for (row in rows) {
            if (!row.sender.startsWith("agent:")) continue
            val p = row.payload
            if (p.stringOrNull("kind") != JournalTimelineMapper.QUEUED_RELEASE_KIND) continue
            val promptID = p.stringOrNull("prompt_id") ?: continue
            when (row.type) {
                JournalEventType.PROMPT -> {
                    val commentID = commentIDFor(p, itemID) ?: continue
                    val actions = p.arrayOrNull("actions").orEmpty().mapNotNull { (it as? JsonObject)?.stringOrNull("id") }
                    cards[promptID] = Card(commentID, row.convoID, row.seq, SEND_ONE in actions)
                }
                JournalEventType.PROMPT_REPLY -> {
                    val action = p.stringOrNull("action") ?: continue
                    if (promptID !in released) released[promptID] = action
                }
            }
        }
        val out = HashMap<String, QueuedReplyState>()
        for ((promptID, card) in cards) {
            when (released[promptID]) {
                null -> out[card.commentID] = QueuedReplyState.Queued(card.convoID, card.seq, card.sendOne)
                CANCEL_CHOICE -> out[card.commentID] = QueuedReplyState.Cancelled
                EXPIRED -> out[card.commentID] = QueuedReplyState.NotDelivered
                else -> Unit // send / send_one: delivered
            }
        }
        return out
    }

    /// The replies (by comment id) whose live card can be edited from the
    /// thread: the card says so with `edit: {mode: "resend"}`. The bridge
    /// holds the reply (`edit`) while its text is edited in the reply box,
    /// and sending the edit cancels the original (which withdraws it) before
    /// the edited reply goes as a new comment.
    fun editable(rows: List<JournalEvent>, itemID: String): Set<String> {
        val cards = HashMap<String, String>()
        val released = HashSet<String>()
        for (row in rows) {
            if (!row.sender.startsWith("agent:")) continue
            val p = row.payload
            if (p.stringOrNull("kind") != JournalTimelineMapper.QUEUED_RELEASE_KIND) continue
            val promptID = p.stringOrNull("prompt_id") ?: continue
            when (row.type) {
                JournalEventType.PROMPT -> {
                    val commentID = commentIDFor(p, itemID) ?: continue
                    if (p.objectOrNull("edit")?.stringOrNull("mode") != "resend") continue
                    cards[promptID] = commentID
                }
                JournalEventType.PROMPT_REPLY -> released += promptID
            }
        }
        return cards.filterKeys { it !in released }.values.toSet()
    }

    private fun commentIDFor(payload: JsonObject, itemID: String): String? {
        val item = payload.objectOrNull("item") ?: return null
        if (item.stringOrNull("item_id") != itemID) return null
        return item.stringOrNull("comment_id")?.takeIf { it.isNotEmpty() }
    }

    /// The card choice a Send now taps: just this reply when the card can,
    /// otherwise the whole queue (which includes this reply).
    fun sendNowChoice(offersSendOne: Boolean): String = if (offersSendOne) SEND_ONE else SEND

    const val SEND = "send"
    const val SEND_ONE = "send_one"

    /// The card choice a Cancel taps: the bridge's `cancel` always withdraws
    /// just the card's own message, whatever else is queued behind it.
    const val CANCEL_CHOICE = "cancel"

    /// Edit holds the reply on the queue; a cancelled edit lets it go as it was.
    const val EDIT_CHOICE = "edit"
    const val EDIT_CANCEL_CHOICE = "edit_cancel"

    private const val EXPIRED = "expired"
}

/// Answers a reply's queue card: the same `prompt_reply` a tap on the card in
/// the conversation sends, so the bridge handles it exactly as it does there.
fun interface QueuedReplySending {
    suspend fun sendQueuedRelease(convoID: String, targetSeq: Long, choice: String)
}
