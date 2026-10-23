package chat.matron.android.journal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// `POST /password`: the request it sends and how each refusal comes back.
class ChangePasswordApiTest {
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
            api().changePassword("old-secret", "new-secret", signOutOthers = true)
            fail("expected a throw")
            throw AssertionError()
        } catch (e: JournalApiError) {
            return e
        }
    }

    @Test
    fun sendsBothPasswordsAndTheFlag() = runBlocking {
        server.enqueue(json(200, """{"ok":true,"signed_out":2}"""))
        val answer = api().changePassword("old-secret", "new-secret", signOutOthers = true)
        assertEquals(PasswordChange(signedOut = 2), answer)
        val req = server.takeRequest()
        assertEquals("/password", req.path)
        assertEquals("POST", req.method)
        assertEquals("Bearer t", req.getHeader("Authorization"))
        val obj = parseJsonObjectOrNull(req.body.readUtf8())!!
        assertEquals("old-secret", obj.stringOrNull("old_password"))
        assertEquals("new-secret", obj.stringOrNull("new_password"))
        assertEquals(true, obj.boolOrNull("sign_out_others"))
    }

    @Test
    fun sendsTheFlagOffToo() = runBlocking {
        server.enqueue(json(200, """{"ok":true,"signed_out":0}"""))
        assertEquals(PasswordChange(0), api().changePassword("old-secret", "new-secret", signOutOthers = false))
        val obj = parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!
        assertEquals(false, obj.boolOrNull("sign_out_others"))
    }

    @Test
    fun anOlderJournalLeavesSignedOutOut() = runBlocking {
        server.enqueue(json(200, """{"ok":true}"""))
        assertEquals(PasswordChange(0), api().changePassword("old-secret", "new-secret", signOutOthers = true))
    }

    @Test
    fun aWrongCurrentPasswordIsNotASignedOutSession() = runBlocking {
        val e = refusal(401, """{"error":"bad_password"}""")
        assertEquals(JournalApiError.BadPassword, e)
        assertNotEquals(JournalApiError.Unauthenticated, e)
    }

    @Test
    fun aBare401IsStillASignedOutSession() = runBlocking {
        assertEquals(JournalApiError.Unauthenticated, refusal(401, """{"error":"unauthorized"}"""))
    }

    @Test
    fun aShortNewPasswordIsWeak() = runBlocking {
        assertEquals(JournalApiError.WeakPassword, refusal(400, """{"error":"weak_password"}"""))
    }

    @Test
    fun lockedOutCarriesTheWait() = runBlocking {
        assertEquals(JournalApiError.LockedOut(300), refusal(429, """{"error":"locked_out","retry_after":300}"""))
    }

    @Test
    fun otherRefusals() = runBlocking {
        val bad = refusal(400, """{"error":"bad_request"}""")
        assertEquals(400, (bad as JournalApiError.Http).status)
        assertEquals("bad_request", bad.code)
        // An agent device may not change the password.
        assertEquals(JournalApiError.Forbidden, refusal(403, """{"error":"forbidden"}"""))
    }
}
