package chat.matron.android.viewmodels

import chat.matron.android.auth.AuthError
import chat.matron.android.auth.AuthService
import chat.matron.android.auth.ServerURLValidator
import chat.matron.android.journal.TwoFactorSetup
import chat.matron.android.models.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/// Drives the sign-in form. Ported from matron-apple's `SignInViewModel`.
/// `deviceDisplayName` is platform-specific ("Matron Android" from the app), so
/// the view model itself stays target-agnostic.
///
/// Two-factor: when the password is right but the journal wants a code, the
/// form moves on to [State.TwoFactorCode]; when it wants two-factor set up
/// first, to [State.TwoFactorEnrol] and then [State.RecoveryCodes]. A ticket
/// that runs out (too many wrong codes, or time) drops back to the password
/// form with [State.Error]. Once the journal has issued a token after a code
/// step the session is persisted at once — two-factor is on (or a recovery
/// code is spent) by then, so losing the token would cost the user more than
/// an extra screen — and published on [State.SignedIn].
///
/// "Forgot password?": offered only while the journal at the typed server
/// says it mails reset links ([forgotPasswordAvailable], from
/// [checkPasswordReset]). It opens [forgotPassword], a small form that asks
/// for a link and then always says the same thing, so it never reveals
/// whether an account exists. The link itself is finished in Matron Web.
class SignInViewModel(
    private val auth: AuthService,
    private val deviceDisplayName: String,
) {
    sealed interface State {
        data object Idle : State
        data object Busy : State
        data class Error(val message: String) : State
        data class SignedIn(val session: UserSession) : State

        /// "Two-factor code": a 6-digit code from the authenticator app, or a
        /// recovery code.
        data class TwoFactorCode(val busy: Boolean = false, val error: String? = null) : State

        /// Two-factor setup at sign-in. [setup] is null while it loads, or
        /// when loading it failed ([error] says why, and [retryEnrolment]
        /// tries again).
        data class TwoFactorEnrol(
            val setup: TwoFactorSetup?,
            val busy: Boolean = false,
            val error: String? = null,
        ) : State

        /// Setup done: the recovery codes, shown this once, until the user
        /// says they have saved them.
        data class RecoveryCodes(val codes: List<String>, val session: UserSession) : State

        /// Signed in with a recovery code: say how many are left before
        /// going on.
        data class RecoveryCodeUsed(val codesLeft: Int?, val session: UserSession) : State
    }

    /// The "Forgot password?" form. [sent]: the journal took the request;
    /// the form then shows [FORGOT_SENT] and "Back to sign in" only.
    data class ForgotPassword(
        val identifier: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        val sent: Boolean = false,
    ) {
        val canSubmit: Boolean get() = !busy && !sent && identifier.trim().isNotEmpty()
    }

    /// User-editable inputs (Swift `public var`, @Observable-tracked).
    // Deliberately empty: shipping a pre-filled default (formerly a dead
    // placeholder host) meant the field looked already-answered — App Review
    // signed in over it and hit "Couldn't reach that server."
    var serverURL: String = ""
    var username: String = ""
    var password: String = ""

    /// The two-factor code field (code step and setup).
    var code: String = ""

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _forgotPasswordAvailable = MutableStateFlow(false)
    /// Whether to show "Forgot password?" under the password field.
    val forgotPasswordAvailable: StateFlow<Boolean> = _forgotPasswordAvailable.asStateFlow()

    private val _forgotPassword = MutableStateFlow<ForgotPassword?>(null)
    /// The open "Forgot password?" form, or null.
    val forgotPassword: StateFlow<ForgotPassword?> = _forgotPassword.asStateFlow()

    /// The sign-in a two-factor step is finishing.
    private data class Pending(val url: HttpUrl, val username: String, val ticket: String)
    private var pending: Pending? = null

    /// Bumped by [cancelTwoFactor] and by every way out of a step: an answer
    /// that lands after it is dropped, so a cancelled request can neither
    /// sign in nor put a dead step back on screen.
    private var attempt = 0L

    suspend fun submit() {
        if (serverURL.trim().isEmpty() || username.isEmpty() || password.isEmpty()) return
        _state.value = State.Busy
        try {
            auth.probe(serverURL)
            val url = ServerURLValidator.normalize(serverURL).toHttpUrl()
            try {
                val session = auth.loginPassword(
                    homeserverURL = url,
                    username = username,
                    password = password,
                    initialDeviceDisplayName = deviceDisplayName,
                )
                signIn(session)
            } catch (twoFactor: AuthError.TwoFactorRequired) {
                pending = Pending(url, username, twoFactor.ticket)
                code = ""
                if (twoFactor.enrolment) {
                    beginEnrolment()
                } else {
                    _state.value = State.TwoFactorCode()
                }
            }
        } catch (error: AuthError) {
            _state.value = State.Error(message(error))
        } catch (error: Throwable) {
            _state.value = State.Error("Unexpected error: ${error.message ?: error}")
        }
    }

    /// The code step's "Verify".
    suspend fun submitCode() {
        val step = _state.value as? State.TwoFactorCode ?: return
        val p = pending ?: return
        val entered = code.trim()
        if (step.busy || entered.isEmpty()) return
        val mine = attempt
        _state.value = State.TwoFactorCode(busy = true)
        try {
            val result = auth.verifyTwoFactor(p.url, p.username, p.ticket, entered)
            if (mine != attempt) return
            pending = null
            if (result.usedRecoveryCode) {
                auth.persist(result.session)
                _state.value = State.RecoveryCodeUsed(result.recoveryCodesLeft, result.session)
            } else {
                signIn(result.session)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (mine == attempt) codeStepFailed(error) { message -> State.TwoFactorCode(error = message) }
        }
    }

    /// Setup at sign-in, step one: fetch the secret for the QR code.
    suspend fun retryEnrolment() {
        val step = _state.value as? State.TwoFactorEnrol ?: return
        if (step.busy || step.setup != null) return
        beginEnrolment()
    }

    private suspend fun beginEnrolment() {
        val p = pending ?: return
        val mine = attempt
        _state.value = State.TwoFactorEnrol(setup = null, busy = true)
        try {
            val setup = auth.beginTwoFactorEnrolment(p.url, p.ticket)
            if (mine == attempt) _state.value = State.TwoFactorEnrol(setup = setup)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (mine == attempt) {
                codeStepFailed(error) { message -> State.TwoFactorEnrol(setup = null, error = message) }
            }
        }
    }

    /// Setup at sign-in, step two: the app's first code turns two-factor on.
    suspend fun confirmEnrolment() {
        val step = _state.value as? State.TwoFactorEnrol ?: return
        val setup = step.setup ?: return
        val p = pending ?: return
        val entered = code.trim()
        if (step.busy || entered.isEmpty()) return
        val mine = attempt
        _state.value = step.copy(busy = true, error = null)
        try {
            val result = auth.finishTwoFactorEnrolment(p.url, p.username, p.ticket, entered)
            if (mine != attempt) return
            pending = null
            code = ""
            auth.persist(result.session)
            _state.value = State.RecoveryCodes(result.recoveryCodes, result.session)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (mine == attempt) {
                codeStepFailed(error) { message -> State.TwoFactorEnrol(setup = setup, error = message) }
            }
        }
    }

    /// "I've saved these" on the recovery codes, or "Continue" after the
    /// recovery-code warning: the sign-in is done.
    fun finishTwoFactor() {
        val session = when (val s = _state.value) {
            is State.RecoveryCodes -> s.session
            is State.RecoveryCodeUsed -> s.session
            else -> return
        }
        _state.value = State.SignedIn(session)
    }

    /// Back to the password form; the ticket is dropped, and so is any
    /// answer still on its way. Only from the code step or setup: after a
    /// token has been issued there is nothing to cancel.
    fun cancelTwoFactor() {
        val s = _state.value
        if (s !is State.TwoFactorCode && s !is State.TwoFactorEnrol) return
        attempt++
        pending = null
        code = ""
        _state.value = State.Idle
    }

    /// Asks the journal at the current [serverURL] whether it mails reset
    /// links. The screen calls it (debounced) as the server field changes; an
    /// answer for a server the user has since changed is dropped.
    suspend fun checkPasswordReset() {
        val asked = serverURL.trim()
        if (asked.isEmpty()) {
            _forgotPasswordAvailable.value = false
            return
        }
        val available = auth.passwordResetAvailable(asked)
        if (serverURL.trim() == asked) _forgotPasswordAvailable.value = available
    }

    /// "Forgot password?" — the form starts with the username, if typed.
    fun openForgotPassword() {
        if (!_forgotPasswordAvailable.value) return
        _forgotPassword.value = ForgotPassword(identifier = username.trim())
    }

    fun setForgotIdentifier(value: String) {
        _forgotPassword.value = _forgotPassword.value?.copy(identifier = value, error = null)
    }

    /// "Back to sign in".
    fun closeForgotPassword() {
        _forgotPassword.value = null
    }

    /// "Send reset link".
    suspend fun submitForgotPassword() {
        val form = _forgotPassword.value ?: return
        if (!form.canSubmit) return
        _forgotPassword.value = form.copy(busy = true, error = null)
        val result = try {
            val url = ServerURLValidator.normalize(serverURL).toHttpUrl()
            auth.requestPasswordReset(url, form.identifier.trim())
            form.copy(busy = false, sent = true)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (invalid: ServerURLValidator.ValidationError) {
            form.copy(busy = false, error = message(AuthError.InvalidServerURL(invalid)))
        } catch (error: AuthError) {
            form.copy(busy = false, error = message(error))
        } catch (error: Throwable) {
            form.copy(busy = false, error = "Unexpected error: ${error.message ?: error}")
        }
        // Closed meanwhile: leave it closed.
        if (_forgotPassword.value != null) _forgotPassword.value = result
    }

    private fun signIn(session: UserSession) {
        auth.persist(session)
        _state.value = State.SignedIn(session)
    }

    /// A refused code step: a wrong code, a lockout or a network failure stay
    /// on the step ([stay]); a spent or expired ticket goes back to the
    /// password.
    private fun codeStepFailed(error: Throwable, stay: (String) -> State) {
        _state.value = when (error) {
            is AuthError.WrongCode ->
                if (error.ticketValid) {
                    stay("Wrong code. Check your authenticator app and try again.")
                } else {
                    backToPassword("Too many wrong codes. Sign in again.")
                }
            AuthError.TwoFactorExpired -> backToPassword("The sign-in timed out. Sign in again.")
            is AuthError -> stay(message(error))
            else -> stay("Unexpected error: ${error.message ?: error}")
        }
    }

    private fun backToPassword(message: String): State {
        attempt++
        pending = null
        code = ""
        return State.Error(message)
    }

    private fun message(error: AuthError): String = when (error) {
        is AuthError.InvalidServerURL -> "That doesn't look like a valid server URL."
        AuthError.ServerUnreachable -> "Couldn't reach that server."
        AuthError.SsoNotSupported -> "SSO is not supported by this server."
        AuthError.InvalidCredentials -> "Invalid credentials."
        is AuthError.Unexpected -> error.detail
        is AuthError.TwoFactorRequired -> "Two-factor sign-in needed."
        is AuthError.WrongCode -> "Wrong code."
        AuthError.TwoFactorExpired -> "The sign-in timed out. Sign in again."
        is AuthError.LockedOut -> "Too many attempts. Try again in ${retryAfterText(error.retryAfterSeconds)}."
        AuthError.RateLimited -> "Too many requests. Try again in a few minutes."
    }

    companion object {
        const val FORGOT_LINK = "Forgot password?"
        const val FORGOT_IDENTIFIER = "Username or email"
        const val FORGOT_SUBMIT = "Send reset link"
        const val FORGOT_BACK = "Back to sign in"
        const val FORGOT_SENT =
            "If that account has an email address, we've sent a link to reset its password. " +
                "It works once, for 30 minutes."

        /// "45 seconds", "5 minutes", "2 hours".
        fun retryAfterText(seconds: Int): String = when {
            seconds < 60 -> plural(maxOf(seconds, 1), "second")
            seconds < 3600 -> plural((seconds + 59) / 60, "minute")
            else -> plural((seconds + 3599) / 3600, "hour")
        }

        /// The note after a recovery-code sign-in.
        fun recoveryCodesLeftText(left: Int?): String = when (left) {
            null -> "You signed in with a recovery code. That code can't be used again."
            0 -> "You signed in with your last recovery code. Make new ones in Matron Web now."
            1 -> "You signed in with a recovery code. You have 1 recovery code left. Make new ones in Matron Web."
            else -> "You signed in with a recovery code. You have $left recovery codes left."
        }

        private fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"
    }
}
