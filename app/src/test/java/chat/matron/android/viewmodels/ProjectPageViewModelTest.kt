package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.ProjectsProviding
import chat.matron.android.journal.TimelineHistoryPage
import chat.matron.android.models.ConversationMissionLink
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionState
import chat.matron.android.models.Project
import chat.matron.android.models.ProjectDetail
import chat.matron.android.models.ProjectFilesPage
import chat.matron.android.models.TimelineHistoryWeek
import chat.matron.android.models.TimelineProjectPage
import chat.matron.android.models.TimelineProjectRef
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// A projects provider a test fills in; writes are recorded.
internal open class FakeProjects : ProjectsProviding {
    var projects = listOf<Project>()
    val details = mutableMapOf<String, ProjectDetail>()
    val files = mutableMapOf<String?, ProjectFilesPage>()
    val writes = mutableListOf<String>()
    var writeError: Throwable? = null
    var links = listOf<ConversationMissionLink>()

    override suspend fun listProjects(openOnly: Boolean) = projects
    override suspend fun project(id: String) = details[id] ?: throw JournalApiError.NotFound
    override suspend fun projectFiles(id: String, before: String?, limit: Int?) = files[before] ?: ProjectFilesPage()
    override suspend fun mergeProject(id: String, into: String) { writeError?.let { throw it }; writes += "merge:$id>$into" }
    override suspend fun renameProject(id: String, title: String) { writeError?.let { throw it }; writes += "rename:$id:$title" }
    override suspend fun closeProject(id: String, summary: String) { writeError?.let { throw it }; writes += "close:$id:$summary" }
    override suspend fun setMissionProject(missionID: String, project: String?): Mission {
        writeError?.let { throw it }
        writes += "file:$missionID>${project ?: "none"}"
        return Mission(id = missionID, num = 1, title = "m", originConvoID = "c1", projectID = project)
    }
    override suspend fun conversationMissions(convoID: String) = links
}

class ProjectPageViewModelTest {
    private val docs = TimelineProjectRef("pj_docs", 12, "Docs site launch")
    private fun history(key: String) = TimelineHistoryWeek(key, Instant.parse("${key}T00:00:00Z"), Instant.parse("${key}T00:00:00Z").plusSeconds(604_800))

    private class Timeline(val pages: MutableMap<String, TimelineProjectPage>) : FakeTimelineBase() {
        val historyPages = mutableMapOf<Pair<String, String?>, TimelineHistoryPage>()
        val historyAsks = mutableListOf<Pair<String, String?>>()
        override suspend fun timelineProject(id: String) = pages[id] ?: throw JournalApiError.NotFound
        override suspend fun timelineProjectHistory(id: String, before: String?, limit: Int?): TimelineHistoryPage {
            historyAsks += id to before
            return historyPages[id to before] ?: TimelineHistoryPage(emptyList(), null)
        }
    }

    @Test
    fun aPageLoadsAndAMissingProjectSaysSo() = runBlocking {
        val timeline = Timeline(mutableMapOf("pj_docs" to TimelineProjectPage(docs, weeks = 3)))
        val vm = ProjectPageViewModel("pj_docs", timeline, FakeProjects())
        vm.refreshPage()
        assertEquals(3, vm.state.value.page?.weeks)
        val gone = ProjectPageViewModel("pj_gone", timeline, FakeProjects())
        gone.refreshPage()
        assertTrue(gone.state.value.isMissing)
        assertNull(gone.state.value.error)
    }

    @Test
    fun aProjectThatGoesAwayLeavesNothingOnScreen() = runBlocking {
        val timeline = Timeline(mutableMapOf("pj_docs" to TimelineProjectPage(docs)))
        val vm = ProjectPageViewModel("pj_docs", timeline, FakeProjects())
        vm.refreshPage()
        timeline.pages.clear()
        vm.refresh()
        assertTrue(vm.state.value.isMissing)
        assertNull(vm.state.value.page)
    }

    @Test
    fun aFailedSectionReadIsReadAgainOnRefresh() = runBlocking {
        val projects = FakeProjects()
        val vm = ProjectPageViewModel("pj_docs", Timeline(mutableMapOf("pj_docs" to TimelineProjectPage(docs))), projects)
        vm.loadMissions()
        assertNull(vm.state.value.openMissions)
        projects.details["pj_docs"] = ProjectDetail(Project(id = "pj_docs", num = 12, title = "Docs site launch"), emptyList())
        vm.refresh()
        assertEquals(emptyList<Mission>(), vm.state.value.openMissions)
    }

    @Test
    fun anOlderMissionsReadNeverLandsOverANewerOne() = runBlocking {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        val projects = object : FakeProjects() {
            override suspend fun project(id: String): ProjectDetail {
                reads += 1
                val mine = reads
                if (mine == 1) hold.await()
                return ProjectDetail(Project(id = id, num = 12, title = "Docs site launch"), listOf(Mission(id = "ms_$mine", num = mine, title = "m$mine", originConvoID = "c1")))
            }
        }
        val vm = ProjectPageViewModel("pj_docs", Timeline(mutableMapOf("pj_docs" to TimelineProjectPage(docs))), projects)
        val first = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { vm.loadMissions() }
        vm.loadMissions()
        hold.complete(Unit)
        first.join()
        assertEquals(listOf("ms_2"), vm.state.value.openMissions?.map { it.id })
    }

