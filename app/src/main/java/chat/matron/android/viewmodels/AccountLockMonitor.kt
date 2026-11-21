package chat.matron.android.viewmodels

import chat.matron.android.journal.AccountLock
import chat.matron.android.journal.AccountLockListening
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Whether the journal says the whole account is locked, and whether this
/// device can unlock it. Ported from matron-apple's `AccountLockMonitor`.
///
/// A lock shows up three ways: a `panic` frame on the live socket just before
/// the journal closes it, a hello refused with `account_locked`, and HTTP 423
/// on a request. Each says when the lock began and, with `unlock: true`, that
/// the journal froze this device instead of signing it out, because it holds
/// an approval key old enough: it keeps its token and can unlock the account
/// with its fingerprint.
///
/// The journal does not announce that a lock was cleared from its host. An
/// answer the server could only give a signed-in device (anything but 401,
/// 423, a throttle or a server error) shows it has been, and so do the socket
/// being accepted again and this device's own unlock.
///
/// Safe to call from any thread: the state is guarded by one monitor.
class AccountLockMonitor(private val clock: () -> Long = System::nanoTime) : AccountLockListening {
    data class State(
        val isLocked: Boolean = false,
        /// What the journal last said about the lock.
        val lock: AccountLock? = null,
    ) {
        /// The journal froze this device: it can unlock the account.
        val canUnlock: Boolean get() = isLocked && lock?.canUnlock == true
    }

    private val guard = Any()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /// Something the user must read once the lock has gone (the new sign-in
    /// an unlock gave could not be saved). Shown until dismissed.
    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    /// The live socket said so. An HTTP answer cannot undo that: one sent
    /// before the lock can arrive after it. Only the socket being accepted
    /// again, or an unlock, does.
    private var lockedBySocket = false
    /// When the lock now shown was last reported. An acceptance heard before
    /// then cannot clear it.
    private var lockedAt: Long? = null
    private val clearedHandlers = mutableListOf<() -> Unit>()

    /// Runs [handler] each time the lock is cleared: what waited behind it
    /// (the item outbox) can go again.
    fun whenCleared(handler: () -> Unit) = synchronized(guard) { clearedHandlers += handler; Unit }

    override fun noteStatus(status: Int) {
        if (verdict(status) != false) return
        // A frozen device's token can still fetch a nonce: that answer says
        // nothing about the lock. Nor can an HTTP answer undo the socket's word.
        clearIf { !lockedBySocket && _state.value.lock?.canUnlock != true }
    }

    override fun noteLock(lock: AccountLock) = locked(lock, clock(), bySocket = false)

    override fun noteLockedBySocket(lock: AccountLock) = locked(lock, clock(), bySocket = true)

    override fun noteSocketAccepted() {
        val at = clock()
        // The journal greets no device of a locked account, so this ends a
        // lock however it was learned; but not one reported after this hello
        // was accepted (the lock came later).
        clearIf {
            val reported = lockedAt
            if (reported != null && reported > at) return@clearIf false
            lockedBySocket = false
            true
        }
    }

    /// This device unlocked the account (or it is signing out).
    fun cleared() {
        clearIf {
            lockedBySocket = false
            true
        }
    }

    fun warn(text: String) { _warning.value = text }
    fun dismissWarning() { _warning.value = null }

    private fun locked(lock: AccountLock, at: Long, bySocket: Boolean) {
        synchronized(guard) {
            if (bySocket) lockedBySocket = true
            val previous = lockedAt
            if (previous == null || at > previous) lockedAt = at
            val known = _state.value.lock
            // A refusal that leaves `since` out keeps the one already known;
            // and only the socket (whose refusal always says) can take a
            // frozen device's unlock away: an HTTP answer without `unlock`
            // (a proxy's 423, an empty body) cannot.
            val canUnlock = lock.canUnlock || (!bySocket && known?.canUnlock == true)
            _state.value = State(isLocked = true, lock = AccountLock(lock.since ?: known?.since, canUnlock))
        }
    }

    /// Clears the lock when [allowed], decided and done under one monitor so
    /// a lock reported meanwhile is never wiped. The cleared handlers run
    /// after, outside it.
    private fun clearIf(allowed: () -> Boolean) {
        val handlers = synchronized(guard) {
            if (!_state.value.isLocked) {
                allowed()
                return
            }
            if (!allowed()) return
            _state.value = State()
            lockedAt = null
            clearedHandlers.toList()
        }
        handlers.forEach { it() }
    }

    companion object {
        /// Shown on the locked screen's sign-out path.
        const val UNSAVED_TOKEN_WARNING =
            "The account is unlocked, but this device couldn't save its new sign-in. The next time the app opens it may ask you to sign in again: do so."

        /// What a status says about the lock: locked, not locked, or nothing.
        /// A 401 says nothing: a lock removes a device's sign-in, so the token
        /// is refused while the account is still locked.
        fun verdict(status: Int): Boolean? = when {
            status == 423 -> true
            status == 401 || status == 429 || status >= 500 -> null
            else -> false
        }
    }
}
