package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.ProjectCloseError
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionState
import chat.matron.android.models.Project
import chat.matron.android.models.TimelineProjectPage
import chat.matron.android.models.TimelineProjectRef
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectActionsTest {
    private val docs = TimelineProjectRef("pj_docs", 12, "Docs site launch")
    private val promo = TimelineProjectRef("pj_promo", 14, "Promo launch")

    private class Pages : FakeTimelineBase() {
        val pages = mutableMapOf<String, TimelineProjectPage>()
        var reads = 0
        override suspend fun timelineProject(id: String): TimelineProjectPage {
            reads += 1
            return pages[id] ?: throw JournalApiError.NotFound
        }
    }

    private fun mission(id: String, project: String? = null, state: MissionState = MissionState.OPEN) =
        Mission(id = id, num = id.length, state = state, title = id, originConvoID = "c1", projectID = project)

    private fun setup(state: String = "open"): Triple<ProjectPageViewModel, FakeProjects, MutableList<String>> {
        val pages = Pages().apply {
            pages["pj_docs"] = TimelineProjectPage(docs, state = state)
            pages["pj_promo"] = TimelineProjectPage(promo)
        }
        val projects = FakeProjects().apply {
            projects = listOf(Project(id = "pj_docs", num = 12, title = "Docs site launch"), Project(id = "pj_promo", num = 14, title = "Promo launch"))
        }
        val events = mutableListOf<String>()
        val vm = ProjectPageViewModel(
            "pj_docs", pages, projects,
            unfiledMissions = { listOf(mission("ms_loose"), mission("ms_filed", "pj_promo"), mission("ms_done", state = MissionState.CLOSED)) },
            askCoordinator = { events += "ask:$it" },
            missionChanged = { events += "mission:$it" },
            projectChanged = { events += "changed" },
        )
        return Triple(vm, projects, events)
    }

    @Test
    fun theMenuWaitsForAnOpenProjectAndItsLists() = runBlocking {
        val (vm, _, _) = setup()
        vm.refreshPage()
        assertFalse(vm.state.value.actions.rename)
        vm.loadActionTargets()
        assertEquals(ProjectActionsAvailability(addMission = true, rename = true, merge = true, close = true), vm.state.value.actions)
        assertEquals(listOf("ms_loose"), vm.state.value.unfiled.map { it.id })
        assertEquals(listOf("pj_promo"), vm.state.value.mergeTargets.map { it.id })
        val (closed, _, _) = setup(state = "closed")
        closed.refreshPage(); closed.loadActionTargets()
        assertEquals(ProjectActionsAvailability(false, false, false, false), closed.state.value.actions)
        assertEquals(ProjectActionsAvailability(false, true, false, true), ProjectActionsAvailability.of(true, true, hasUnfiled = false, hasMergeTargets = false))
    }

    @Test
    fun writesReachTheJournalAndTellTheTimeline() = runBlocking {
        val (vm, projects, events) = setup()
        vm.refreshPage(); vm.loadActionTargets()
        assertTrue(vm.rename("  Docs site  "))
        assertFalse(vm.rename("   "))
        assertTrue(vm.addMission("ms_loose"))
        assertTrue(vm.state.value.unfiled.isEmpty())
        assertTrue(vm.close())
        assertEquals(listOf("rename:pj_docs:Docs site", "file:ms_loose>pj_docs", "close:pj_docs:Closed from the app."), projects.writes)
        assertEquals(listOf("changed", "mission:ms_loose", "changed", "changed"), events)
    }

    @Test
    fun aMergeMovesThePageToTheProjectItBecame() = runBlocking {
        val (vm, projects, _) = setup()
        vm.refreshPage(); vm.loadActionTargets()
        assertTrue(vm.merge("pj_promo"))
        assertEquals(listOf("merge:pj_docs>pj_promo"), projects.writes)
        assertEquals("pj_promo", vm.projectID)
        assertEquals("pj_promo", vm.state.value.redirectedTo)
        assertEquals("Promo launch", vm.state.value.page?.project?.title)
        assertEquals(listOf("pj_docs"), vm.state.value.mergeTargets.map { it.id })
    }

    @Test
    fun aMergeLeavesNothingOfTheOldProjectAndUnlocksTheAsk() = runBlocking {
        val (vm, _, _) = setup()
        vm.refreshPage(); vm.loadActionTargets(); vm.ask()
        assertTrue(vm.state.value.asked)
        vm.merge("pj_promo")
        assertFalse(vm.state.value.asked)
        assertEquals("pj_promo", vm.state.value.page?.project?.id)
        assertTrue(vm.state.value.actionsLoaded)
    }

    @Test
    fun aFailedListsReadIsReadAgainOnRefresh() = runBlocking {
        var failing = true
        val pages = Pages().apply { pages["pj_docs"] = TimelineProjectPage(docs) }
        val projects = object : FakeProjects() {
            override suspend fun listProjects(openOnly: Boolean): List<Project> {
                if (failing) throw JournalApiError.Transport("offline")
                return listOf(Project(id = "pj_promo", num = 14, title = "Promo launch"))
            }
        }
        val vm = ProjectPageViewModel("pj_docs", pages, projects)
        vm.refreshPage(); vm.loadActionTargets()
        assertFalse(vm.state.value.actionsLoaded)
        failing = false
        vm.refresh()
        assertTrue(vm.state.value.actions.merge)
    }

    @Test
    fun aListsReadStartedBeforeAMergeNeverLandsAfterIt() = runBlocking {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        val pages = Pages().apply {
            pages["pj_docs"] = TimelineProjectPage(docs)
            pages["pj_promo"] = TimelineProjectPage(promo)
        }
        val projects = object : FakeProjects() {
            override suspend fun listProjects(openOnly: Boolean): List<Project> {
                reads += 1
                val mine = reads
                if (mine == 2) hold.await()
                // Before the merge both are open; after it only the survivor.
                return if (mine <= 2) listOf(Project(id = "pj_docs", num = 12, title = "Docs site launch"), Project(id = "pj_promo", num = 14, title = "Promo launch"))
                else listOf(Project(id = "pj_promo", num = 14, title = "Promo launch"))
            }
        }
        val vm = ProjectPageViewModel("pj_docs", pages, projects)
        vm.refreshPage(); vm.loadActionTargets()
        val stale = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch { vm.loadActionTargets() }
        vm.merge("pj_promo")
        hold.complete(Unit)
        stale.join()
        assertEquals(emptyList<String>(), vm.state.value.mergeTargets.map { it.id })
    }

    @Test
    fun refusalsAreSaidInWords() = runBlocking {
        val (vm, projects, _) = setup()
        vm.refreshPage(); vm.loadActionTargets()
        projects.writeError = ProjectCloseError.OpenMissions
        assertFalse(vm.close())
        assertEquals("Close or move this project's open missions first.", vm.state.value.error)
        projects.writeError = JournalApiError.Conflict
        assertFalse(vm.rename("Docs"))
        assertEquals(ProjectActionsFormat.NOT_OPEN, vm.state.value.error)
        assertFalse(vm.state.value.isBusy)
    }

    @Test
    fun askingNamesTheProjectOnce() = runBlocking {
        val (vm, _, events) = setup()
        vm.refreshPage()
        vm.ask()
        assertEquals(listOf("ask:Tell me about the project “Docs site launch”: where it is and what is next."), events)
        assertTrue(vm.state.value.asked)
        assertEquals("Merge “Docs site launch” into “Promo launch”? Its missions move there and this project closes.",
            ProjectActionsFormat.mergeConfirmation("Docs site launch", "Promo launch"))
    }
}
