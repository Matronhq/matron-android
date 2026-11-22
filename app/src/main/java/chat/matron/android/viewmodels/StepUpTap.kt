package chat.matron.android.viewmodels

import chat.matron.android.journal.ItemsProviding
import chat.matron.android.journal.ItemsSyncing
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.approvals.ApprovalError
import chat.matron.android.journal.approvals.ApprovalKeyFailure
import chat.matron.android.journal.approvals.ApprovalRequest
import chat.matron.android.journal.approvals.ApprovalSigner
import chat.matron.android.journal.approvals.ApprovalStatement
import chat.matron.android.journal.approvals.ApprovalSubjects
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

    const val RELOAD_REQUEST = "This request's details don't match what would be approved. Reload it and read it again."

    /// What Approve on a consent request's item signs: a `consent` statement
    /// over the subject the journal published, for a request aimed at a
    /// protected box. Only its yes is signed; null otherwise. Ported from
    /// matron-apple's `StepUpTap.consentRequest`.
    ///
    /// The subject names the work by a hash, which nobody can read. So the
    /// app signs only when it holds the very text that hash is of, and shows
    /// it: a spawn's task, or a chat ask's message, found verbatim in a
    /// fenced block of the item's own body and checked against the subject's
    /// `task_sha256` / `message_sha256`. The subject must also be about this
    /// item's request (its consent link) and an approval. A file transfer's
    /// subject is the file's own hash and size, shown as they are with the
    /// item's words. Throws [ApprovalError.SubjectMismatch] when a check fails.
    fun consentRequest(item: TrackerItem, label: String): ApprovalRequest? {
        val kind = item.consentKind ?: return null
        if (!item.stepUp || !TrackerItem.isAffirmativeAnswer(label)) return null
        val subject = item.subject ?: emptyMap()
        if (subject["op"] != kind || subject["decision"] != "approve") throw ApprovalError.SubjectMismatch(RELOAD_REQUEST)
        val detail = when (kind) {
            "spawn", "chat" -> {
                val ask = askOf(item, kind) ?: throw ApprovalError.SubjectMismatch(RELOAD_REQUEST)
                if (!matches(subject, "ask", ask)) throw ApprovalError.SubjectMismatch(RELOAD_REQUEST)
                val hashKey = if (kind == "spawn") "task_sha256" else "message_sha256"
                val hash = subject[hashKey] ?: throw ApprovalError.SubjectMismatch(RELOAD_REQUEST)
                verifiedText(item.body, hash) ?: throw ApprovalError.SubjectMismatch(
                    if (kind == "spawn") {
                        "The task on this request is not the task that would be approved. Reload the request and read it again."
                    } else {
                        "The message on this request is not the one that would be approved. Reload the request and read it again."
                    },
                )
            }
            "transfer" -> item.body
            else -> throw ApprovalError.SubjectMismatch("This app can't approve this kind of request yet.")
        }
        return ApprovalRequest(
            kind = ApprovalStatement.Kind.Consent, subject = subject, headline = item.title,
            reference = "#${item.num}", button = label, detail = detail,
        )
    }

    /// The request a consent item stands for, from its consent link: a
    /// spawn's id, or a chat ask's `room/device`.
    internal fun askOf(item: TrackerItem, kind: String): String? {
        val prefix = "matron://consent/$kind/"
        return item.links.firstOrNull { it.url.startsWith(prefix) }?.url?.removePrefix(prefix)?.takeIf { it.isNotEmpty() }
    }

    /// Whether the subject's [name] (or its hashed form, for a long value)
    /// is [value].
    private fun matches(subject: Map<String, String>, name: String, value: String): Boolean =
        subject[name]?.let { it == value } ?: (subject["${name}_sha256"] == ApprovalSubjects.sha256(value))

    /// The text of the first fenced block in [body] whose SHA-256 is [hash],
    /// or null. The journal fences peer text with a run of backticks longer
    /// than any inside it, so a block ends at the first line that is exactly
    /// its opening fence.
    internal fun verifiedText(body: String, hash: String): String? {
        val lines = body.split("\n")
        var i = 0
        while (i < lines.size) {
            val fence = lines[i]
            if (fence.length >= 3 && fence.all { it == '`' }) {
                val end = (i + 1 until lines.size).firstOrNull { lines[it] == fence }
                if (end != null) {
                    val text = lines.subList(i + 1, end).joinToString("\n")
                    if (ApprovalSubjects.sha256(text) == hash) return text
                    i = end + 1
                    continue
                }
            }
            i += 1
        }
        return null
    }

    /// One idempotency key per tap the user is making, kept until the journal
    /// has answered it: a retry after a lost answer is the same tap, which the
    /// journal then answers once instead of applying twice.
    private val tapKeys = mutableMapOf<String, String>()
    private fun tapKey(itemID: String, replyTo: String?, label: String) = "$itemID|${replyTo.orEmpty()}|$label"
    internal fun idempotencyKey(itemID: String, replyTo: String?, label: String): String =
        synchronized(tapKeys) { tapKeys.getOrPut(tapKey(itemID, replyTo, label)) { UUID.randomUUID().toString() } }
    private fun answered(itemID: String, replyTo: String?, label: String) {
        synchronized(tapKeys) { tapKeys.remove(tapKey(itemID, replyTo, label)) }
    }

    /// Whether the journal refused the tap outright, so it cannot have
    /// landed: a 4xx, other than a timeout or a throttle.
    internal fun refusedOutright(e: Throwable): Boolean = when (e) {
        JournalApiError.ApprovalRequired, is JournalApiError.ApprovalInvalid, JournalApiError.Conflict,
        JournalApiError.Forbidden, JournalApiError.NotFound, JournalApiError.Unauthenticated,
        is JournalApiError.AccountLocked -> true
        is JournalApiError.Http -> e.status in 400..499 && e.status != 408 && e.status != 429
        else -> false
    }

    const val MAY_HAVE_BEEN_SENT = "The connection dropped after your answer went out, so it may have been sent. Refreshing the item."

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Offline, or the read refused. A tap the cache does not know
                // to be step-up goes to the outbox as before, and so does a
                // consent request's decline (never signed); a step-up one
                // cannot wait.
                val consentDecline = (item.isConsentAsk || TrackerItem.CONSENT_LABEL in item.labels) &&
                    !TrackerItem.isAffirmativeAnswer(label)
                if ((item.stepUp || comment?.stepUp == true) && !consentDecline) {
                    val why = if (e is JournalApiError.Transport) "can't wait for a connection" else "couldn't be checked first"
                    return Outcome.Failed("This answer needs a signature and $why. ${e.message}")
                }
                return Outcome.Unsigned
            }
            val freshComment = comment?.let { c -> fresh.comments.firstOrNull { it.id == c.id } }
            val request = if (fresh.item.consentKind != null) {
                // A consent ask is answered with a `consent` statement over
                // the request it stands for; only Approve on one aimed at a
                // protected box needs it. A follow-up's buttons on one are
                // never signed here.
                if (comment != null) null else consentRequest(fresh.item, label)
            } else if (comment != null) {
                freshComment?.let { request(fresh.item, it, label) }
            } else {
                request(fresh.item, null, label)
            }
            // No longer step-up (the mark was lowered), or the asking comment
            // has gone: an ordinary tap.
            if (request == null) return Outcome.Unsigned
            val approval = signer(request) ?: return Outcome.Unsigned
            val result = try {
                api.tapItem(item.id, label, comment?.id, approval, idempotencyKey(item.id, comment?.id, label))
            } catch (e: JournalApiError.Transport) {
                // The answer was lost: the tap may have landed. The next try
                // carries the same idempotency key, so it is never applied twice.
                return Outcome.Failed(MAY_HAVE_BEEN_SENT)
            } catch (e: CancellationException) {
                // Called off mid-request: the tap may have landed, so its key
                // stays for the next try.
                throw e
            } catch (e: Throwable) {
                // A definite refusal: nothing landed, so the next try is a new
                // tap with a key of its own. A server error or a throttle says
                // nothing about whether it landed: the key stays.
                if (refusedOutright(e)) answered(item.id, comment?.id, label)
                throw e
            }
            answered(item.id, comment?.id, label)
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
            if (!landed) {
                try {
                    sync.refreshItem(item.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // A refetch that fails changes nothing here.
                }
            }
        }
    }
}
