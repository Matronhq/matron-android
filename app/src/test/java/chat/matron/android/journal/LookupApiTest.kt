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

/// `JournalApi`'s `/lookup` route ("what is #N?") and its decoder.
class LookupApiTest {
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

    @Test
    fun lookupSendsTheUserAndNumberAndDecodesEveryField() = runBlocking {
        server.enqueue(
            json(
                200,
                """{"kind":"milestone","id":"ms1","num":412,"title":"Shipped the beta",""" +
                    """"mission":{"id":"m7","num":7},"merged_from":"old1","owner":{"user_id":1,"name":"alice"}}""",
            ),
        )
        assertEquals(
            NumberLookup(
                kind = NumberKind.MILESTONE, id = "ms1", num = 412, title = "Shipped the beta",
                missionID = "m7", missionNum = 7, mergedFrom = "old1", ownerUserID = 1, ownerName = "alice",
            ),
            api().lookup(user = "alice", num = 412),
        )
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/lookup", request.requestUrl!!.encodedPath)
        assertEquals("alice", request.requestUrl!!.queryParameter("user"))
        assertEquals("412", request.requestUrl!!.queryParameter("num"))
        assertEquals("Bearer t", request.getHeader("Authorization"))
    }

    /// An older journal answers with the kind, the id and the owner only.
    @Test
    fun optionalFieldsMayBeAbsent() = runBlocking {
        server.enqueue(json(200, """{"kind":"item","id":"i1","owner":{"user_id":1,"name":"alice"}}"""))
        val found = api().lookup(user = "alice", num = 412)
        assertEquals(NumberKind.ITEM, found.kind); assertEquals("i1", found.id)
        assertNull(found.num); assertNull(found.title)
        assertNull(found.missionID); assertNull(found.missionNum); assertNull(found.mergedFrom)
    }

    @Test
    fun theLoginNameIsQueryEncoded() = runBlocking {
        server.enqueue(json(200, """{"kind":"project","id":"p1"}"""))
        api().lookup(user = "al ice&b", num = 3)
        assertEquals("al ice&b", server.takeRequest().requestUrl!!.queryParameter("user"))
    }

    @Test
    fun anUnknownNumber404s() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try {
            api().lookup(user = "alice", num = 412); fail("expected NotFound")
        } catch (e: JournalApiError) {
            assertTrue(e is JournalApiError.NotFound)
        }
    }

    /// A kind this build cannot open must fail loudly, not open the wrong page.
    @Test
    fun anUnknownKindIsAMalformedResponse() = runBlocking {
        server.enqueue(json(200, """{"kind":"galaxy","id":"g1","num":412}"""))
        try {
            api().lookup(user = "alice", num = 412); fail("expected Transport")
        } catch (e: JournalApiError) {
            assertTrue(e is JournalApiError.Transport)
        }
    }

    @Test
    fun decoderRejectsWhatCannotBeOpened() {
        assertNull(NumberLookupDecoding.lookup(null))
        assertNull("no id", NumberLookupDecoding.lookup(parseJsonObjectOrNull("""{"kind":"item"}""")))
        assertNull("empty id", NumberLookupDecoding.lookup(parseJsonObjectOrNull("""{"kind":"item","id":""}""")))
        assertNull("no kind", NumberLookupDecoding.lookup(parseJsonObjectOrNull("""{"id":"i1"}""")))
        val blanks = NumberLookupDecoding.lookup(
            parseJsonObjectOrNull("""{"kind":"mission","id":"m1","title":" ","merged_from":"","mission":{}}"""),
        )!!
        assertEquals(NumberKind.MISSION, blanks.kind)
        assertNull("a blank title reads as absent", blanks.title)
        assertNull(blanks.mergedFrom); assertNull(blanks.missionID)
    }

    @Test
    fun everyWireKindDecodes() {
        assertEquals(
            listOf(NumberKind.ITEM, NumberKind.MISSION, NumberKind.MILESTONE, NumberKind.PROJECT),
            listOf("item", "mission", "milestone", "project").map(NumberLookupDecoding::kind),
        )
    }
}
