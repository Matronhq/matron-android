package chat.matron.android.auth

import chat.matron.android.storage.InMemorySessionStore
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// Ported from matron-apple's `JournalAuthServiceTests`. The Apple suite stubs
/// `URLProtocol` (host-agnostic); here a `MockWebServer` on http-localhost backs
/// the [JournalApi] (localhost http is allowed by [ServerURLValidator]).
class JournalAuthServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var store: InMemorySessionStore
    private lateinit var service: JournalAuthService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store = InMemorySessionStore()
        service = JournalAuthService(sessionStore = store)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): HttpUrl = server.url("/")
    private fun rawBase(): String = server.url("/").toString().trimEnd('/')

    @Test
    fun probeRecognisesJournalServer() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthenticated"}"""))
        val caps = service.probe(rawBase())
        assertEquals(true, caps.supportsPasswordLogin)
        assertEquals(false, caps.supportsSSO)
    }

    @Test
    fun loginMapsToUserSessionAndPersistRoundTrips() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"tok1","device_id":7,"user_id":3}"""))
        val session = service.loginPassword(
            homeserverURL = baseUrl(),
            username = "dan", password = "pw", initialDeviceDisplayName = "Matron Android",
        )
        assertEquals("dan", session.userID)
        assertEquals("7", session.deviceID)
        assertEquals("tok1", session.accessToken)

        service.persist(session)
        assertEquals(session, service.restoreSession())
        service.clearSession()
        assertNull(service.restoreSession())
    }

    @Test
    fun badCredentialsMapsToAuthError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"bad_credentials"}"""))
        try {
            service.loginPassword(baseUrl(), "dan", "wrong", "x")
            fail("expected throw")
        } catch (e: AuthError) {
            assertEquals(AuthError.InvalidCredentials, e)
        }
    }

    @Test
    fun probeRejectsNonJournalServer() = runBlocking {
        // A 200 from /snapshot means it is NOT a journal server (they 401).
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"whatever":"ok"}"""))
        try {
            service.probe(rawBase())
            fail("expected serverUnreachable")
        } catch (e: AuthError) {
            assertEquals(AuthError.ServerUnreachable, e)
        }
    }

    @Test
    fun lockedOutMapsToUnexpectedWithRetryMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"locked_out","retry_after":90}"""))
        try {
            service.loginPassword(baseUrl(), "dan", "pw", "x")
            fail("expected throw")
        } catch (e: AuthError.Unexpected) {
            assertEquals(true, e.detail.contains("90"))
        }
    }
}
