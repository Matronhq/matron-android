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

/// `GET`/`PUT /defaults` and the live `defaults` frame.
class NewChatDefaultsApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer(); server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api() = JournalApi(server.url("/").toString(), token = "t")

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun obj(text: String) = parseJsonObjectOrNull(text)!!

    @Test
    fun decodeTreatsMissingNullAndEmptyAsBoxDefaultAndRejectsNonStrings() {
        assertEquals(NewChatDefaults("opus", null), NewChatDefaults.decode(obj("""{"default_model":"opus","default_effort":null}""")))
        assertEquals(NewChatDefaults(), NewChatDefaults.decode(obj("""{"default_model":""}""")))
        assertNull(NewChatDefaults.decode(obj("""{"default_model":3,"default_effort":"high"}""")))
    }

    @Test
    fun getAndPutHitTheRoute() = runBlocking {
        server.enqueue(json(200, """{"default_model":"sonnet","default_effort":"high"}"""))
        assertEquals(NewChatDefaults("sonnet", "high"), api().newChatDefaults())
        assertEquals("/defaults", server.takeRequest().requestUrl!!.encodedPath)

        server.enqueue(json(200, """{"default_model":null,"default_effort":"high"}"""))
        assertEquals(NewChatDefaults(null, "high"), api().setNewChatDefault(NewChatDefaults.Key.MODEL, null))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("""{"default_model":null}""", put.body.readUtf8())

        server.enqueue(json(200, "{}"))
        api().setNewChatDefault(NewChatDefaults.Key.EFFORT, "max")
        assertEquals("""{"default_effort":"max"}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun anOlderJournal404s() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try {
            api().newChatDefaults(); fail("expected NotFound")
        } catch (e: JournalApiError) {
            assertTrue(e is JournalApiError.NotFound)
        }
    }

    @Test
    fun theLiveFrameDecodes() {
        val frame = ServerFrame.decode("""{"kind":"defaults","default_model":"fable","default_effort":"low"}""")
        assertEquals(ServerFrame.DefaultsFrame(NewChatDefaults("fable", "low")), frame)
    }
}
