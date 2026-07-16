package chat.matron.android.auth

import chat.matron.android.models.UserSession
import okhttp3.HttpUrl

/// Test double for [AuthService], ported from matron-apple's `FakeAuthService`.
/// Reused by the view-model stage.
class FakeAuthService : AuthService {
    var stubbedProbe: Result<ServerCapabilities> = Result.failure(AuthError.Unexpected("not stubbed"))
    var stubbedLogin: Result<UserSession> = Result.failure(AuthError.Unexpected("not stubbed"))
    var stubbedRestore: Result<UserSession?> = Result.success(null)
    val persistedSessions = mutableListOf<UserSession>()
    var clearCallCount = 0

    override suspend fun probe(rawURL: String): ServerCapabilities = stubbedProbe.getOrThrow()

    override suspend fun loginPassword(
        homeserverURL: HttpUrl,
        username: String,
        password: String,
        initialDeviceDisplayName: String,
    ): UserSession = stubbedLogin.getOrThrow()

    override suspend fun restoreSession(): UserSession? = stubbedRestore.getOrThrow()

    override fun persist(session: UserSession) {
        persistedSessions.add(session)
    }

    override fun clearSession() {
        clearCallCount++
    }
}
