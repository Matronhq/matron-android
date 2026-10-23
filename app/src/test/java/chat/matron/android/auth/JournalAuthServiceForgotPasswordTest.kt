package chat.matron.android.auth

import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.storage.InMemorySessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// "Forgot password?" on the wire: whether the journal offers it, and the
/// unauthenticated request for a reset link.
class JournalAuthServiceForgotPasswordTest {
    private lateinit var server: MockWebServer
    private lateinit var service: JournalAuthService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = JournalAuthService(sessionStore = InMemorySessionStore())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun respond(status: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    private suspend fun expectAuthError(block: suspend () -> Unit): AuthError {
        try {
            block()
        } catch (e: AuthError) {
            return e
        }
        fail("expected an AuthError")
        throw AssertionError()
    }

    @Test
    fun offeredOnlyWhenTheJournalSaysEnabled() = runBlocking {
        respond(200, """{"enabled":true}""")
        assertTrue(service.passwordResetAvailable(server.url("/").toString()))
        val req = server.takeRequest()
        assertEquals("/password/forgot", req.path)
        assertEquals("GET", req.method)
        assertNull(req.getHeader("Authorization"))

        respond(200, """{"enabled":false}""")
        assertFalse(service.passwordResetAvailable(server.url("/").toString()))
        respond(200, """{}""")
        assertFalse(service.passwordResetAvailable(server.url("/").toString()))
    }

    @Test
    fun anOlderJournalOrNoAnswerIsOff() = runBlocking {
        respond(404, """{"error":"not_found"}""")
        assertFalse(service.passwordResetAvailable(server.url("/").toString()))
        respond(500, "oops")
        assertFalse(service.passwordResetAvailable(server.url("/").toString()))
        // Nothing listens there; an unusable address never asks.
        assertFalse(service.passwordResetAvailable("http://127.0.0.1:1/"))
        assertFalse(service.passwordResetAvailable("not a url"))
        assertFalse(service.passwordResetAvailable(""))
    }

    @Test
    fun sendsTheIdentifierUnauthenticated() = runBlocking {
        respond(200, """{"ok":true}""")
        service.requestPasswordReset(server.url("/"), "alice@example.com")
        val req = server.takeRequest()
        assertEquals("/password/forgot", req.path)
        assertEquals("POST", req.method)
        assertNull(req.getHeader("Authorization"))
        assertEquals("alice@example.com", parseJsonObjectOrNull(req.body.readUtf8())!!.stringOrNull("identifier"))
    }

    @Test
    fun refusals() = runBlocking {
        respond(429, """{"error":"rate_limited"}""")
        assertEquals(AuthError.RateLimited, expectAuthError { service.requestPasswordReset(server.url("/"), "alice") })

        respond(503, """{"error":"unavailable"}""")
        val off = expectAuthError { service.requestPasswordReset(server.url("/"), "alice") }
        assertTrue("got $off", off is AuthError.Unexpected)
    }
}
