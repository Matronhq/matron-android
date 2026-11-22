package chat.matron.android.journal

import chat.matron.android.models.TimelineMarks
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

/// The `/timeline` reads against a MockWebServer plus the pure decoders.
class TimelineApiTest {
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
        const val WEEKS_JSON = """{"weeks":[
            {"week":"2026-11-16","start":1763247600000,"end":1763852400000,"sentence":"Docs site launch shipped.","moved":4},
            {"week":"2026-11-09","start":1762642800000,"end":1763247600000,"sentence":"","moved":2},
            {"week":"","start":1,"end":2}],"next_before":"2026-11-09"}"""

        const val WEEK_JSON = """{"week":"2026-11-16","start":1763247600000,"end":1763852400000,"final":false,
            "lede":"A busy week.","stories":[
              {"project":{"id":"pj_docs","num":12,"title":"Docs site launch"},"title":"Docs go live",
               "projects":[{"id":"pj_promo","num":14,"title":"Promo launch"},{"id":"pj_docs","num":12,"title":"Docs site launch"}],
               "sentence":"The docs site went live on example.com.","next":"Add search.",
               "needs_you":[{"id":"it_1","num":31,"title":"Pick a theme"},{"id":"it_2","num":32,"title":"Approve copy","done":true}],
               "media":{"blob_id":"bl_1","content_type":"image/png","name":"home.png"},
               "marks":{"for_you":1,"done":3,"next":true}},
              {"project":{"id":"pj_promo","num":14,"title":"Promo launch"},"sentence":null,
               "needs_you":[{"id":"it_3","num":33,"title":"Sign off"},{"id":"it_4","num":34,"title":"Old","done":true}],"next":"Book the slot."},
              {"project":{"num":15}}],
            "also":[{"id":"pj_app","num":16,"title":"App store listing"},{"title":"no id"}]}"""
    }

    @Test
    fun weeksDecodeNewestFirstAndDropMalformedRows() {
        val page = TimelineDecoding.weeks(obj(WEEKS_JSON))
        assertEquals(listOf("2026-11-16", "2026-11-09"), page.weeks.map { it.week })
        assertEquals(4, page.weeks[0].moved)
        assertEquals("2026-11-09", page.nextBefore)
        assertNull(TimelineDecoding.weeks(obj("""{"weeks":[]}""")).nextBefore)
    }

    @Test
    fun aWeeksResponseWithoutItsArrayIsATransportError() {
        try {
            TimelineDecoding.weeks(obj("{}")); fail("expected Transport")
        } catch (_: JournalApiError.Transport) {
        }
    }

    @Test
    fun weekStoriesKeepTheMainProjectFirstAndDeriveMissingMarks() {
        val week = TimelineDecoding.week(obj(WEEK_JSON))
        assertFalse(week.final)
        assertEquals(2, week.stories.size)
        val docs = week.stories[0]
        assertEquals("Docs go live", docs.title)
        assertEquals(listOf("pj_docs", "pj_promo"), docs.projects.map { it.id })
        assertEquals(TimelineMarks(forYou = 1, done = 3, next = true), docs.marks)
        assertEquals("bl_1", docs.media?.blobID)
        assertFalse(docs.media!!.isVideo)
        assertTrue(docs.needsYou[1].done)
        // No marks sent: the items still waiting and the next step count.
        val promo = week.stories[1]
        assertNull(promo.sentence)
        assertEquals(TimelineMarks(forYou = 1, done = 0, next = true), promo.marks)
        assertEquals(listOf("pj_app"), week.also.map { it.id })
    }

    @Test
    fun weekProjectsPageDecodesItsPaging() {
        val page = TimelineDecoding.weekProjects(obj("""{"week":"2026-11-16","total":30,"offset":0,"next_offset":24,"projects":[
            {"project":{"id":"pj_app","num":16,"title":"App store listing"},"moved":5,"sentence":"Screens uploaded.",
             "media":{"blob_id":"bl_9","content_type":"video/mp4","name":"tour.mp4"},"marks":{"for_you":2}},
            {"moved":1}]}"""))
        assertEquals(30, page.total); assertEquals(24, page.nextOffset)
        assertEquals(1, page.projects.size)
        val story = page.projects[0].story
        assertEquals("App store listing", story.project.title)
        assertTrue(story.media!!.isVideo)
        assertEquals(TimelineMarks(forYou = 2), story.marks)
        assertNull(TimelineDecoding.weekProjects(obj("""{"week":"2026-11-16","projects":[]}""")).nextOffset)
    }

    @Test
    fun stalledFinishedAndAllProjectsDecode() {
        val quiet = TimelineDecoding.stalled(obj("""{"total":3,"rows":[
            {"project":{"id":"pj_promo","num":14,"title":"Promo launch"},"last_moved_at":1762000000000,"weekly":[0,2,"x",1],"note":"Waiting on bob."},
            {"nope":true}]}"""))
        assertEquals(3, quiet.total)
        assertEquals(listOf(0, 2, 0, 1), quiet.rows[0].weekly)
        assertEquals("Waiting on bob.", quiet.rows[0].note)
        val finished = TimelineDecoding.finished(obj("""{"rows":[{"project":{"id":"pj_docs","num":12,"title":"Docs site launch"},"finished_at":1763000000000},
            {"project":{"id":"pj_x","num":2,"title":"x"}}],"next_before":"1763000000000:pj_docs"}"""))
        assertEquals(listOf("pj_docs"), finished.rows.map { it.project.id })
        assertEquals("1763000000000:pj_docs", finished.nextBefore)
        val all = TimelineDecoding.projects(obj("""{"total":2,"rows":[{"project":{"id":"pj_docs","num":12,"title":"Docs site launch"},"open_missions":2}]}"""))
        assertEquals(2, all.total); assertEquals(2, all.rows[0].openMissions); assertNull(all.rows[0].lastMovedAt)
    }

    @Test
    fun projectPageAndHistoryDecode() {
        val page = TimelineDecoding.project(obj("""{"project":{"id":"pj_docs","num":12,"title":"Docs site launch","live":true,"state":"open"},
            "sentence":"Live on example.com.","next":"Add search.","needs_you":[{"id":"it_1","num":31,"title":"Pick a theme"}],
            "needs_you_total":4,"counts":{"weeks":6,"missions_open":2,"missions_done":3,"files":7},"merged_from":{"id":"pj_old"}}"""))
        assertTrue(page.isLive); assertTrue(page.isOpen)
        assertEquals(4, page.needsYouTotal); assertEquals(6, page.weeks); assertEquals(7, page.files)
        assertEquals("pj_old", page.mergedFromID)
        val bare = TimelineDecoding.project(obj("""{"project":{"id":"pj_promo","num":14,"title":"Promo launch"},"needs_you":[{"id":"it_3","num":33,"title":"Sign off"}]}"""))
        assertEquals(1, bare.needsYouTotal); assertEquals("open", bare.state); assertFalse(bare.isLive)

        val history = TimelineDecoding.history(obj("""{"weeks":[{"week":"2026-11-16","start":1763247600000,"end":1763852400000,
            "card":{"sentence":"The docs went live.","media":{"blob_id":"bl_1","content_type":"image/png","name":"home.png"}},
            "milestones":[{"id":"ml_1","mission_id":"ms_1","num":40,"kind":"progress","title":"Deployed","convo_id":"c1","seq":12,"created_at":1763300000000,"mission_num":20}],
            "more_milestones":2},{"week":"2026-11-09","start":1762642800000,"end":1763247600000}],"next_before":null}"""))
        assertEquals(2, history.weeks.size)
        assertEquals("The docs went live.", history.weeks[0].sentence)
        assertEquals(20, history.weeks[0].milestones[0].missionNum)
        assertEquals(2, history.weeks[0].moreMilestones)
        assertNull(history.weeks[1].sentence)
        assertNull(history.nextBefore)
    }

    @Test
    fun routesAskTheRightPaths() = runBlocking {
        server.enqueue(json(200, WEEKS_JSON))
        server.enqueue(json(200, WEEK_JSON))
        server.enqueue(json(200, """{"week":"2026-11-16","total":0,"offset":24,"projects":[]}"""))
        server.enqueue(json(200, """{"total":0,"rows":[]}"""))
        server.enqueue(json(200, """{"weeks":[]}"""))
        val api = api()
        api.timelineWeeks(before = "2026-11-09", limit = 12)
        api.timelineWeek("2026-11-16")
        api.timelineWeekProjects("2026-11-16", offset = -3, limit = 500)
        api.timelineProjects()
        api.timelineProjectHistory("pj_docs", before = "2026-11-02")
        assertEquals("/timeline/weeks?before=2026-11-09&limit=12", server.takeRequest().path)
        assertEquals("/timeline/weeks/2026-11-16", server.takeRequest().path)
        assertEquals("/timeline/weeks/2026-11-16/projects?offset=0&limit=50", server.takeRequest().path)
        assertEquals("/timeline/projects", server.takeRequest().path)
        assertEquals("/timeline/projects/pj_docs/history?before=2026-11-02", server.takeRequest().path)
    }

    @Test
    fun aJournalWithoutTheTimelineAnswersNotFound() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try {
            api().timelineWeeks(); fail("expected NotFound")
        } catch (_: JournalApiError.NotFound) {
        }
    }
}
