package chat.matron.android.journal

/// What the journal says when it refuses a request because the whole account
/// is locked: since when, and whether this device can unlock it (it holds an
/// approval key old enough, so the journal froze it instead of signing it out).
data class AccountLock(
    /// When the lock began, epoch ms; null when the answer left it out.
    val since: Long?,
    /// This device was frozen, not signed out: it keeps its token and can
    /// unlock the account with a signed statement.
    val canUnlock: Boolean,
) {
    companion object {
        /// From an HTTP answer: any 423, or a body that says `account_locked`.
        fun fromHttp(status: Int, body: ByteArray): AccountLock? {
            val obj = parseJsonObjectOrNull(String(body, Charsets.UTF_8))
            if (status != 423 && obj?.stringOrNull("error") != "account_locked") return null
            return AccountLock(obj?.longOrNull("since"), obj?.boolOrNull("unlock") ?: false)
        }
    }
}

/// Told what each answer says about the account lock. The API client and the
/// sync engine call it from any thread; [chat.matron.android.viewmodels.AccountLockMonitor]
/// implements it.
interface AccountLockListening {
    /// The status of an answered request that carried this device's token.
    fun noteStatus(status: Int)
    /// A request was refused because the account is locked.
    fun noteLock(lock: AccountLock)
    /// The live socket said the account is locked (its `panic` frame, or a
    /// refused hello).
    fun noteLockedBySocket(lock: AccountLock)
    /// The journal accepted this device's hello: the account is not locked.
    fun noteSocketAccepted()
}

/// What `POST /panic/unlock` answers when it unlocks the account.
data class AccountUnlockResult(
    /// This device's new token. The old one stopped working when the account
    /// was unlocked: store this one before anything else.
    val token: String,
    /// How many other devices the unlock signed out.
    val devicesRevoked: Int,
)

/// 409 `not_locked`: the account is not locked (any more).
class AccountNotLockedError : Exception("The account is not locked.")

/// What unlocking a locked account needs from the journal: the only two
/// routes a frozen device's token can call. [JournalApi] implements it.
interface AccountUnlocking {
    suspend fun approvalNonce(): chat.matron.android.journal.approvals.ApprovalNonce
    /// Unlocks the account with a `clear_panic` statement signed by this
    /// device's key. Every other device of the account is signed out, and
    /// this one gets a new token.
    suspend fun unlockAccount(approval: chat.matron.android.journal.approvals.SignedApproval): AccountUnlockResult
}
