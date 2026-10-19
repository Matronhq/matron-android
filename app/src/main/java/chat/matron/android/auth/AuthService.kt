package chat.matron.android.auth

import chat.matron.android.journal.TwoFactorSetup
import chat.matron.android.models.UserSession
import okhttp3.HttpUrl

/// Errors surfaced by [AuthService]. `data object`/`data class` cases give
/// value-equality so `assertEquals(AuthError.invalidCredentials, caught)` works,
/// mirroring the Swift `Equatable` enum.
sealed class AuthError : Exception() {
    data class InvalidServerURL(val error: ServerURLValidator.ValidationError) : AuthError()
    data object ServerUnreachable : AuthError()
    data object SsoNotSupported : AuthError()
    data object InvalidCredentials : AuthError()
    data class Unexpected(val detail: String) : AuthError()
    /// The password was right; the journal wants a two-factor code
    /// ([enrolment] false) or a two-factor setup first ([enrolment] true).
    /// [ticket] carries the sign-in through the code step.
    data class TwoFactorRequired(val ticket: String, val enrolment: Boolean) : AuthError()
    /// A code step refused the code. [ticketValid] false: the ticket has used
    /// up its tries, so the user starts again from the password.
    data class WrongCode(val ticketValid: Boolean) : AuthError()
    /// The sign-in ticket is unknown, expired or used: back to the password.
    data object TwoFactorExpired : AuthError()
    /// Too many wrong passwords or codes for this username.
    data class LockedOut(val retryAfterSeconds: Int) : AuthError()
}

/// A password sign-in finished with a two-factor code. [usedRecoveryCode]:
/// the code was a recovery code, so [recoveryCodesLeft] is worth showing.
data class TwoFactorSignIn(
    val session: UserSession,
    val usedRecoveryCode: Boolean,
    val recoveryCodesLeft: Int?,
)

/// Two-factor turned on at sign-in: the session, and the recovery codes the
/// journal shows this once.
data class TwoFactorEnrolled(val session: UserSession, val recoveryCodes: List<String>)

/// The login flows a server advertises. SSO redirect handling is deferred (the
/// Apple original only surfaces whether the server advertises SSO); the journal
/// server is password-only.
data class ServerCapabilities(
    val supportsPasswordLogin: Boolean,
    val supportsSSO: Boolean,
)

/// Cross-platform authentication surface. Ported from matron-apple's
/// `AuthService` protocol.
interface AuthService {
    /// Probes the server URL to determine supported login flows.
    suspend fun probe(rawURL: String): ServerCapabilities

    /// Logs in with username and password. Returns a [UserSession] on success.
    suspend fun loginPassword(
        homeserverURL: HttpUrl,
        username: String,
        password: String,
        initialDeviceDisplayName: String,
    ): UserSession

    /// Finishes a password sign-in that answered [AuthError.TwoFactorRequired]
    /// with a 6-digit code or a recovery code.
    suspend fun verifyTwoFactor(
        homeserverURL: HttpUrl,
        username: String,
        ticket: String,
        code: String,
    ): TwoFactorSignIn

    /// Enrolment at sign-in, step one: a new secret for the authenticator app.
    suspend fun beginTwoFactorEnrolment(homeserverURL: HttpUrl, ticket: String): TwoFactorSetup

    /// Enrolment at sign-in, step two: the app's first code turns two-factor
    /// on and signs in.
    suspend fun finishTwoFactorEnrolment(
        homeserverURL: HttpUrl,
        username: String,
        ticket: String,
        code: String,
    ): TwoFactorEnrolled

    /// Restores a previously persisted session, or `null` if none stored.
    suspend fun restoreSession(): UserSession?

    /// Persists a session to the secure store.
    fun persist(session: UserSession)

    /// Clears the persisted session (sign out).
    fun clearSession()
}
