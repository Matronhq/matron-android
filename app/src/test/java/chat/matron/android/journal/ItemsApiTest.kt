package chat.matron.android.journal

import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerItemTest
import java.time.Instant
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

/// `JournalApi`'s `/items` routes against a MockWebServer — the route half of
/// matron-apple's `ItemsAPITests` (the Swift suite stubs `URLProtocol`).
class ItemsApiTest {
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
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val itemJSON = TrackerItemTest.ITEM_JSON
    private val commentJSON = """{"id":"ic_2","item_id":"it_1","user_id":1,"author":"user","device_id":9,"kind":"comment",
        "body":"hi","attachments":[],"meta":null,"idem_key":null,"created_at":1700000002000}"""

    @Test
    fun listItemsBuildsQueryAndDecodesPage() = runBlocking {
        server.enqueue(json(200, """{"items":[$itemJSON],"next_cursor":"abc"}"""))
        val page = api().listItems(
            ItemsListQuery(convoID = "c1", awaiting = ItemAwaiting.USER, since = Instant.ofEpochMilli(1_700_000_000_000)),
        )
        assertEquals(12, page.items.first().num); assertEquals("abc", page.nextCursor)
        val request = server.takeRequest()
        assertEquals("/items", request.requestUrl!!.encodedPath)
        val url = request.requestUrl!!
        assertEquals("c1", url.queryParameter("convo"))
        assertEquals("user", url.queryParameter("awaiting"))
        assertEquals("1700000000000", url.queryParameter("since"))
        assertEquals("rank", url.queryParameter("sort"))
        assertEquals("100", url.queryParameter("limit"))
        assertNull("state is omitted when unset — the journal has no state=any", url.queryParameter("state"))
        assertEquals("Bearer t", request.getHeader("Authorization"))
    }

    @Test
    fun createItemAccepts201AndSendsIdempotencyKey() = runBlocking {
        server.enqueue(json(201, """{"item":$itemJSON}"""))
        val item = api().createItem(NewItem(kind = ItemKind.TASK, title = "T", body = "b", convoID = "c1"), idempotencyKey = "k1")
        assertEquals("it_1", item.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("k1", request.getHeader("Idempotency-Key"))
        val sent = parseJsonObjectOrNull(request.body.readUtf8())!!
        assertEquals("task", sent.stringOrNull("kind")); assertEquals("c1", sent.stringOrNull("convo_id"))
        assertNull("awaiting is omitted unless explicitly set", sent["awaiting"])
    }

    @Test
    fun createItemReplayAnswers200() = runBlocking {
        server.enqueue(json(200, """{"item":$itemJSON}"""))
        assertEquals("it_1", api().createItem(NewItem(kind = ItemKind.TASK, title = "T", convoID = "c1"), "k1").id)
    }

    @Test
    fun commentItemAccepts201DecodesItemAndCommentAndStripsTranscript() = runBlocking {
        server.enqueue(json(201, """{"item":$itemJSON,"comment":$commentJSON}"""))
        val attachment = TrackerAttachment("b1", "audio/m4a", "note.m4a", 42, transcript = "secret transcript")
        val r = api().commentItem("it_1", "hi", listOf(attachment), idempotencyKey = "k2")
        assertEquals("hi", r.comment.body); assertEquals("it_1", r.item.id)
        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/items/it_1/comments"))
        assertEquals("POST", request.method); assertEquals("k2", request.getHeader("Idempotency-Key"))
        val sent = parseJsonObjectOrNull(request.body.readUtf8())!!
        val sentAttachment = sent.arrayOrNull("attachments")!!.objects().first()
        assertNull(sentAttachment["transcript"])
        assertEquals("b1", sentAttachment.stringOrNull("blob_ref")); assertEquals("audio/m4a", sentAttachment.stringOrNull("mime"))
        assertEquals("note.m4a", sentAttachment.stringOrNull("name")); assertEquals(42L, sentAttachment.longOrNull("size"))
    }

    @Test
    fun closeMapsConflict() = runBlocking {
        server.enqueue(json(409, """{"error":"conflict"}"""))
        try {
            api().closeItem("it_1", ItemResolution.DONE, null); fail("expected throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.Conflict, e)
        }
        val sent = parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!
        assertEquals("done", sent.stringOrNull("resolution"))
        assertNull("no comment key when none given", sent["comment"])
    }

    @Test
    fun listItemsMapsNotFoundForAJournalWithoutTheRoutes() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try {
            api().listItems(ItemsListQuery()); fail("expected throw")
        } catch (e: JournalApiError) {
            assertEquals(JournalApiError.NotFound, e)
        }
    }

    @Test
    fun itemDetailDecodesCommentsAndEncodesNumberLookups() = runBlocking {
        server.enqueue(json(200, """{"item":$itemJSON,"comments":[$commentJSON]}"""))
        val r = api().item("#12")
        assertEquals("hi", r.comments.first().body)
        assertTrue(server.takeRequest().path!!.endsWith("/items/%2312"))
    }

    @Test
    fun rankAndReopenSendTheirBodies() = runBlocking {
        server.enqueue(json(200, """{"item":$itemJSON}"""))
        api().rankItem("it_1", ItemRankChange(after = "a", before = "b"))
        var request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/items/it_1/rank"))
        var sent = parseJsonObjectOrNull(request.body.readUtf8())!!
        assertEquals("a", sent.stringOrNull("after")); assertEquals("b", sent.stringOrNull("before")); assertNull(sent["position"])

        server.enqueue(json(200, """{"item":$itemJSON}"""))
        api().reopenItem("it_1", comment = "back")
        request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/items/it_1/reopen"))
        sent = parseJsonObjectOrNull(request.body.readUtf8())!!
        assertEquals("back", sent.stringOrNull("comment"))
    }

    @Test
    fun malformedItemResponseIsATransportError() = runBlocking {
        server.enqueue(json(200, """{"item":{"id":"it_1"}}"""))
        try {
            api().reopenItem("it_1", null); fail("expected throw")
        } catch (e: JournalApiError) {
            assertTrue(e is JournalApiError.Transport)
        }
    }
}
