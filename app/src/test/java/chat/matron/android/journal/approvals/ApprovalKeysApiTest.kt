package chat.matron.android.journal.approvals

import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// The approval-key routes: the requests sent and how refusals come back.
class ApprovalKeysApiTest {
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

    private fun api() = JournalApi(server.url("/").toString(), token = "t")

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val approval = SignedApproval("{\"v\":1}", "sig")

    @Test
    fun readsTheKeysAndRequests() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"enabled":true,"journal":"journal.example","keys":[{"key_id":"${"a".repeat(64)}","public_key":"pk",
                |"alg":"ES256","name":"Pixel","device_id":7,"enrolled_via":"device","created_at":1000,"revoked_at":null}],
                |"requests":[{"id":"kr_1","key_id":"${"b".repeat(64)}","name":"Tablet","created_at":2000}]}""".trimMargin(),
            ),
        )
        val state = api().approvalKeys()
        assertEquals(true, state.enabled)
        assertEquals("journal.example", state.journal)
        val key = state.keys.single()
        assertEquals(7L, key.deviceID)
        assertEquals(ApprovalKeyDTO.EnrolledVia.DEVICE, key.enrolledVia)
        assertEquals(Instant.ofEpochMilli(1000), key.createdAt)
        assertTrue(key.isLive)
        assertEquals("bbbbbbbb", state.requests.single().fingerprint)
        assertTrue(state.hasActiveKey)
    }

    @Test
    fun nonceIsAPost() = runBlocking {
        server.enqueue(json(200, """{"nonce":"n1","expires_at":5000}"""))
        assertEquals(ApprovalNonce("n1", Instant.ofEpochMilli(5000)), api().approvalNonce())
        val req = server.takeRequest()
        assertEquals("/approvals/nonce", req.path)
        assertEquals("POST", req.method)
    }

    @Test
    fun enrolSendsTheKeyNamePasswordAndCode() = runBlocking {
        server.enqueue(json(200, """{"key":{"key_id":"${"a".repeat(64)}","name":"Pixel","device_id":7}}"""))
        api().enrolApprovalKey("pk", "Pixel", "secret", "123456")
        val body = parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!
        assertEquals(listOf("pk", "Pixel", "secret", "123456"), listOf("public_key", "name", "password", "code").map { body.stringOrNull(it) })
    }

    @Test
    fun signedRoutesCarryTheApprovalAsTwoStrings() = runBlocking {
        server.enqueue(json(200, """{"key":{"key_id":"${"a".repeat(64)}","name":"Pixel","device_id":7}}"""))
        api().attachApprovalKey(approval)
        val req = server.takeRequest()
        assertEquals("/approval-keys/attach", req.path)
        val sent = parseJsonObjectOrNull(req.body.readUtf8())!!.objectOrNull("approval")!!
        assertEquals("{\"v\":1}", sent.stringOrNull("statement"))
        assertEquals("sig", sent.stringOrNull("signature"))
        assertEquals(2, sent.size)

        server.enqueue(json(200, """{"ok":true}"""))
        api().revokeApprovalKey("c".repeat(64), approval)
        assertEquals("/approval-keys/${"c".repeat(64)}/revoke", server.takeRequest().path)

        server.enqueue(json(200, """{"key":{"key_id":"${"d".repeat(64)}","name":"Tablet","device_id":9}}"""))
        api().approveApprovalKeyRequest("kr_1", approval)
        assertEquals("/approval-keys/requests/kr_1/approve", server.takeRequest().path)
    }

    private suspend fun refusal(code: Int, body: String): Throwable {
        server.enqueue(json(code, body))
        try {
            api().enrolApprovalKey("pk", "Pixel", "secret", "123456")
            fail("expected a throw")
        } catch (e: Throwable) {
            return e
        }
        throw AssertionError()
    }

    @Test
    fun keyRouteRefusalsAreNamed() = runBlocking {
        assertEquals(ApprovalKeyError.BadPassword, refusal(401, """{"error":"bad_password"}"""))
        assertEquals(ApprovalKeyError.BadCode, refusal(403, """{"error":"bad_code"}"""))
        assertEquals(ApprovalKeyError.KeyExists, refusal(409, """{"error":"key_exists"}"""))
        assertEquals(ApprovalKeyError.Disabled, refusal(409, """{"error":"approval_keys_disabled"}"""))
        assertEquals(ApprovalKeyError.TwoFactorRequired, refusal(409, """{"error":"two_factor_required"}"""))
        assertEquals(ApprovalKeyError.TotpRequired, refusal(400, """{"error":"totp_required"}"""))
        assertEquals(
            ApprovalKeyError.EnrolmentLocked(Instant.ofEpochMilli(9000)),
            refusal(429, """{"error":"enrolment_locked","retry_at":9000}"""),
        )
        assertEquals(JournalApiError.ApprovalInvalid("signature"), refusal(400, """{"error":"approval_invalid","reason":"signature"}"""))
        assertEquals(JournalApiError.ApprovalRequired, refusal(403, """{"error":"approval_required"}"""))
        assertEquals(JournalApiError.Unauthenticated, refusal(401, """{"error":"unauthenticated"}"""))
    }
}
