package chat.matron.android.auth

import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.MatronJson
import chat.matron.android.models.UserSession
import chat.matron.android.storage.SessionStore
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/// [AuthService] against the matron-journal server: `POST /login` issues a
/// long-lived device token which maps onto [UserSession.accessToken]. Ported
/// from matron-apple's `JournalAuthService`.
class JournalAuthService(
    private val sessionStore: SessionStore,
    private val client: OkHttpClient = OkHttpClient(),
) : AuthService {
    private val sessionKey = "matron.journal.session"

    override suspend fun probe(rawURL: String): ServerCapabilities {
        val url = try {
            ServerURLValidator.normalize(rawURL)
        } catch (e: ServerURLValidator.ValidationError) {
            throw AuthError.InvalidServerURL(e)
        }
        val api = JournalApi(url, client)
        try {
            api.snapshot() // unauthenticated on purpose
            throw AuthError.ServerUnreachable // a journal server must 401 here
        } catch (e: AuthError) {
            throw e
        } catch (e: JournalApiError) {
            if (e is JournalApiError.Unauthenticated) {
                return ServerCapabilities(supportsPasswordLogin = true, supportsSSO = false)
            }
            throw AuthError.ServerUnreachable
        } catch (e: Throwable) {
            throw AuthError.ServerUnreachable
        }
    }

    override suspend fun loginPassword(
        homeserverURL: HttpUrl,
        username: String,
        password: String,
        initialDeviceDisplayName: String,
    ): UserSession {
        val api = JournalApi(homeserverURL, client)
        try {
            val login = api.login(username, password, initialDeviceDisplayName)
            return UserSession(
                userID = username,
                deviceID = login.deviceID.toString(),
                homeserverURL = homeserverURL.toString(),
                accessToken = login.token,
            )
        } catch (e: JournalApiError.BadCredentials) {
            throw AuthError.InvalidCredentials
        } catch (e: JournalApiError.LockedOut) {
            throw AuthError.Unexpected("Too many attempts — try again in ${e.retryAfterSeconds}s")
        } catch (e: JournalApiError.RateLimited) {
            throw AuthError.Unexpected("Too many attempts — try again in a minute")
        } catch (e: JournalApiError) {
            throw AuthError.Unexpected(e.toString())
        }
    }

    override suspend fun restoreSession(): UserSession? {
        val json = sessionStore.get(sessionKey) ?: return null
        return runCatching { MatronJson.decodeFromString<UserSession>(json) }.getOrNull()
    }

    override fun persist(session: UserSession) {
        sessionStore.set(MatronJson.encodeToString(session), sessionKey)
    }

    override fun clearSession() {
        sessionStore.delete(sessionKey)
    }
}
