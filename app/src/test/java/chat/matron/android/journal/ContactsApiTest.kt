package chat.matron.android.journal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// `JournalApi`'s contacts-across-journals routes.
class ContactsApiTest {
    private lateinit var server: MockWebServer
    private val link = "matron://contact?v=1&j=b.example&u=bob&t=${"A".repeat(43)}&fp=${"B".repeat(26)}"
    private val webLink = "https://b.example/contact#v=1&j=b.example&u=bob&t=${"A".repeat(43)}&fp=${"B".repeat(26)}"

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
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e")
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun makingAnInviteReturnsTheLinkOnce() = runBlocking {
        server.enqueue(json(201, """{"invite":{"id":"inv_1","state":"open","created_at":1,"expires_at":2},"link":"$link"}"""))
        val minted = api().createContactInvite()
        assertEquals(link, minted.link)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/contacts/invites", req.requestUrl!!.encodedPath)
    }

    @Test
    fun aJournalSendingBothFormsHasTheHttpsOneShared() = runBlocking {
        server.enqueue(json(201, """{"invite":{"id":"inv_1","state":"open","created_at":1,"expires_at":2},"link":"$link","web_link":"$webLink"}"""))
        assertEquals(webLink, api().createContactInvite().shareLink)
        server.enqueue(json(200, """{"ask":{"id":"ia_1","state":"approved"},"invite":{"id":"inv_1"},"link":"$link","web_link":"$webLink"}"""))
        assertEquals(webLink, api().answerInviteAsk("ia_1", approve = true).link)
    }

    @Test
    fun anHttpsLinkIsOpenedAndAcceptedAsGiven() = runBlocking {
        server.enqueue(json(200, """{"inviter":{"address":"bob@b.example","name":"bob","journal":"b.example","fingerprint":"BBBB"}}"""))
        api().openContactInvite(webLink)
        assertEquals(webLink, parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!.stringOrNull("link"))
        server.enqueue(json(202, """{}"""))
        api().acceptContactInvite(webLink)
        assertEquals(webLink, parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!.stringOrNull("link"))
    }

    @Test
    fun tooManyInvitesIsARefusalWithItsReason() = runBlocking {
        server.enqueue(json(409, """{"error":"conflict","blocked_by":"too_many_invites"}"""))
        val e = thrown<ContactRefusal> { runBlocking { api().createContactInvite() } }
        assertEquals(409, e.status)
        assertEquals("too_many_invites", e.reason)
    }

    @Test
    fun openSendsTheLinkAndReadsTheInviter() = runBlocking {
        server.enqueue(json(200, """{"inviter":{"address":"bob@b.example","name":"bob","journal":"b.example","fingerprint":"BBBB BBBB"},"key_changed":false,"contact":null}"""))
        val preview = api().openContactInvite(link)
        assertEquals("bob@b.example", preview.address)
        val req = server.takeRequest()
        assertEquals("/contacts/invites/open", req.requestUrl!!.encodedPath)
        assertEquals(link, parseJsonObjectOrNull(req.body.readUtf8())!!.stringOrNull("link"))
    }

    @Test
    fun openRefusalsCarryTheirReason() = runBlocking {
        server.enqueue(json(409, """{"error":"conflict","blocked_by":"own_journal"}"""))
        assertEquals("own_journal", thrown<ContactRefusal> { runBlocking { api().openContactInvite(link) } }.reason)
        server.enqueue(json(502, """{"error":"unreachable"}"""))
        val upstream = thrown<ContactRefusal> { runBlocking { api().openContactInvite(link) } }
        assertEquals(502, upstream.status)
        assertEquals("unreachable", upstream.reason)
        server.enqueue(json(400, """{"error":"bad_request"}"""))
        val bad = thrown<JournalApiError.Http> { runBlocking { api().openContactInvite(link) } }
        assertEquals(400, bad.status)
        assertEquals("This is not a valid invite link.", ContactsWording.linkError(bad))
    }

    @Test
    fun acceptIsA202() = runBlocking {
        server.enqueue(json(202, """{"contact":{"id":"ct_1","address":"bob@b.example","state":"pending_out"},"pending":"peer"}"""))
        api().acceptContactInvite(link)
        assertEquals("/contacts/invites/accept", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun withdrawDeletesTheInvite() = runBlocking {
        server.enqueue(json(200, """{"invite":{"id":"inv_1","state":"revoked"}}"""))
        api().revokeContactInvite("inv_1")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/contacts/invites/inv_1", req.requestUrl!!.encodedPath)
    }

    @Test
    fun answeringAnAskSendsTheDecisionAndReadsTheLink() = runBlocking {
        server.enqueue(json(200, """{"ask":{"id":"ia_1","state":"approved"},"invite":{"id":"inv_1"},"link":"$link"}"""))
        val answer = api().answerInviteAsk("ia_1", approve = true)
        assertEquals(link, answer.link)
        val req = server.takeRequest()
        assertEquals("/contacts/invites/asks/ia_1/answer", req.requestUrl!!.encodedPath)
        assertEquals("approve", parseJsonObjectOrNull(req.body.readUtf8())!!.stringOrNull("decision"))

        server.enqueue(json(200, """{"ask":{"id":"ia_1","state":"declined"}}"""))
        assertNull(api().answerInviteAsk("ia_1", approve = false).link)
        assertEquals("decline", parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!.stringOrNull("decision"))

        server.enqueue(json(409, """{"error":"conflict","blocked_by":"not_pending"}"""))
        assertEquals("not_pending", thrown<ContactRefusal> { runBlocking { api().answerInviteAsk("ia_1", true) } }.reason)
    }

    @Test
    fun contactsAreListed() = runBlocking {
        server.enqueue(json(200, """{"contacts":[{"id":"ct_1","address":"bob@b.example","peer_user":"bob","peer_journal":"b.example","state":"active"}]}"""))
        assertEquals(listOf("bob@b.example"), api().contacts().map { it.address })
    }

    @Test
    fun aMirrorTapAnswerCarriesTheLink() = runBlocking {
        val comment = """{"id":"ic_2","item_id":"it_1","user_id":1,"author":"user","device_id":9,"kind":"comment",
            "body":"Approve","attachments":[],"meta":null,"idem_key":null,"created_at":1700000002000}"""
        server.enqueue(json(200, """{"item":${chat.matron.android.models.TrackerItemTest.ITEM_JSON},"comment":$comment,
            "ask":{"id":"ia_1","state":"approved"},"invite":{"id":"inv_1"},"link":"$link"}"""))
        val result = api().commentItem("it_1", "Approve", emptyList(), idempotencyKey = null, action = "Approve")
        assertEquals(link, result.link)
        assertEquals("it_1", result.comment.itemID)
    }

    @Test
    fun aMirrorTapAnswerSharesTheHttpsFormWhenSent() = runBlocking {
        val comment = """{"id":"ic_2","item_id":"it_1","user_id":1,"author":"user","device_id":9,"kind":"comment",
            "body":"Approve","attachments":[],"meta":null,"idem_key":null,"created_at":1700000002000}"""
        server.enqueue(json(200, """{"item":${chat.matron.android.models.TrackerItemTest.ITEM_JSON},"comment":$comment,
            "ask":{"id":"ia_1","state":"approved"},"invite":{"id":"inv_1"},"link":"$link","web_link":"$webLink"}"""))
        val result = api().commentItem("it_1", "Approve", emptyList(), idempotencyKey = null, action = "Approve")
        assertEquals(webLink, result.link)
    }
}
