package chat.matron.android.journal

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// `GET`/`PUT /account/email`: the shapes and how each refusal comes back.
class AccountEmailApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(): JournalApi = JournalApi(server.url("/").toString(), token = "t")

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json").setBody(body)

    private suspend fun refusal(code: Int, body: String): JournalApiError {
        server.enqueue(json(code, body))
        try {
            api().setAccountEmail("secret-pw", "alice@example.com")
            fail("expected a throw")
            throw AssertionError()
        } catch (e: JournalApiError) {
            return e
        }
    }

    @Test
    fun readsTheAddress() = runBlocking {
        server.enqueue(json(200, """{"email":"alice@example.com","reset_by_email":true}"""))
        assertEquals(AccountEmail("alice@example.com", true), api().accountEmail())
        val req = server.takeRequest()
        assertEquals("/account/email", req.path)
        assertEquals("Bearer t", req.getHeader("Authorization"))

        server.enqueue(json(200, """{"email":null,"reset_by_email":false}"""))
        assertEquals(AccountEmail(null, false), api().accountEmail())
    }

    @Test
    fun anOlderJournalIsNotFound() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try {
            api().accountEmail(); fail("expected a throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.NotFound, e)
        }
    }

    @Test
    fun setsAndRemoves() = runBlocking {
        server.enqueue(json(200, """{"email":"alice@example.com"}"""))
        assertEquals("alice@example.com", api().setAccountEmail("secret-pw", "alice@example.com"))
        var req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/account/email", req.path)
        var obj = parseJsonObjectOrNull(req.body.readUtf8())!!
        assertEquals("secret-pw", obj.stringOrNull("password"))
        assertEquals("alice@example.com", obj.stringOrNull("email"))

        server.enqueue(json(200, """{"email":null}"""))
        assertNull(api().setAccountEmail("secret-pw", null))
        req = server.takeRequest()
        obj = parseJsonObjectOrNull(req.body.readUtf8())!!
        assertEquals(JsonNull, obj["email"])
    }

    @Test
    fun aWrongPasswordIsNotASignedOutSession() = runBlocking {
        val e = refusal(401, """{"error":"bad_password"}""")
        assertEquals(JournalApiError.BadPassword, e)
        assertNotEquals(JournalApiError.Unauthenticated, e)
    }

    @Test
    fun refusals() = runBlocking {
        assertEquals(JournalApiError.BadEmail, refusal(400, """{"error":"bad_email"}"""))
        assertEquals(JournalApiError.EmailTaken, refusal(409, """{"error":"email_taken"}"""))
        assertEquals(JournalApiError.LockedOut(120), refusal(429, """{"error":"locked_out","retry_after":120}"""))
        // Other conflicts keep their old meaning.
        assertEquals(JournalApiError.Conflict, refusal(409, """{"error":"conflict"}"""))
    }
}
