package chat.matron.android.auth

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
}

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

    /// Restores a previously persisted session, or `null` if none stored.
    suspend fun restoreSession(): UserSession?

    /// Persists a session to the secure store.
    fun persist(session: UserSession)

    /// Clears the persisted session (sign out).
    fun clearSession()
}
