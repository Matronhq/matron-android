package chat.matron.android.auth

import chat.matron.android.models.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthServiceProtocolTest {
    @Test
    fun fakeCanProbe() = runBlocking {
        val fake = FakeAuthService()
        fake.stubbedProbe = Result.success(ServerCapabilities(supportsPasswordLogin = true, supportsSSO = false))
        val caps = fake.probe("https://matrix.example.com")
        assertTrue(caps.supportsPasswordLogin)
        assertFalse(caps.supportsSSO)
    }

    @Test
    fun fakeCapturesSsoFlag() = runBlocking {
        val fake = FakeAuthService()
        fake.stubbedProbe = Result.success(ServerCapabilities(supportsPasswordLogin = true, supportsSSO = true))
        val caps = fake.probe("https://matrix.example.com")
        assertTrue(caps.supportsSSO)
    }

    @Test
    fun fakePersistRetainsSessions() {
        val fake = FakeAuthService()
        val session = UserSession(
            userID = "@alice:example.com",
            deviceID = "DEV1",
            homeserverURL = "https://matrix.example.com",
            accessToken = "tok",
        )
        fake.persist(session)
        assertEquals(listOf(session), fake.persistedSessions)
    }
}
