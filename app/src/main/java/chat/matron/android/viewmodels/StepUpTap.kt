package chat.matron.android.viewmodels

import chat.matron.android.journal.ItemsProviding
import chat.matron.android.journal.ItemsSyncing
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.approvals.ApprovalError
import chat.matron.android.journal.approvals.ApprovalKeyFailure
import chat.matron.android.journal.approvals.ApprovalRequest
import chat.matron.android.journal.approvals.ApprovalSigner
import chat.matron.android.journal.approvals.ApprovalStatement
import chat.matron.android.journal.approvals.PromptDigest
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.util.UUID
import kotlinx.coroutines.CancellationException

/// A tap that needs a signature: a button on a step-up item or follow-up
/// question. Ported from matron-apple's `StepUpTap`.
///
/// Such a tap never goes through the offline outbox. A nonce lives five
/// minutes and a signature must not wait on disk, so it is confirmed, signed
/// and posted at once, and the item is refetched.
object StepUpTap {
    /// What a tap on [label] must be signed with, or null when the journal
    /// asks for no signature on it. [comment] is the follow-up question whose
    /// button was tapped; null for the item's own buttons.
    fun request(item: TrackerItem, comment: TrackerComment?, label: String): ApprovalRequest? {
        if (item.consentKind != null) return null
        if (!item.stepUp && comment?.stepUp != true) return null
        // The asking comment's own subject when that comment is step-up, else
        // the item's; none is signed as `{}`.
        val subject = (if (comment?.stepUp == true) comment.subject else item.subject) ?: emptyMap()
        val digest = comment?.let { PromptDigest.comment(it.body) } ?: PromptDigest.item(item.title, item.body)
        return ApprovalRequest(
            kind = ApprovalStatement.Kind.ItemTap(item.id, item.num, comment?.id, label, digest),
            subject = subject, headline = item.title, reference = "#${item.num}", button = label,
            detail = comment?.body ?: item.body,
        )
    }

    /// Whether a tap may need a signature, judged from the cached item:
    /// enough to decide between the outbox and [send], which reads the item
    /// again before anything is signed. [accountHasKey] is the approval
    /// centre's last read: once the account has a key, an item cached before
    /// the step-up mark was stored could carry it, so the fresh read decides.
    fun mayNeedSignature(item: TrackerItem, comment: TrackerComment?, accountHasKey: Boolean): Boolean =
        item.stepUp || comment?.stepUp == true || accountHasKey

    sealed interface Outcome {
        /// No signature applies (not step-up, or the account has no key):
        /// send the tap the ordinary way.
        data object Unsigned : Outcome
        /// Signed and accepted.
        data object Sent : Outcome
        /// The user backed out; nothing was sent.
        data object Cancelled : Outcome
        /// Not sent, or refused; [message] says why.
        data class Failed(val message: String) : Outcome
    }

    const val CONSENT_ELSEWHERE =
        "This request is aimed at a protected box, so approving it needs your approval key on its card. " +
            "Approve it from a device that can sign consent requests."

    /// Signs and posts the tap when it needs a signature.
    ///
    /// The item is read again first, so what is shown and hashed is the
    /// journal's current text. A `mismatch` (the question was edited while the
    /// user was signing) refetches it, so the next tap signs the new words.
    suspend fun send(
        item: TrackerItem,
        comment: TrackerComment?,
        label: String,
        api: ItemsProviding,
        sync: ItemsSyncing,
        signer: ApprovalSigner?,
        accountHasKey: Boolean,
    ): Outcome {
        if (!mayNeedSignature(item, comment, accountHasKey)) return Outcome.Unsigned
        if (signer == null) return Outcome.Failed(ApprovalError.NoKeyOnThisDevice.message!!)
        // Whatever happens short of a sent tap, the item is read again after,
        // so its thread and step-up mark are the journal's.
        var landed = false
        try {
            // The current words: the digest must be over the stored text, and
            // only a fresh read says whether this is step-up at all.
            val fresh = try {
                api.item(item.id)
            } catch (e: JournalApiError.Transport) {
                // Offline. A tap the cache does not know to be step-up goes to
                // the outbox as before, and so does a consent request's
                // decline (never signed); a step-up one cannot wait.
                val consentDecline = (item.isConsentAsk || TrackerItem.CONSENT_LABEL in item.labels) &&
                    !TrackerItem.isAffirmativeAnswer(label)
                return if ((item.stepUp || comment?.stepUp == true) && !consentDecline) {
                    Outcome.Failed("This answer needs a signature and can't wait for a connection. ${e.message}")
                } else {
                    Outcome.Unsigned
                }
            }
            val freshComment = comment?.let { c -> fresh.comments.firstOrNull { it.id == c.id } }
            if (fresh.item.consentKind != null) {
                // A consent ask is answered with a `consent` statement over
                // the request it stands for, which this path does not sign.
                // Only an approval of one aimed at a protected box needs it.
                return if (comment == null && fresh.item.stepUp && TrackerItem.isAffirmativeAnswer(label)) {
                    Outcome.Failed(CONSENT_ELSEWHERE)
                } else {
                    Outcome.Unsigned
                }
            }
            val request = if (comment != null) {
                freshComment?.let { request(fresh.item, it, label) }
            } else {
                request(fresh.item, null, label)
            }
            // No longer step-up (the mark was lowered), or the asking comment
            // has gone: an ordinary tap.
            if (request == null) return Outcome.Unsigned
            val approval = signer(request) ?: return Outcome.Unsigned
            val result = api.tapItem(item.id, label, comment?.id, approval, UUID.randomUUID().toString())
            // The tap and the asking comment's choice land now; the refetch
            // below can fail.
            sync.applyTapResult(result.item, result.comment)
            landed = true
            sync.refreshItem(item.id)
            return Outcome.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApprovalKeyFailure.Cancelled) {
            return Outcome.Cancelled
        } catch (e: Throwable) {
            if (e is JournalApiError.ApprovalInvalid && e.reason == "mismatch") {
                return Outcome.Failed("The question changed while you were approving it. Read it again, then approve.")
            }
            if (e is JournalApiError.Transport) {
                return Outcome.Failed("This answer needs a signature and can't wait for a connection. ${e.message}")
            }
            return Outcome.Failed(e.message ?: e.toString())
        } finally {
            if (!landed) runCatching { sync.refreshItem(item.id) }
        }
    }
}
