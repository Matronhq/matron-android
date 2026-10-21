package chat.matron.android.auth

import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.TwoFactorEnabled
import chat.matron.android.journal.TwoFactorStatus
import chat.matron.android.storage.InMemorySessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// The two-factor routes on the wire: each journal answer maps onto the
/// [AuthError] the sign-in form tells apart.
class JournalAuthServiceTwoFactorTest {
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

    private fun baseUrl(): HttpUrl = server.url("/")

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
    fun loginThatNeedsACode_throwsTwoFactorRequiredWithTheTicket() = runBlocking {
        respond(401, """{"error":"two_factor_required","two_factor_ticket":"tkt","expires_in":300}""")
        val e = expectAuthError { service.loginPassword(baseUrl(), "alice", "pw", "Matron Android") }
        assertEquals(AuthError.TwoFactorRequired(ticket = "tkt", enrolment = false), e)
    }

    @Test
    fun loginThatNeedsEnrolment_throwsTwoFactorRequiredForEnrolment() = runBlocking {
        respond(401, """{"error":"two_factor_enrolment_required","two_factor_ticket":"tkt","expires_in":300}""")
        val e = expectAuthError { service.loginPassword(baseUrl(), "alice", "pw", "Matron Android") }
        assertEquals(AuthError.TwoFactorRequired(ticket = "tkt", enrolment = true), e)
    }

    @Test
    fun verify_postsTicketAndCode_andMapsTheToken() = runBlocking {
        respond(
            200,
            """{"token":"tok2","device_id":9,"user_id":3,"used_recovery_code":true,"recovery_codes_left":7}""",
        )
        val result = service.verifyTwoFactor(baseUrl(), "alice", "tkt", "123456")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/login/2fa", request.path)
        assertNull("unauthenticated", request.getHeader("Authorization"))
        assertEquals("""{"ticket":"tkt","code":"123456"}""", request.body.readUtf8())
        assertEquals("alice", result.session.userID)
        assertEquals("9", result.session.deviceID)
        assertEquals("tok2", result.session.accessToken)
        assertEquals(true, result.usedRecoveryCode)
        assertEquals(7, result.recoveryCodesLeft)
    }

    @Test
    fun verify_badCode_keepsTicketValidity() = runBlocking {
        respond(403, """{"error":"bad_code","ticket_valid":true}""")
        assertEquals(
            AuthError.WrongCode(ticketValid = true),
            expectAuthError { service.verifyTwoFactor(baseUrl(), "alice", "tkt", "000000") },
        )
        respond(403, """{"error":"bad_code","ticket_valid":false}""")
        assertEquals(
            AuthError.WrongCode(ticketValid = false),
            expectAuthError { service.verifyTwoFactor(baseUrl(), "alice", "tkt", "000000") },
        )
    }

    @Test
    fun verify_invalidTicket_isExpired() = runBlocking {
        respond(401, """{"error":"two_factor_ticket_invalid"}""")
        assertEquals(
            AuthError.TwoFactorExpired,
            expectAuthError { service.verifyTwoFactor(baseUrl(), "alice", "tkt", "123456") },
        )
    }

    @Test
    fun verify_lockedOut_carriesRetryAfter() = runBlocking {
        respond(429, """{"error":"locked_out","retry_after":120}""")
        assertEquals(
            AuthError.LockedOut(120),
            expectAuthError { service.verifyTwoFactor(baseUrl(), "alice", "tkt", "123456") },
        )
    }

    @Test
    fun enrolment_setupThenEnable() = runBlocking {
        respond(
            200,
            """{"secret":"JBSWY3DPEHPK3PXP","otpauth_uri":"otpauth://totp/Matron:alice@journal.example.com?secret=JBSWY3DPEHPK3PXP&issuer=Matron","issuer":"Matron","account":"alice@journal.example.com"}""",
        )
        val setup = service.beginTwoFactorEnrolment(baseUrl(), "tkt")
        assertEquals("/login/2fa/setup", server.takeRequest().path)
        assertEquals("JBSWY3DPEHPK3PXP", setup.secret)
        assertEquals(true, setup.otpauthURI.startsWith("otpauth://totp/"))

        respond(200, """{"token":"tok3","device_id":11,"user_id":3,"recovery_codes":["aaaa-bbbb-cccc-dddd","eeee-ffff-gggg-hhhh"]}""")
        val enrolled = service.finishTwoFactorEnrolment(baseUrl(), "alice", "tkt", "123456")
        val request = server.takeRequest()
        assertEquals("/login/2fa/enable", request.path)
        assertEquals("""{"ticket":"tkt","code":"123456"}""", request.body.readUtf8())
        assertEquals("tok3", enrolled.session.accessToken)
        assertEquals(listOf("aaaa-bbbb-cccc-dddd", "eeee-ffff-gggg-hhhh"), enrolled.recoveryCodes)
    }

    @Test
    fun enrolment_withAVerifyTicket_isExpired() = runBlocking {
        respond(401, """{"error":"two_factor_ticket_invalid"}""")
        assertEquals(AuthError.TwoFactorExpired, expectAuthError { service.beginTwoFactorEnrolment(baseUrl(), "tkt") })
    }

