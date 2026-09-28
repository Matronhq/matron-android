package chat.matron.android.journal

import chat.matron.android.models.MemoryModelTest
import chat.matron.android.models.MemoryType
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// `JournalApi`'s `/memories` routes against a MockWebServer plus the pure
/// decoders (protocol.md "Memories", spec 2026-09-27).
class MemoriesApiTest {
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

    private fun obj(text: String) = parseJsonObjectOrNull(text)!!

    private val memoryJSON = MemoryModelTest.MEMORY_JSON

    @Test
    fun listDecodesSortedByNameAndDropsMalformedRows() {
        val second = memoryJSON.replace("me_cbf8", "me_2").replace("eric-fatima-last-resort", "a-first")
        val decoded = MemoriesDecoding.memories(obj("""{"memories":[$memoryJSON,{"id":"me_broken"},$second]}"""))
        assertEquals(listOf("a-first", "eric-fatima-last-resort"), decoded.map { it.name })
    }

    @Test
    fun listWithoutTheTopLevelKeyIsMalformedNotEmpty() {
        try {
            MemoriesDecoding.memories(obj("""{"ok":true}"""))
            fail("expected Transport")
        } catch (e: JournalApiError.Transport) {
            assertTrue(e.message!!.contains("malformed"))
        }
        assertTrue(MemoriesDecoding.memories(obj("""{"memories":[]}""")).isEmpty())
    }

    @Test
    fun listMemoriesGetsTheRouteWithTheBearer() = runBlocking {
        server.enqueue(json(200, """{"memories":[$memoryJSON]}"""))
        val list = api().listMemories()
        assertEquals(listOf("eric-fatima-last-resort"), list.map { it.name })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/memories", request.requestUrl!!.encodedPath)
        assertEquals("Bearer t", request.getHeader("Authorization"))
    }

    @Test
    fun saveMemoryPutsTheWholeMemoryAndReportsCreatedFromTheStatus() = runBlocking {
        server.enqueue(json(201, """{"memory":$memoryJSON}"""))
        val created = api().saveMemory("eric-fatima-last-resort", MemoryWrite("Use eric last.", body = "**Why:** x", type = MemoryType.FEEDBACK))
        assertTrue(created.created)
        assertEquals("eric-fatima-last-resort", created.memory.name)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/memories/eric-fatima-last-resort", request.requestUrl!!.encodedPath)
        val body = parseJsonObjectOrNull(request.body.readUtf8())!!
        assertEquals("Use eric last.", body.stringOrNull("description"))
        assertEquals("**Why:** x", body.stringOrNull("body"))
        assertEquals("feedback", body.stringOrNull("type"))

        server.enqueue(json(200, """{"memory":$memoryJSON}"""))
        val updated = api().saveMemory("eric-fatima-last-resort", MemoryWrite("Use eric last."))
        assertFalse(updated.created)
        val second = parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!
        assertEquals(setOf("description"), second.keys)
    }

    @Test
    fun deleteMemoryDeletesTheRouteAndDecodesTheRow() = runBlocking {
        server.enqueue(json(200, """{"memory":$memoryJSON}"""))
        val gone = api().deleteMemory("eric-fatima-last-resort")
        assertEquals("me_cbf8", gone.id)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/memories/eric-fatima-last-resort", request.requestUrl!!.encodedPath)
    }

    @Test
    fun statusesMapToTheSharedErrors() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try { api().listMemories(); fail("expected NotFound") } catch (e: JournalApiError.NotFound) { }
        server.enqueue(json(409, """{"error":"too_many"}"""))
        try { api().saveMemory("x", MemoryWrite("d")); fail("expected Conflict") } catch (e: JournalApiError.Conflict) { }
        server.enqueue(json(400, """{"error":"bad_request"}"""))
        try { api().saveMemory("x", MemoryWrite("d")); fail("expected Http") } catch (e: JournalApiError.Http) { assertEquals(400, e.status) }
    }
}