    @Test
    fun aMergedProjectFollowsTheOneItBecame() = runBlocking {
        val timeline = Timeline(mutableMapOf("pj_old" to TimelineProjectPage(docs, mergedFromID = "pj_old")))
        timeline.historyPages["pj_docs" to null] = TimelineHistoryPage(listOf(history("2026-11-16")), null)
        val vm = ProjectPageViewModel("pj_old", timeline, FakeProjects())
        vm.refreshPage()
        assertEquals("pj_docs", vm.state.value.redirectedTo)
        assertEquals("pj_docs", vm.projectID)
        vm.loadHistory()
        assertEquals(listOf("pj_docs" to null), timeline.historyAsks)
    }

    @Test
    fun historyPagesOnWithoutRepeatsAndLoadsOnce() = runBlocking {
        val timeline = Timeline(mutableMapOf("pj_docs" to TimelineProjectPage(docs)))
        timeline.historyPages["pj_docs" to null] = TimelineHistoryPage(listOf(history("2026-11-16"), history("2026-11-09")), "2026-11-09")
        timeline.historyPages["pj_docs" to "2026-11-09"] = TimelineHistoryPage(listOf(history("2026-11-09"), history("2026-11-02")), null)
        val vm = ProjectPageViewModel("pj_docs", timeline, FakeProjects())
        vm.loadHistoryIfNeeded()
        assertTrue(vm.state.value.hasMoreHistory)
        vm.loadMoreHistory()
        assertEquals(listOf("2026-11-16", "2026-11-09", "2026-11-02"), vm.state.value.history.map { it.week })
        assertFalse(vm.state.value.hasMoreHistory)
        vm.loadHistoryIfNeeded()
        vm.loadMoreHistory()
        assertEquals(2, timeline.historyAsks.size)
    }

    @Test
    fun everyLandedHistoryPageBumpsTheVersionAndAFailureOffersRetry() = runBlocking {
        var fail = true
        val timeline = object : FakeTimelineBase() {
            override suspend fun timelineProjectHistory(id: String, before: String?, limit: Int?): TimelineHistoryPage {
                if (before == null) return TimelineHistoryPage(listOf(history("2026-11-16")), "2026-11-16")
                if (fail) throw JournalApiError.Transport("offline")
                // A page of weeks already listed still moves the cursor on.
                return TimelineHistoryPage(listOf(history("2026-11-16")), "2026-11-09")
            }
        }
        val vm = ProjectPageViewModel("pj_docs", timeline, FakeProjects())
        vm.loadHistory()
        assertEquals(1, vm.state.value.historyVersion)
        vm.loadMoreHistory()
        assertTrue(vm.state.value.historyPagingFailed)
        assertNull("the Retry says it, not an alert", vm.state.value.error)
        fail = false
        vm.loadMoreHistory()
        assertFalse(vm.state.value.historyPagingFailed)
        assertEquals(1, vm.state.value.history.size)
        assertEquals(2, vm.state.value.historyVersion)
        vm.loadHistory()
        assertEquals(3, vm.state.value.historyVersion)
    }

    @Test
    fun aReloadDuringAnOlderPageReadCanPageAgain() = runBlocking {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        val asks = mutableListOf<String?>()
        val timeline = object : FakeTimelineBase() {
            override suspend fun timelineProjectHistory(id: String, before: String?, limit: Int?): TimelineHistoryPage {
                asks += before
                if (before == null) return TimelineHistoryPage(listOf(history("2026-11-16")), "2026-11-16")
                if (asks.count { it != null } == 1) hold.await()
                return TimelineHistoryPage(listOf(history("2026-11-09")), null)
            }
        }
        val vm = ProjectPageViewModel("pj_docs", timeline, FakeProjects())
        vm.loadHistory()
        val stale = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { vm.loadMoreHistory() }
        vm.loadHistory()
        vm.loadMoreHistory()
        assertEquals(listOf("2026-11-16", "2026-11-09"), vm.state.value.history.map { it.week })
        hold.complete(Unit)
        stale.join()
        assertEquals(listOf("2026-11-16", "2026-11-09"), vm.state.value.history.map { it.week })
    }

    @Test
    fun aCursorThatDoesNotMoveOnEndsTheHistory() = runBlocking {
        val timeline = object : FakeTimelineBase() {
            override suspend fun timelineProjectHistory(id: String, before: String?, limit: Int?): TimelineHistoryPage =
                if (before == null) TimelineHistoryPage(listOf(history("2026-11-16")), "c1") else TimelineHistoryPage(emptyList(), "c1")
        }
        val vm = ProjectPageViewModel("pj_docs", timeline, FakeProjects())
        vm.loadHistory()
        vm.loadMoreHistory()
        assertFalse(vm.state.value.hasMoreHistory)
    }

    @Test
    fun missionsSplitOpenByActivityAndDoneByClose() = runBlocking {
        val t = Instant.parse("2026-11-20T10:00:00Z")
        fun m(id: String, state: MissionState = MissionState.OPEN, last: Instant? = null, closed: Instant? = null) =
            Mission(id = id, num = id.length, state = state, title = id, originConvoID = "c1", lastMilestoneAt = last, closedAt = closed, createdAt = t)
        val projects = FakeProjects().apply {
            details["pj_docs"] = ProjectDetail(
                Project(id = "pj_docs", num = 12, title = "Docs site launch"),
                listOf(m("quiet"), m("busy", last = t), m("older", last = t.minusSeconds(3600)),
                    m("done1", MissionState.CLOSED, closed = t.minusSeconds(86_400)), m("done2", MissionState.CLOSED, closed = t)),
            )
        }
        val vm = ProjectPageViewModel("pj_docs", Timeline(mutableMapOf()), projects)
        vm.loadMissions()
        assertEquals(listOf("busy", "older", "quiet"), vm.state.value.openMissions?.map { it.id })
        assertEquals(listOf("done2", "done1"), vm.state.value.closedMissions.map { it.id })
    }
}