    @Test
    fun enrolment_onAServerWithoutTheKey_saysSoInWords() = runBlocking {
        respond(503, """{"error":"two_factor_unavailable"}""")
        assertEquals(
            AuthError.Unexpected("Two-factor sign-in isn't available on this server."),
            expectAuthError { service.beginTwoFactorEnrolment(baseUrl(), "tkt") },
        )
    }

    @Test
    fun status_decodesGet2fa() = runBlocking {
        respond(200, """{"enabled":true,"enabled_at":1760000000000,"recovery_codes_left":9,"required":true,"available":true}""")
        val api = JournalApi(baseUrl(), token = "tok")
        assertEquals(
            TwoFactorStatus(
                enabled = true,
                recoveryCodesLeft = 9,
                required = true,
                available = true,
                enabledAtMillis = 1760000000000,
            ),
            api.twoFactorStatus(),
        )
        val request = server.takeRequest()
        assertEquals("/2fa", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
    }

    @Test
    fun status_onAJournalWithoutTwoFactor_isNotFound() = runBlocking {
        respond(404, """{"error":"not_found"}""")
        try {
            JournalApi(baseUrl(), token = "tok").twoFactorStatus()
            fail("expected NotFound")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.NotFound, e)
        }
    }

    @Test
    fun settingsSetup_postsThePassword_andTheCurrentCodeOnlyWhenMoving() = runBlocking {
        val setupBody = """{"secret":"JBSWY3DPEHPK3PXP","otpauth_uri":"otpauth://totp/Matron:alice@example.com?secret=JBSWY3DPEHPK3PXP","issuer":"Matron","account":"alice@example.com"}"""
        val api = JournalApi(baseUrl(), token = "tok")
        respond(200, setupBody)
        assertEquals("JBSWY3DPEHPK3PXP", api.twoFactorSetup("pw", null).secret)
        var request = server.takeRequest()
        assertEquals("/2fa/setup", request.path)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals("""{"password":"pw"}""", request.body.readUtf8())

        respond(200, setupBody)
        api.twoFactorSetup("pw", "123456")
        request = server.takeRequest()
        assertEquals("""{"password":"pw","code":"123456"}""", request.body.readUtf8())
    }

    @Test
    fun settingsEnable_sendsSignOutOthers_andDecodesTheCodes() = runBlocking {
        respond(200, """{"recovery_codes":["aaaa-bbbb-cccc-dddd"],"signed_out":2}""")
        val enabled = JournalApi(baseUrl(), token = "tok").twoFactorEnable("123456", signOutOthers = true)
        val request = server.takeRequest()
        assertEquals("/2fa/enable", request.path)
        assertEquals("""{"code":"123456","sign_out_others":true}""", request.body.readUtf8())
        assertEquals(TwoFactorEnabled(listOf("aaaa-bbbb-cccc-dddd"), signedOut = 2), enabled)
    }

    @Test
    fun settingsRecoveryCodesAndDisable_postPasswordAndCode() = runBlocking {
        val api = JournalApi(baseUrl(), token = "tok")
        respond(200, """{"recovery_codes":["aaaa-bbbb-cccc-dddd","eeee-ffff-gggg-hhhh"]}""")
        assertEquals(listOf("aaaa-bbbb-cccc-dddd", "eeee-ffff-gggg-hhhh"), api.twoFactorRecoveryCodes("pw", "123456"))
        var request = server.takeRequest()
        assertEquals("/2fa/recovery-codes", request.path)
        assertEquals("""{"password":"pw","code":"123456"}""", request.body.readUtf8())

        respond(200, """{"ok":true}""")
        api.twoFactorDisable("pw", "123456")
        request = server.takeRequest()
        assertEquals("/2fa/disable", request.path)
        assertEquals("""{"password":"pw","code":"123456"}""", request.body.readUtf8())
    }

    @Test
    fun settingsRefusals_mapToTheirOwnErrors() = runBlocking {
        val api = JournalApi(baseUrl(), token = "tok")
        suspend fun refusal(block: suspend () -> Unit): JournalApiError {
            try {
                block()
            } catch (e: JournalApiError) {
                return e
            }
            fail("expected a JournalApiError")
            throw AssertionError()
        }
        // A wrong password is a 401, but not a dead session.
        respond(401, """{"error":"bad_password"}""")
        assertEquals(JournalApiError.BadPassword, refusal { api.twoFactorSetup("nope", null) })
        respond(403, """{"error":"bad_code"}""")
        assertEquals(JournalApiError.BadCode(ticketValid = true), refusal { api.twoFactorDisable("pw", "000000") })
        respond(409, """{"error":"no_pending_setup"}""")
        assertEquals(
            JournalApiError.TwoFactorConflict("no_pending_setup"),
            refusal { api.twoFactorEnable("123456", signOutOthers = false) },
        )
        respond(409, """{"error":"two_factor_required"}""")
        assertEquals(
            JournalApiError.TwoFactorConflict("two_factor_required"),
            refusal { api.twoFactorDisable("pw", "123456") },
        )
    }
}
