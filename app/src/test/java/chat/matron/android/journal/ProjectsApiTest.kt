package chat.matron.android.journal

import chat.matron.android.models.MissionModelTest
import chat.matron.android.models.MissionState
import chat.matron.android.models.ProjectFileSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// The `/projects` routes and the per-conversation missions read.
class ProjectsApiTest {
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

    companion object {
        const val PROJECT_JSON = """{"id":"pj_docs","num":12,"state":"open","title":"Docs site launch","body":"Ship the docs.",
            "status":"Live; search next.","created_at":1762000000000,"updated_at":1763000000000,
            "missions":{"running":1,"waiting":1,"idle":0,"quiet":0,"closed":3},"needs_you":2,"open_items":5,
            "last_activity_at":1763100000000}"""
    }

    @Test
    fun projectsDecodeAndDropMalformedRows() {
        val projects = ProjectsDecoding.projects(obj("""{"projects":[$PROJECT_JSON,{"id":"pj_bad"}]}"""))
        assertEquals(1, projects.size)
        val docs = projects[0]
        assertEquals("#12 Docs site launch", docs.label)
        assertEquals(2, docs.missions.open)
        assertEquals(3, docs.missions.closed)
        assertEquals("Live; search next.", docs.status)
        try {
            ProjectsDecoding.projects(obj("{}")); fail("expected Transport")
        } catch (_: JournalApiError.Transport) {
        }
    }

    @Test
    fun missionRowsCarryTheirProject() {
        val mission = MissionsDecoding.mission(obj("""{"mission":${MissionModelTest.MISSION_JSON.replace("\"num\":61", "\"num\":61,\"project_id\":\"pj_docs\",\"project_num\":12")}}"""))
        assertEquals("pj_docs", mission.projectID)
        assertEquals(12, mission.projectNum)
        val loose = MissionsDecoding.mission(obj("""{"mission":${MissionModelTest.MISSION_JSON}}"""))
        assertNull(loose.projectID)
    }

    @Test
    fun detailAndFilesDecode() {
        val detail = ProjectsDecoding.detail(obj("""{"project":$PROJECT_JSON,"missions":[${MissionModelTest.MISSION_JSON},{"id":"x"}]}"""))
        assertEquals("pj_docs", detail.project.id)
        assertEquals(listOf("ms_a1"), detail.missions.map { it.id })

        val files = ProjectsDecoding.files(obj("""{"kind":"files","total":3,"rows":[
            {"blob_id":"bl_1","name":"home.png","content_type":"image/png","size":2048,"mission_num":20,"source":{"item_num":31},"posted_at":1763000000000},
            {"blob_id":"bl_2","name":"tour.mp4","content_type":"video/mp4","source":{"convo_id":"c1","seq":44}},
            {"blob_id":"bl_3"}],"next_before":"cursor"}"""))
        assertEquals(3, files.total)
        assertEquals(listOf("item:31:bl_1", "chat:c1:44"), files.rows.map { it.id })
        assertTrue(files.rows[0].isImage); assertTrue(files.rows[1].isVideo)
        assertEquals(ProjectFileSource.Chat("c1", 44), files.rows[1].source)
        assertTrue(files.hasMore)
        try {
            ProjectsDecoding.files(obj("""{"kind":"decisions","rows":[]}""")); fail("expected Transport")
        } catch (_: JournalApiError.Transport) {
        }
    }

    @Test
    fun aDetailWithoutItsMissionsIsMalformed() {
        try {
            ProjectsDecoding.detail(obj("""{"project":$PROJECT_JSON}""")); fail("expected Transport")
        } catch (_: JournalApiError.Transport) {
        }
        assertTrue(ProjectsDecoding.detail(obj("""{"project":$PROJECT_JSON,"missions":[]}""")).missions.isEmpty())
    }

    @Test
    fun conversationMissionsAreAllOrNothing() {
        val links = ProjectsDecoding.conversationMissions(obj("""{"missions":[
            ${MissionModelTest.MISSION_JSON.replace("{\n", "{\"current\":true,\"active\":true,\"joined_at\":1700000000000,\n")}]}"""))
        assertEquals(1, links.size)
        assertTrue(links[0].isCurrent)
        assertFalse(links[0].isEarlier)
        try {
            ProjectsDecoding.conversationMissions(obj("""{"missions":[${MissionModelTest.MISSION_JSON},{"id":"broken"}]}""")); fail("expected Transport")
        } catch (_: JournalApiError.Transport) {
        }
        try {
            ProjectsDecoding.conversationMissions(obj("""{"missions":[${MissionModelTest.MISSION_JSON},null]}""")); fail("expected Transport")
        } catch (_: JournalApiError.Transport) {
        }
    }

    @Test
    fun closeRefusalsNameWhyTheJournalSaidNo() = runBlocking {
        server.enqueue(json(409, """{"error":"conflict","blocked_by":"open_missions"}"""))
        server.enqueue(json(409, """{"error":"conflict","blocked_by":"closed"}"""))
        server.enqueue(json(200, """{"project":$PROJECT_JSON}"""))
        val api = api()
        try {
            api.closeProject("pj_docs", "Done."); fail("expected OpenMissions")
        } catch (_: ProjectCloseError.OpenMissions) {
        }
        try {
            api.closeProject("pj_docs", "Done."); fail("expected AlreadyClosed")
        } catch (_: ProjectCloseError.AlreadyClosed) {
        }
        api.closeProject("pj_docs", "Done.")
        val request = server.takeRequest()
        assertEquals("/projects/pj_docs/close", request.path)
        assertEquals("POST", request.method)
        assertEquals("""{"summary":"Done."}""", request.body.readUtf8())
    }

    @Test
    fun writesSendTheirBodies() = runBlocking {
        val mission = "{\"mission\":${MissionModelTest.MISSION_JSON}}"
        server.enqueue(json(200, """{"project":$PROJECT_JSON}"""))
        server.enqueue(json(200, """{"project":$PROJECT_JSON}"""))
        server.enqueue(json(200, mission))
        server.enqueue(json(200, mission))
        server.enqueue(json(200, """{"projects":[$PROJECT_JSON]}"""))
        val api = api()
        api.renameProject("pj_docs", "Docs site")
        api.mergeProject("pj_promo", into = "pj_docs")
        assertEquals(MissionState.OPEN, api.setMissionProject("ms_a1", "pj_docs").state)
        api.setMissionProject("ms_a1", null)
        api.listProjects()
        server.takeRequest().let { assertEquals("PATCH", it.method); assertEquals("/projects/pj_docs", it.path); assertEquals("""{"title":"Docs site"}""", it.body.readUtf8()) }
        server.takeRequest().let { assertEquals("/projects/pj_promo/merge", it.path); assertEquals("""{"into":"pj_docs"}""", it.body.readUtf8()) }
        server.takeRequest().let { assertEquals("/missions/ms_a1", it.path); assertEquals("""{"project":"pj_docs"}""", it.body.readUtf8()) }
        server.takeRequest().let { assertEquals("""{"project":null}""", it.body.readUtf8()) }
        assertEquals("/projects?state=open", server.takeRequest().path)
    }
}
