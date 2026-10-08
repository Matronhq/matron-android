package chat.matron.android.journal

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// `JournalApi`'s `/pins` and `/coordinator` routes against a MockWebServer
/// (protocol.md "Pinned desk chats", Coordinator redesign contract).
class PinsApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer(); server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(): JournalApi = JournalApi(server.url("/").toString(), token = "t")

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val listBody =
        """{"pins":[{"convo_id":"c1","label":"Inbox triage","emoji":"📮","position":0,"device_id":7,"created_at":1,"updated_at":1}],"limit":5}"""

    private fun body(request: okhttp3.mockwebserver.RecordedRequest) = parseJsonObjectOrNull(request.body.readUtf8())!!

    @Test
    fun getsTheListAndLimitWithTheBearer() = runBlocking {
        server.enqueue(json(200, listBody))
        val list = api().pins()
        assertEquals(listOf("Inbox triage"), list.pins.map { it.label })
        assertEquals(5, list.limit)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/pins", request.requestUrl!!.encodedPath)
        assertEquals("Bearer t", request.getHeader("Authorization"))
    }

    @Test
    fun reorderPutsTheWholeOrder() = runBlocking {
        server.enqueue(json(200, listBody))
        api().reorderPins(listOf("c2", "c1"))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/pins", request.requestUrl!!.encodedPath)
        assertEquals(JsonArray(listOf(JsonPrimitive("c2"), JsonPrimitive("c1"))), body(request)["order"])
    }

    @Test
    fun putPinSendsLabelAndEmojiAndEscapesTheId() = runBlocking {
        server.enqueue(json(200, listBody))
        api().putPin("c/1", "Inbox triage", "📮")
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/pins/c%2F1", request.requestUrl!!.encodedPath)
        val sent = body(request)
        assertEquals("Inbox triage", sent.stringOrNull("label"))
        assertEquals("📮", sent.stringOrNull("emoji"))

        server.enqueue(json(200, listBody))
        api().putPin("c1", null, "")
        val edit = body(server.takeRequest())
        assertTrue("an unchanged label is left out", !edit.containsKey("label"))
        assertEquals("", edit.stringOrNull("emoji"))
    }

    @Test
    fun deleteMoveAndDismissHitTheirRoutes() = runBlocking {
        server.enqueue(json(200, listBody))
        server.enqueue(json(200, listBody))
        server.enqueue(json(200, listBody))
        api().deletePin("c1")
        api().movePin("c1", "c9")
        api().dismissPinSuccessor("c1", "c9")
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/pins/c1", delete.requestUrl!!.encodedPath)
        val move = server.takeRequest()
        assertEquals("POST", move.method)
        assertEquals("/pins/c1/move", move.requestUrl!!.encodedPath)
        assertEquals("c9", body(move).stringOrNull("to_convo_id"))
        val dismiss = server.takeRequest()
        assertEquals("POST", dismiss.method)
        assertEquals("/pins/c1/dismiss", dismiss.requestUrl!!.encodedPath)
        assertEquals("c9", body(dismiss).stringOrNull("successor_id"))
    }

    @Test
    fun theConflictsKeepTheirDetailInWords() = runBlocking {
        server.enqueue(json(409, """{"error":"conflict","detail":"pin_limit","limit":5}"""))
        try {
            api().putPin("c6", "Sixth", "")
            fail("expected PinRequestError")
        } catch (e: PinRequestError) {
            assertEquals(409, e.status)
            assertEquals("You can pin up to 5 chats.", e.message)
        }
        server.enqueue(json(409, """{"error":"conflict","detail":"already_pinned"}"""))
        try {
            api().movePin("c1", "c2")
            fail("expected PinRequestError")
        } catch (e: PinRequestError) {
            assertEquals("That chat is already pinned.", e.message)
        }
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try {
            api().deletePin("c1")
            fail("expected PinRequestError")
        } catch (e: PinRequestError) {
            assertEquals(404, e.status)
        }
    }

    @Test
    fun otherFailuresKeepTheGenericMapping() = runBlocking {
        server.enqueue(json(401, """{"error":"unauthenticated"}"""))
        try {
            api().deletePin("c1")
            fail("expected Unauthenticated")
        } catch (_: JournalApiError.Unauthenticated) {
        }
    }

    @Test
    fun snapshotCarriesPinsAndTheCoordinator() = runBlocking {
        server.enqueue(json(200, """{"conversations":[],"seq":3,"pins":[],"coordinator_convo_id":"c9"}"""))
        val snapshot = api().snapshot()
        assertEquals(emptyList<ConvoPin>(), snapshot.pins)
        assertEquals(HelloCoordinator.Known("c9"), snapshot.coordinator)

        server.enqueue(json(200, """{"conversations":[],"seq":3}"""))
        val old = api().snapshot()
        assertNull(old.pins)
        assertEquals(HelloCoordinator.Absent, old.coordinator)
    }

    @Test
    fun coordinatorGetAndPut() = runBlocking {
        server.enqueue(json(200, """{"convo_id":"c9"}"""))
        assertEquals("c9", api().coordinator())
        assertEquals("/coordinator", server.takeRequest().requestUrl!!.encodedPath)

        server.enqueue(json(200, """{"convo_id":"c2"}"""))
        assertEquals("c2", api().setCoordinator("c2"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("c2", body(put).stringOrNull("convo_id"))

        server.enqueue(json(200, """{"convo_id":null}"""))
        assertNull(api().setCoordinator(null))
        assertEquals(JsonNull, body(server.takeRequest())["convo_id"])
    }
}
