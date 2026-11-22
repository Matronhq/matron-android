package chat.matron.android.viewmodels

import chat.matron.android.journal.AccountNotLockedError
import chat.matron.android.journal.AccountUnlocking
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.approvals.ApprovalKeyFailure
import chat.matron.android.journal.approvals.SignedApproval
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Unlocking a locked account from this device, with its fingerprint. Ported
/// from matron-apple's `AccountUnlockModel`.
///
/// The journal froze this device instead of signing it out, because it holds
/// an approval key old enough. Unlocking signs a statement over the lock's
/// start, sends it, and swaps in the new token the journal answers with: the
/// old one stops working the moment the account is unlocked, and every other
/// device of the account is signed out.
class AccountUnlockModel(
    private val monitor: AccountLockMonitor,
    private val api: AccountUnlocking,
    /// The confirm sheet, then the signature (`ApprovalCenter.signUnlock`).
    private val sign: suspend (since: Long) -> SignedApproval,
    /// Saves the new token and starts using it in place of the old one, before
    /// anything else is sent. Returns false when it is in use but could not be
    /// saved for the next launch.
    private val adoptToken: suspend (String) -> Boolean,
    /// Whether the sign-in the unlock started from is still the current one.
    private val isCurrent: () -> Boolean = { true },
    /// Told when the unlock request goes out ([Attempt.begin]) and how it
    /// ended ([Attempt.end], true when this device took its new token), so a
    /// refusal of the old token meanwhile is held back
    /// ([chat.matron.android.journal.CredentialRevocationMonitor.hold]).
    private val attempt: Attempt = Attempt.NONE,
) {
    class Attempt(val begin: () -> Unit, val end: (tookNewToken: Boolean) -> Unit) {
        companion object {
            val NONE = Attempt({}, {})
        }
    }

    sealed interface Phase {
        data object Idle : Phase
        data object Unlocking : Phase
        data class Failed(val message: String) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    suspend fun unlock() {
        if (_phase.value == Phase.Unlocking) return
        val since = monitor.state.value.lock?.since ?: run {
            _phase.value = Phase.Failed("The app doesn't know yet when the lock began. Wait a moment and try again.")
            return
        }
        _phase.value = Phase.Unlocking
        val approval = try {
            sign(since)
        } catch (e: CancellationException) {
            _phase.value = Phase.Idle
            throw e
        } catch (_: ApprovalKeyFailure.Cancelled) {
            _phase.value = Phase.Idle
            return
        } catch (e: Throwable) {
            _phase.value = Phase.Failed(e.message ?: e.toString())
            return
        }
        // An answer for a sign-in that has since gone changes nothing.
        if (!isCurrent()) { _phase.value = Phase.Idle; return }
        // From here the journal may unlock, and kill the old token, before
        // this device hears it: no sign-out for that token's refusals until
        // the attempt is settled.
        attempt.begin()
        var tookNewToken = false
        try {
            tookNewToken = send(approval)
        } finally {
            attempt.end(tookNewToken)
        }
    }

    /// Sends the signed unlock and takes the new token. True once the new
    /// token is in use.
    private suspend fun send(approval: SignedApproval): Boolean {
        val result = try {
            api.unlockAccount(approval)
        } catch (e: CancellationException) {
            _phase.value = Phase.Idle
            throw e
        } catch (_: AccountNotLockedError) {
            if (isCurrent()) monitor.cleared()
            _phase.value = Phase.Idle
            return false
        } catch (e: Throwable) {
            _phase.value = if (isCurrent()) Phase.Failed(text(e)) else Phase.Idle
            return false
        }
        // The answer belongs to the sign-in the unlock started from: after a
        // sign-out or a new sign-in it is thrown away.
        if (!isCurrent()) { _phase.value = Phase.Idle; return false }
        val saved = adoptToken(result.token)
        _phase.value = Phase.Idle
        monitor.cleared()
        if (!saved) monitor.warn(AccountLockMonitor.UNSAVED_TOKEN_WARNING)
        return true
    }

    companion object {
        fun text(error: Throwable): String = when (error) {
            is JournalApiError.AccountLocked ->
                "This device can't unlock the account. Unlock it from another device, or ask whoever manages the server."
            JournalApiError.ApprovalRequired ->
                "This device's key can't unlock this lock: it was added too recently, or isn't attached to this device."
            JournalApiError.Unauthenticated -> "This device has been signed out."
            is JournalApiError.Transport -> "Couldn't reach the server. Try again."
            else -> error.message ?: error.toString()
        }

        /// The confirm sheet's words for an unlock: which lock, and what
        /// unlocking does.
        fun unlockDetail(since: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            "The lock that began ${sinceText(since, zone)} ends, and every other device signed in to this account is " +
                "signed out. Sign them in again afterwards."

        fun sinceText(since: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(zone)
                .format(Instant.ofEpochMilli(since))
    }
}
