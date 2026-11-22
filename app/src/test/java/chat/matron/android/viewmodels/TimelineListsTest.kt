package chat.matron.android.viewmodels

import chat.matron.android.designsystem.TimelineListCopy
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.TimelineFinishedPage
import chat.matron.android.models.FinishedProject
import chat.matron.android.models.StalledList
import chat.matron.android.models.StalledProject
import chat.matron.android.models.TimelinePlace
import chat.matron.android.models.TimelineProjectList
import chat.matron.android.models.TimelineProjectRow
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineListsTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val now = Instant.parse("2026-11-20T12:00:00Z")

    private class Lists : FakeTimelineBase() {
        var quietReads = 0
        var projectsError: Throwable? = null
        val finishedPages = mutableMapOf<String?, TimelineFinishedPage>()
        val projectPages = mutableMapOf<String?, TimelineProjectList>()
        override suspend fun wentQuiet(): StalledList {
            quietReads += 1
            return StalledList(5, listOf(StalledProject(TimelineFixtures.promo, weekly = listOf(1, 2, 0))))
        }
        override suspend fun openLong() = StalledList(1, listOf(StalledProject(TimelineFixtures.app)))
        override suspend fun finishedProjects(before: String?, limit: Int?) = finishedPages[before] ?: TimelineFinishedPage(emptyList(), null)
        override suspend fun timelineProjects(before: String?, limit: Int?): TimelineProjectList {
            projectsError?.let { throw it }
            return projectPages[before] ?: TimelineProjectList(0, emptyList())
        }
    }

    @Test
    fun countsAreReadOnceUntilARefresh() = runBlocking {
        val lists = Lists().apply { projectPages[null] = TimelineProjectList(9, listOf(TimelineProjectRow(TimelineFixtures.docs))) }
        val vm = TimelineViewModel(lists, scope)
        vm.loadCounts()
        assertEquals(5, vm.state.value.quiet?.total)
        assertEquals(1, vm.state.value.long?.total)
        assertEquals(9, vm.state.value.allProjects?.total)
        vm.loadList(TimelinePlace.Quiet)
        assertEquals(1, lists.quietReads)
        vm.refresh()
        vm.loadList(TimelinePlace.Quiet)
        assertEquals(2, lists.quietReads)
    }

    @Test
    fun aRefreshReadsTheCountsAgain() = runBlocking {
        val lists = Lists()
        val vm = TimelineViewModel(lists, scope)
        vm.start()
        vm.loadCounts()
        vm.refresh()
        assertEquals(2, lists.quietReads)
    }

    @Test
    fun aStalledReadOvertakenByARefreshNeverLandsOverIt() = runBlocking {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        val lists = object : FakeTimelineBase() {
            var reads = 0
            override suspend fun wentQuiet(): StalledList {
                reads += 1
                if (reads == 1) hold.await()
                return StalledList(reads, emptyList())
            }
        }
        val vm = TimelineViewModel(lists, scope)
        val first = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch { vm.loadList(TimelinePlace.Quiet) }
        vm.refresh()
        // The refresh read the list itself (its counts); the older read's
        // answer, landing after it, is dropped.
        assertEquals(2, vm.state.value.quiet?.total)
        hold.complete(Unit)
        first.join()
        assertEquals(2, vm.state.value.quiet?.total)
        vm.loadList(TimelinePlace.Quiet)
        assertEquals(2, lists.reads)
    }

    @Test
    fun aListOnScreenSaysItsErrorsInAnAlert() {
        assertTrue(chat.matron.android.features.projects.listShowsError(TimelineListContent.Finished(emptyList())))
        assertFalse(chat.matron.android.features.projects.listShowsError(TimelineListContent.Failed))
        assertFalse(chat.matron.android.features.projects.listShowsError(TimelineListContent.Loading))
    }

    @Test
    fun aJournalWithoutTheProjectsListOffersNone() = runBlocking {
        val vm = TimelineViewModel(Lists().apply { projectsError = JournalApiError.NotFound }, scope)
        vm.loadCounts()
        assertEquals(false, vm.state.value.projectsSupported)
        assertFalse(vm.state.value.showsAllProjects)
        assertEquals(listOf(TimelinePlace.Quiet, TimelinePlace.Long, TimelinePlace.Finished), TimelineListCopy.footPlaces(false))
    }

    @Test
    fun finishedAndAllProjectsPageOn() = runBlocking {
        val lists = Lists().apply {
            finishedPages[null] = TimelineFinishedPage(listOf(FinishedProject(TimelineFixtures.docs, now)), "c1")
            finishedPages["c1"] = TimelineFinishedPage(listOf(FinishedProject(TimelineFixtures.docs, now), FinishedProject(TimelineFixtures.pricing, now)), null)
            projectPages[null] = TimelineProjectList(2, listOf(TimelineProjectRow(TimelineFixtures.docs)), "p1")
            projectPages["p1"] = TimelineProjectList(2, listOf(TimelineProjectRow(TimelineFixtures.promo)), null)
        }
        val vm = TimelineViewModel(lists, scope)
        assertEquals(TimelineListContent.Loading, vm.state.value.listContent(TimelinePlace.Finished))
        vm.loadList(TimelinePlace.Finished)
        assertTrue(vm.state.value.hasMoreFinished)
        vm.loadMoreFinished()
        assertEquals(listOf("pj_docs", "pj_pricing"), vm.state.value.finished.map { it.project.id })
        assertFalse(vm.state.value.hasMoreFinished)
        vm.loadList(TimelinePlace.Projects)
        vm.loadMoreProjects()
        assertEquals(listOf("pj_docs", "pj_promo"), vm.state.value.allProjects?.rows?.map { it.project.id })
        assertNull(vm.state.value.allProjects?.nextBefore)
    }

    @Test
    fun aFailedNextPageOffersRetryUntilItLands() = runBlocking {
        var fail = true
        val lists = object : FakeTimelineBase() {
            override suspend fun finishedProjects(before: String?, limit: Int?): TimelineFinishedPage {
                if (before == null) return TimelineFinishedPage(listOf(FinishedProject(TimelineFixtures.docs, now)), "c1")
                if (fail) throw JournalApiError.Transport("offline")
                return TimelineFinishedPage(listOf(FinishedProject(TimelineFixtures.pricing, now)), null)
            }
        }
        val vm = TimelineViewModel(lists, scope)
        vm.loadList(TimelinePlace.Finished)
        vm.loadMoreFinished()
        assertEquals(setOf(TimelinePlace.Finished), vm.state.value.pagingFailed)
        fail = false
        vm.loadMoreFinished()
        assertTrue(vm.state.value.pagingFailed.isEmpty())
        assertEquals(2, vm.state.value.finished.size)
    }

    @Test
    fun aReloadedFirstPageClearsTheRetry() = runBlocking {
        val lists = object : FakeTimelineBase() {
            override suspend fun finishedProjects(before: String?, limit: Int?): TimelineFinishedPage {
                if (before == null) return TimelineFinishedPage(listOf(FinishedProject(TimelineFixtures.docs, now)), "c1")
                throw JournalApiError.Transport("offline")
            }
        }
        val vm = TimelineViewModel(lists, scope)
        vm.loadList(TimelinePlace.Finished)
        vm.loadMoreFinished()
        assertEquals(setOf(TimelinePlace.Finished), vm.state.value.pagingFailed)
        vm.refresh()
        vm.loadList(TimelinePlace.Finished)
        assertTrue(vm.state.value.pagingFailed.isEmpty())
    }

    @Test
    fun aFinishedReadOvertakenByARefreshIsReadAgain() = runBlocking {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        val lists = object : FakeTimelineBase() {
            override suspend fun finishedProjects(before: String?, limit: Int?): TimelineFinishedPage {
                reads += 1
                val mine = reads
                if (mine == 1) hold.await()
                return TimelineFinishedPage(List(mine) { FinishedProject(TimelineFixtures.docs.copy(id = "pj_$it"), now) }, null)
            }
        }
        val vm = TimelineViewModel(lists, scope)
        val first = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch { vm.loadList(TimelinePlace.Finished) }
        vm.refresh()
        hold.complete(Unit)
        first.join()
        vm.loadList(TimelinePlace.Finished)
        assertEquals(2, reads)
        assertEquals(2, vm.state.value.finished.size)
    }

    @Test
    fun askingTheCoordinatorNamesTheProjectAndMarksTheRow() = runBlocking {
        val sent = mutableListOf<String>()
        val vm = TimelineViewModel(Lists(), scope, askCoordinator = { sent += it }, now = { now })
        assertTrue(vm.state.value.canAsk)
        vm.ask(StalledProject(TimelineFixtures.promo, lastMovedAt = now.minusSeconds(19 * 86_400)))
        assertEquals(listOf("What should we do with the project “Promo launch”? It last moved 19 days ago."), sent)
        assertEquals(setOf("pj_promo"), vm.state.value.askedProjectIDs)
        assertFalse(TimelineViewModel(Lists(), scope).state.value.canAsk)
    }

    @Test
    fun aSecondTapWhileTheQuestionIsOnItsWaySendsNothing() = runBlocking {
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val vm = TimelineViewModel(Lists(), scope, askCoordinator = { hold.await(); sent += it }, now = { now })
        val row = StalledProject(TimelineFixtures.promo)
        val first = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch { vm.ask(row) }
        vm.ask(row)
        hold.complete(Unit)
        first.join()
        vm.ask(row)
        assertEquals(1, sent.size)
    }

    @Test
    fun aListsErrorNeverReachesTheWeekPage() = runBlocking {
        val lists = object : FakeTimelineBase() {
            override suspend fun openLong(): StalledList = throw JournalApiError.Transport("offline")
        }
        val vm = TimelineViewModel(lists, scope)
        vm.loadList(TimelinePlace.Long)
        assertEquals(TimelineListContent.Failed, vm.state.value.listContent(TimelinePlace.Long))
        assertNull(vm.state.value.error)
        vm.dismissListError()
        assertNull(vm.state.value.listError)
    }

    @Test
    fun listWordsAndChart() {
        assertEquals("yesterday", TimelineListFormat.daysAgo(now.minusSeconds(86_400), now))
        assertEquals("3h ago", TimelineListFormat.daysAgo(now.minusSeconds(3 * 3600), now))
        assertEquals("Moved 5 days ago", TimelineListFormat.moved(now.minusSeconds(5 * 86_400), now))
        assertNull(TimelineListFormat.lastMoved(null, now))
        assertEquals(listOf(0, 0, 0, 0, 1, 2, 0), TimelineListFormat.chartBars(listOf(1, 2, 0)))
        assertEquals(listOf(2, 3, 4, 5, 6, 7, 8), TimelineListFormat.chartBars(listOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertEquals("And 3 more.", TimelineListFormat.moreRows(3))
        for (place in listOf(TimelinePlace.Projects, TimelinePlace.Quiet, TimelinePlace.Long, TimelinePlace.Finished)) {
            assertEquals(place, TimelineListCopy.place(TimelineListCopy.wire(place)))
        }
    }
}
