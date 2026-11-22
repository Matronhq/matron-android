package chat.matron.android.models

/// Where one of the user's replies on an item stands with the agent when it
/// has not simply reached it: parked on the session's busy queue, being
/// sent or cancelled from this device, or never delivered. A reply the agent
/// got has no state at all: it reads as sent, which it is.
///
/// Derived from the bridge's `queued_release` cards in the item's owner
/// conversation (`ItemQueuedReplies`), plus this device's own taps in
/// flight.
sealed interface QueuedReplyState {
    /// Waiting on the busy queue. [targetSeq] is the card's own seq, which
    /// Send now and Cancel answer; [offersSendOne] says the card can release
    /// this reply alone (else `send` releases the whole queue).
    data class Queued(val convoID: String, val targetSeq: Long, val offersSendOne: Boolean) : QueuedReplyState

    /// Send now tapped here; the bridge's release has not come back yet.
    data object Sending : QueuedReplyState

    /// Cancel tapped here; the bridge's release has not come back yet.
    data object Cancelling : QueuedReplyState

    /// Send now or Cancel could not be sent, or the bridge never confirmed
    /// it: still queued, tap again.
    data class SendFailed(
        val convoID: String,
        val targetSeq: Long,
        val offersSendOne: Boolean,
        val reason: String,
    ) : QueuedReplyState

    /// Cancelled, here or from the conversation's card: the agent never got
    /// it. The comment stays in the thread, marked.
    data object Cancelled : QueuedReplyState

    /// The session ended before the queue flushed: the agent never got it.
    data object NotDelivered : QueuedReplyState

    /// The card this reply's buttons answer, while it is still on the queue.
    val card: Card?
        get() = when (this) {
            is Queued -> Card(convoID, targetSeq, offersSendOne)
            is SendFailed -> Card(convoID, targetSeq, offersSendOne)
            else -> null
        }

    data class Card(val convoID: String, val targetSeq: Long, val offersSendOne: Boolean)

    /// Still on the busy queue, so Send now, Cancel and Edit apply.
    val canSendNow: Boolean get() = this is Queued || this is SendFailed

    /// A tap made here is waiting on the bridge's release.
    val isAwaitingBridge: Boolean get() = this == Sending || this == Cancelling

    /// Never reached the agent and never will: worth sending again.
    val canResend: Boolean get() = this == Cancelled || this == NotDelivered

    /// Drawn as a problem rather than as progress.
    val isFailure: Boolean get() = this is SendFailed || this == Cancelled || this == NotDelivered

    /// Still waiting on the agent's queue, a tap made here included.
    val isWaiting: Boolean get() = this is Queued || this is SendFailed || this == Sending || this == Cancelling
}
