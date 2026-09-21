package chat.matron.android.journal

import chat.matron.android.models.MissionModelTest
import chat.matron.android.models.MissionState
import chat.matron.android.models.TrackerItemTest
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

/// `JournalApi`'s `/missions` routes against a MockWebServer plus the pure
/// decoders — the port of matron-apple's `MissionsAPITests`.
class MissionsApiTest {
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

    private val missionJSON = MissionModelTest.MISSION_JSON
    private val milestoneJSON = MissionModelTest.MILESTONE_JSON
    private val detailJSON = """{"mission":$missionJSON,"milestones":[$milestoneJSON],"items":[${TrackerItemTest.ITEM_JSON}],
        "conversations":[{"id":"c1","title":"Session","box":"dev-2","state":"running"}]}"""

    @Test
    fun listQueryBuildsStateAndSince() {
        assertTrue(MissionsListQuery().queryItems.isEmpty())
        assertEquals(
            listOf("state" to "open", "since" to "1700000000000"),
            MissionsListQuery(state = MissionState.OPEN, since = Instant.ofEpochMilli(1_700_000_000_000)).queryItems,
        )
    }

    @Test
    fun decodeMissionsListDropsMalformedRowsButKeepsTheRest() {
        val decoded = MissionsDecoding.missions(obj("""{"missions":[$missionJSON,{"id":"ms_broken"}]}"""))
        assertEquals(listOf("ms_a1"), decoded.missions.map { it.id })
        // The dropped row's id is still reported so the caller can protect
        // it from an authoritative replace reading a local decode failure
        // as "the server removed it".
        assertEquals(listOf("ms_broken"), decoded.droppedIDs)
    }

    /// The TOP-LEVEL `missions` key missing (or not an array) is a malformed
    /// RESPONSE, not one bad row — an authoritative replace must not read
    /// that as "zero missions".
    @Test
    fun decodeMissionsListWithoutAMissionsArrayIsATransportError() {
        for (body in listOf("""{"foo":"bar"}""", "{}", """{"missions":"nope"}""")) {
            try {
                MissionsDecoding.missions(obj(body)); fail("expected Transport for $body")
            } catch (e: JournalApiError.Transport) {
                // expected
            }
        }
    }

    /// A present-but-empty array is a legitimate "no missions" answer.
    @Test
    fun decodeMissionsListWithAnEmptyArrayDecodesToEmpty() {
        val decoded = MissionsDecoding.missions(obj("""{"missions":[]}"""))
        assertTrue(decoded.missions.isEmpty()); assertTrue(decoded.droppedIDs.isEmpty())
    }

    @Test
    fun decodeMissionDetail() {
        val detail = MissionsDecoding.detail(obj(detailJSON))
        assertEquals("ms_a1", detail.mission.id)
        assertEquals(listOf("ml_b2"), detail.milestones.map { it.id })
        assertEquals(listOf("it_1"), detail.items.map { it.id })
        assertEquals(listOf("c1"), detail.conversations.map { it.id })
    }

    @Test
    fun decodeMissionDetailWithoutAMissionIsATransportError() {
        try {
            MissionsDecoding.detail(obj("""{"milestones":[]}""")); fail("expected Transport")
        } catch (e: JournalApiError.Transport) {
            // expected
        }
    }

    @Test
    fun listMissionsBuildsQueryAndDecodes() = runBlocking {
        server.enqueue(json(200, """{"missions":[$missionJSON]}"""))
        val decoded = api().listMissions(MissionsListQuery(state = MissionState.OPEN))
        assertEquals(listOf("ms_a1"), decoded.missions.map { it.id })
        val request = server.takeRequest()
        assertEquals("/missions", request.requestUrl!!.encodedPath)
        assertEquals("open", request.requestUrl!!.queryParameter("state"))
        assertEquals("Bearer t", request.getHeader("Authorization"))
    }

    /// `:id` accepts `ms_…` or a bare number; a `#61` reference must be
    /// percent-encoded or the `#` truncates the URL into a fragment.
    @Test
    fun missionDetailFetchesByEncodedID() = runBlocking {
        server.enqueue(json(200, detailJSON))
        val detail = api().mission("#61")
        assertEquals("ms_a1", detail.mission.id)
        assertEquals("/missions/%2361", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun missionNotFoundIsNotFound() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try {
            api().mission("ms_zz"); fail("expected NotFound")
        } catch (e: JournalApiError.NotFound) {
            // expected
        }
    }

    @Test
    fun milestonesFetchesByConvoQuery() = runBlocking {
        server.enqueue(json(200, """{"milestones":[$milestoneJSON]}"""))
        val milestones = api().milestones("c1")
        assertEquals(listOf("ml_b2"), milestones.map { it.id })
        val url = server.takeRequest().requestUrl!!
        assertEquals("/milestones", url.encodedPath); assertEquals("c1", url.queryParameter("convo"))
    }

    @Test
    fun closeMissionPostsSummaryAndDecodesMission() = runBlocking {
        server.enqueue(json(200, """{"mission":$missionJSON}"""))
        val mission = api().closeMission("ms_a1", "Done.")
        assertEquals("ms_a1", mission.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/missions/ms_a1/close", request.requestUrl!!.encodedPath)
        assertEquals("Done.", obj(request.body.readUtf8()).stringOrNull("summary"))
    }
}
