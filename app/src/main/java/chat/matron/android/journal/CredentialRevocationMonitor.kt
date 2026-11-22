package chat.matron.android.journal

import android.os.SystemClock

/// What the live connection learned about this device's credentials.
sealed interface CredentialSignal {
    /// The journal accepted the token (`hello_ok`).
    data object Accepted : CredentialSignal

    /// The journal refused the token at the handshake (`error` code `auth`):
    /// the device was signed out, from another device or by the server.
    /// Definitive.
    data object Rejected : CredentialSignal

    /// The journal said the whole account is locked (a hello refused with
    /// `account_locked`, or a `panic` frame). That says nothing about this
    /// device's token on its own, so it is never taken as a sign-out: a
    /// frozen device keeps its token and can unlock the account. A device
    /// the lock does sign out finds out on its next hello, refused `auth`.
    data object AccountLocked : CredentialSignal
}

/// Decides when this device's credentials have been revoked, so the app can
/// run its normal sign-out and go back to the sign-in screen instead of
/// sitting offline over a local copy it can no longer sync.
///
/// Two inputs:
/// - the WebSocket handshake ([handle]): a [CredentialSignal.Rejected] hello
///   is the journal saying in so many words that this token is no longer
///   valid, so it fires at once;
/// - REST responses to token-bearing requests ([noteResponse]): a single 401
///   is not enough (a proxy hiccup or a request racing a server restart can
///   produce one), so it fires only once 401s keep coming: [rejectionThreshold]
///   in a row, spread over at least [minimumSpanMillis], with nothing in
///   between that shows the token still works. Any answer the server could
///   only give a signed-in caller (2xx, 403, 404 …) or an accepted hello
///   resets the count. 403 is never a revoke: it is one resource refusing an
///   otherwise valid device. Network failures, 429 and 5xx say nothing about
///   the token and leave the count alone.
///
/// Fires [onRevoked] at most once per monitor; [cancel] disarms it (the
/// sign-out teardown calls it so its own calls with the dead token cannot
/// set anything off again). Thread-safe: inputs arrive from OkHttp's threads
/// and the sync engine concurrently, and [onRevoked] runs on the caller's
/// thread, outside the lock.
class CredentialRevocationMonitor(
    rejectionThreshold: Int = 3,
    private val minimumSpanMillis: Long = 10_000,
    /// A monotonic clock in milliseconds (never the wall clock: a clock
    /// change must not make a burst of 401s look spread out).
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    private val onRevoked: () -> Unit,
) {
    private val rejectionThreshold = rejectionThreshold.coerceAtLeast(1)
    private val lock = Any()

    // Guarded by `lock`.
    private var consecutiveRejections = 0
    private var firstRejectionAt: Long? = null
    private var armed = true
    private var fired = false

    /// Whether [onRevoked] has run.
    val hasFired: Boolean get() = synchronized(lock) { fired }

    /// Whether [cancel] has disarmed this monitor.
    val isArmed: Boolean get() = synchronized(lock) { armed }

    /// The sync engine's handshake outcome.
    fun handle(signal: CredentialSignal) {
        when (signal) {
            CredentialSignal.Accepted -> synchronized(lock) { resetLocked() }
            CredentialSignal.Rejected -> fireOnce()
            CredentialSignal.AccountLocked -> Unit
        }
    }

    /// The HTTP status of a REST request that carried this device's token.
    fun noteResponse(status: Int) {
        val shouldFire = synchronized(lock) {
            when {
                status == 401 -> {
                    val at = now()
                    val first = firstRejectionAt ?: at.also { firstRejectionAt = it }
                    consecutiveRejections += 1
                    consecutiveRejections >= rejectionThreshold && at - first >= minimumSpanMillis
                }
                // Transport-level failures never reach here; these say
                // nothing about the token either way.
                status == 429 || status >= 500 -> false
                else -> {
                    resetLocked()
                    false
                }
            }
        }
        if (shouldFire) fireOnce()
    }

    /// Stops this monitor from ever firing (sign-out already under way).
    fun cancel() {
        synchronized(lock) { armed = false }
    }

    private fun resetLocked() {
        consecutiveRejections = 0
        firstRejectionAt = null
    }

    private fun fireOnce() {
        synchronized(lock) {
            if (!armed || fired) return
            fired = true
        }
        onRevoked()
    }
}
