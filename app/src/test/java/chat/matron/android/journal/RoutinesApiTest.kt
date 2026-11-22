package chat.matron.android.journal

import chat.matron.android.models.RoutineDraft
import chat.matron.android.models.RoutineModelTest
import chat.matron.android.models.RoutineTrigger
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

/// `JournalApi`'s `/routines` routes against a MockWebServer, plus the pure
/// decoders and status mapping (protocol "Coordinator routines").
class RoutinesApiTest {
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

    private val scheduled = RoutineModelTest.SCHEDULED_JSON
    private val triggered = RoutineModelTest.TRIGGERED_JSON

    private suspend fun expect(error: RoutinesError, block: suspend () -> Unit) {
        try {
            block(); fail("expected $error")
        } catch (e: RoutinesError) {
            assertEquals(error, e)
        }
    }

    @Test
    fun listDecodesAndDropsMalformedRows() = runBlocking {
        server.enqueue(json(200, """{"routines":[$scheduled,{"id":"rt_x"},$triggered]}"""))
        val list = api().listRoutines()
        assertEquals(listOf("daily-sweep", "context-over"), list.map { it.name })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/routines", request.requestUrl!!.encodedPath)
        assertEquals("Bearer t", request.getHeader("Authorization"))
    }

    @Test
    fun listWithoutTheKeyIsMalformed() {
        try {
            RoutinesDecoding.routines(parseJsonObjectOrNull("""{"ok":true}""")!!)
            fail("expected an error")
        } catch (e: RoutinesError.Other) {
            assertTrue(e.message!!.contains("malformed"))
        }
    }

    @Test
    fun a404OnTheCollectionIsUnsupportedAndOnOneRoutineIsNotFound() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        expect(RoutinesError.Unsupported) { api().listRoutines() }
        server.enqueue(json(404, """{"error":"not_found"}"""))
        expect(RoutinesError.NotFound) { api().setRoutineEnabled("rt_1", false) }
    }

    @Test
    fun createPostsNameZoneAndFieldsAndMapsConflicts() = runBlocking {
        server.enqueue(json(201, """{"routine":$triggered}"""))
        val draft = RoutineDraft(title = "Session context", prompt = "Look at it.", tz = "UTC", trigger = RoutineTrigger.ContextOver(40), enabled = false)
        assertEquals("context-over", api().createRoutine("context-over", draft).name)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = parseJsonObjectOrNull(request.body.readUtf8())!!
        assertEquals("context-over", body.stringOrNull("name"))
        assertEquals("UTC", body.stringOrNull("tz"))
        assertEquals(false, body.boolOrNull("enabled"))
        assertEquals("context_over", body.objectOrNull("trigger")!!.stringOrNull("kind"))
        assertNull(body["convo_id"])

        server.enqueue(json(409, """{"error":"conflict","blocked_by":"name"}"""))
        expect(RoutinesError.NameTaken) { api().createRoutine("context-over", draft) }
        server.enqueue(json(409, """{"error":"conflict","blocked_by":"cap"}"""))
        expect(RoutinesError.TooMany) { api().createRoutine("context-over", draft) }
        server.enqueue(json(400, """{"error":"bad_request"}"""))
        expect(RoutinesError.Rejected) { api().createRoutine("context-over", draft) }
    }

    @Test
    fun anApprovalRequiredRefusalSaysSo() = runBlocking {
        server.enqueue(json(403, """{"error":"approval_required"}"""))
        expect(RoutinesError.ApprovalRequired) { api().setRoutineEnabled("rt_1", true) }
    }

    @Test
    fun patchPauseDeleteAndRunHitTheirRoutes() = runBlocking {
        server.enqueue(json(200, """{"routine":$scheduled}"""))
        api().updateRoutine("rt_0a1b", RoutineDraft(title = "Daily", prompt = "P", tz = "UTC", schedule = "5 7 * * *"))
        var request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/routines/rt_0a1b", request.requestUrl!!.encodedPath)
        assertEquals("5 7 * * *", parseJsonObjectOrNull(request.body.readUtf8())!!.stringOrNull("schedule"))

        server.enqueue(json(200, """{"routine":$scheduled}"""))
        api().setRoutineEnabled("rt_0a1b", false)
        request = server.takeRequest()
        assertEquals("""{"enabled":false}""", request.body.readUtf8())

        server.enqueue(json(200, """{"ok":true}"""))
        api().deleteRoutine("rt_0a1b")
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(json(202, """{"accepted":true}"""))
        assertEquals(RoutineRunResult.Accepted, api().runRoutine("rt_0a1b"))
        request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/routines/rt_0a1b/run", request.requestUrl!!.encodedPath)

        server.enqueue(json(202, """{"delivered":false,"reason":"no_coordinator"}"""))
        val notSent = api().runRoutine("rt_0a1b")
        assertEquals("Not sent: there is no Coordinator to run it.", notSent.message)
    }
}
