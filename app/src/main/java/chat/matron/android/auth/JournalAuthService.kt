package chat.matron.android.auth

import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.LoginResponse
import chat.matron.android.journal.MatronJson
import chat.matron.android.journal.TwoFactorSetup
import chat.matron.android.models.MatronDebug
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
            return session(homeserverURL, username, login)
        } catch (e: JournalApiError.TwoFactorRequired) {
            throw AuthError.TwoFactorRequired(e.ticket, e.enrolment)
        } catch (e: JournalApiError.BadCredentials) {
            throw AuthError.InvalidCredentials
        } catch (e: JournalApiError.LockedOut) {
            throw AuthError.Unexpected("Too many attempts — try again in ${e.retryAfterSeconds}s")
        } catch (e: JournalApiError.RateLimited) {
            throw AuthError.Unexpected("Too many attempts — try again in a minute")
        } catch (e: JournalApiError) {
            // The human `message`, never `toString()` — the raw form leaks
            // class internals like "JournalApiError$Http" into the sign-in form.
            throw AuthError.Unexpected(e.message ?: "Couldn't reach the server.")
        }
    }

    override suspend fun verifyTwoFactor(
        homeserverURL: HttpUrl,
        username: String,
        ticket: String,
        code: String,
    ): TwoFactorSignIn = twoFactorStep {
        val answer = JournalApi(homeserverURL, client).loginTwoFactor(ticket, code)
        TwoFactorSignIn(
            session = session(homeserverURL, username, answer.login),
            usedRecoveryCode = answer.usedRecoveryCode,
            recoveryCodesLeft = answer.recoveryCodesLeft,
        )
    }

    override suspend fun beginTwoFactorEnrolment(homeserverURL: HttpUrl, ticket: String): TwoFactorSetup =
        twoFactorStep { JournalApi(homeserverURL, client).twoFactorSetupAtLogin(ticket) }

    override suspend fun finishTwoFactorEnrolment(
        homeserverURL: HttpUrl,
        username: String,
        ticket: String,
        code: String,
    ): TwoFactorEnrolled = twoFactorStep {
        val answer = JournalApi(homeserverURL, client).twoFactorEnableAtLogin(ticket, code)
        TwoFactorEnrolled(session(homeserverURL, username, answer.login), answer.recoveryCodes)
    }

    override suspend fun passwordResetAvailable(rawURL: String): Boolean {
        val url = runCatching { ServerURLValidator.normalize(rawURL) }.getOrNull() ?: return false
        return try {
            JournalApi(url, client).passwordResetEnabled()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun requestPasswordReset(homeserverURL: HttpUrl, identifier: String) {
        try {
            JournalApi(homeserverURL, client).requestPasswordReset(identifier)
        } catch (e: JournalApiError.RateLimited) {
            throw AuthError.RateLimited
        } catch (e: JournalApiError) {
            throw AuthError.Unexpected(e.message ?: "Couldn't reach the server.")
        }
    }

    /// The code steps' refusals, as the sign-in form tells them apart.
    private suspend fun <T> twoFactorStep(body: suspend () -> T): T = try {
        body()
    } catch (e: JournalApiError.BadCode) {
        throw AuthError.WrongCode(e.ticketValid)
    } catch (e: JournalApiError.TwoFactorTicketInvalid) {
        throw AuthError.TwoFactorExpired
    } catch (e: JournalApiError.LockedOut) {
        throw AuthError.LockedOut(e.retryAfterSeconds)
    } catch (e: JournalApiError.RateLimited) {
        throw AuthError.Unexpected("Too many attempts — try again in a minute")
    } catch (e: JournalApiError.Http) {
        // 503 with no `message`: the server has no key to keep secrets with.
        if (e.code == "two_factor_unavailable") {
            throw AuthError.Unexpected("Two-factor sign-in isn't available on this server.")
        }
        throw AuthError.Unexpected(e.message ?: "Couldn't reach the server.")
    } catch (e: JournalApiError) {
        throw AuthError.Unexpected(e.message ?: "Couldn't reach the server.")
    }

    private fun session(homeserverURL: HttpUrl, username: String, login: LoginResponse) = UserSession(
        userID = username,
        deviceID = login.deviceID.toString(),
        homeserverURL = homeserverURL.toString(),
        accessToken = login.token,
    )

    override suspend fun restoreSession(): UserSession? {
        val json = sessionStore.get(sessionKey) ?: return null
        return runCatching { MatronJson.decodeFromString<UserSession>(json) }
            .onFailure { MatronDebug.breadcrumb("restoreSession: corrupt persisted session — signing out: $it") }
            .getOrNull()
    }

    override fun persist(session: UserSession) {
        sessionStore.set(MatronJson.encodeToString(session), sessionKey)
    }

    override fun clearSession() {
        sessionStore.delete(sessionKey)
    }
}
