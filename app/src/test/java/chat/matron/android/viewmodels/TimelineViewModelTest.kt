package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.TimelineFinishedPage
import chat.matron.android.journal.TimelineHistoryPage
import chat.matron.android.journal.TimelineProviding
import chat.matron.android.journal.TimelineWeeksPage
import chat.matron.android.models.StalledList
import chat.matron.android.models.TimelineMarks
import chat.matron.android.models.TimelineProjectList
import chat.matron.android.models.TimelineProjectPage
import chat.matron.android.models.TimelineProjectRef
import chat.matron.android.models.TimelineStory
import chat.matron.android.models.TimelineWeek
import chat.matron.android.models.TimelineWeekExtras
import chat.matron.android.models.TimelineWeekProject
import chat.matron.android.models.TimelineWeekProjectsPage
import chat.matron.android.models.TimelineWeekSummary
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Every read answers empty (or 404 for a project); tests override what they need.
internal open class FakeTimelineBase : TimelineProviding {
    override suspend fun timelineWeeks(before: String?, limit: Int?) = TimelineWeeksPage(emptyList(), null)
    override suspend fun timelineWeek(week: String): TimelineWeek = throw JournalApiError.NotFound
    override suspend fun timelineWeekProjects(week: String, offset: Int, limit: Int?): TimelineWeekProjectsPage = throw JournalApiError.NotFound
    override suspend fun wentQuiet(): StalledList = StalledList(0, emptyList())
    override suspend fun openLong(): StalledList = StalledList(0, emptyList())
    override suspend fun finishedProjects(before: String?, limit: Int?) = TimelineFinishedPage(emptyList(), null)
    override suspend fun timelineProjects(before: String?, limit: Int?) = TimelineProjectList(0, emptyList())
    override suspend fun timelineProject(id: String): TimelineProjectPage = throw JournalApiError.NotFound
    override suspend fun timelineProjectHistory(id: String, before: String?, limit: Int?) = TimelineHistoryPage(emptyList(), null)
}

/// A timeline whose answers a test sets per call.
internal class FakeTimeline : TimelineProviding {
    var weeksPages = mutableMapOf<String?, TimelineWeeksPage>()
    var weeksError: Throwable? = null
    val weeks = mutableMapOf<String, TimelineWeek>()
    /// Held reads: the week read waits on this when set.
    val holds = mutableMapOf<String, CompletableDeferred<Unit>>()
    var weekProjectsPages = mutableMapOf<Int, TimelineWeekProjectsPage>()
    var weekProjectsError: Throwable? = null
    val weekProjectsAsks = mutableListOf<Pair<String, Int>>()
    var weeksReads = 0

    override suspend fun timelineWeeks(before: String?, limit: Int?): TimelineWeeksPage {
        weeksReads += 1
        weeksError?.let { throw it }
        return weeksPages[before] ?: TimelineWeeksPage(emptyList(), null)
    }

    override suspend fun timelineWeek(week: String): TimelineWeek {
        holds[week]?.await()
        return weeks[week] ?: throw JournalApiError.Transport("no week")
    }

    override suspend fun timelineWeekProjects(week: String, offset: Int, limit: Int?): TimelineWeekProjectsPage {
        weekProjectsAsks += week to offset
        weekProjectsError?.let { throw it }
        return weekProjectsPages[offset] ?: TimelineWeekProjectsPage(week, 0, offset, null, emptyList())
    }

    override suspend fun wentQuiet(): StalledList = StalledList(0, emptyList())
    override suspend fun openLong(): StalledList = StalledList(0, emptyList())
    override suspend fun finishedProjects(before: String?, limit: Int?) = TimelineFinishedPage(emptyList(), null)
    override suspend fun timelineProjects(before: String?, limit: Int?) = TimelineProjectList(0, emptyList())
    override suspend fun timelineProject(id: String): TimelineProjectPage = throw JournalApiError.NotFound
    override suspend fun timelineProjectHistory(id: String, before: String?, limit: Int?) = TimelineHistoryPage(emptyList(), null)
}

internal object TimelineFixtures {
    val docs = TimelineProjectRef("pj_docs", 12, "Docs site launch")
    val promo = TimelineProjectRef("pj_promo", 14, "Promo launch")
    val app = TimelineProjectRef("pj_app", 16, "App store listing")
    val pricing = TimelineProjectRef("pj_pricing", 17, "Pricing page refresh")

    fun summary(key: String) = TimelineWeekSummary(key, Instant.parse("${key}T00:00:00Z"), Instant.parse("${key}T00:00:00Z").plusSeconds(7 * 86_400))

    fun week(key: String, final: Boolean = false, stories: List<TimelineStory> = listOf(TimelineStory.of(docs, sentence = "The docs went live.")), also: List<TimelineProjectRef> = emptyList()) =
        TimelineWeek(key, Instant.parse("${key}T00:00:00Z"), Instant.parse("${key}T00:00:00Z").plusSeconds(7 * 86_400), final, stories = stories, also = also)
}

class TimelineViewModelTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun fake(): FakeTimeline = FakeTimeline().apply {
        weeksPages[null] = TimelineWeeksPage(listOf(TimelineFixtures.summary("2026-11-16"), TimelineFixtures.summary("2026-11-09")), "2026-11-09")
        weeksPages["2026-11-09"] = TimelineWeeksPage(listOf(TimelineFixtures.summary("2026-11-02"), TimelineFixtures.summary("2026-11-09")), null)
        weeks["2026-11-16"] = TimelineFixtures.week("2026-11-16")
        weeks["2026-11-09"] = TimelineFixtures.week("2026-11-09", final = true, stories = listOf(TimelineStory.of(TimelineFixtures.promo)))
    }

    @Test
    fun startOpensOnTheNewestWeek() = runBlocking {
        val vm = TimelineViewModel(fake(), scope)
        vm.start()
        val s = vm.state.value
        assertEquals(true, s.isSupported)
        assertEquals("2026-11-16", s.selectedWeek)
        assertEquals("2026-11-16", s.shownWeek?.week)
        assertTrue(s.content is TimelineWeekContent.Week)
        assertTrue(s.hasEarlierWeeks)
    }

    @Test
    fun aJournalWithoutTheTimelineIsUnsupportedAndNotAskedAgain() = runBlocking {
        val timeline = fake().apply { weeksError = JournalApiError.NotFound }
        val vm = TimelineViewModel(timeline, scope)
        vm.shown()
        assertEquals(false, vm.state.value.isSupported)
        assertNull(vm.state.value.error)
        vm.shown()
        assertEquals(1, timeline.weeksReads)
    }

    @Test
    fun aFailedFirstReadOffersTheRetry() = runBlocking {
        val vm = TimelineViewModel(fake().apply { weeksError = JournalApiError.Transport("offline") }, scope)
        vm.start()
        assertEquals(TimelineWeekContent.Failed, vm.state.value.content)
        assertNull(vm.state.value.isSupported)
    }

    @Test
    fun anAnswerWithNoWeeksSaysNothingHasMoved() = runBlocking {
        val vm = TimelineViewModel(FakeTimeline(), scope)
        vm.start()
        assertEquals(TimelineWeekContent.NothingYet, vm.state.value.content)
    }

    @Test
    fun pickingAWeekShowsProgressUntilItArrivesAndASlowerOlderReadIsDropped() = runBlocking {
        val timeline = fake()
        val vm = TimelineViewModel(timeline, scope)
        vm.start()
        val hold = CompletableDeferred<Unit>()
        timeline.holds["2026-11-09"] = hold
        val pick = scope.launch { vm.select("2026-11-09") }
        // The old week never shows under the new pick.
        assertEquals(TimelineWeekContent.Loading, vm.state.value.content)
        vm.select("2026-11-16")
        hold.complete(Unit)
        pick.join()
        yield()
        assertEquals("2026-11-16", vm.state.value.shownWeek?.week)
    }

    @Test
    fun earlierWeeksAppendWithoutRepeats() = runBlocking {
        val vm = TimelineViewModel(fake(), scope)
        vm.start()
        vm.loadEarlierWeeks()
        assertEquals(listOf("2026-11-16", "2026-11-09", "2026-11-02"), vm.state.value.weeks.map { it.week })
        assertFalse(vm.state.value.hasEarlierWeeks)
    }

    @Test
    fun aRefreshKeepsAnEarlierWeekThatWasPicked() = runBlocking {
        val timeline = fake().apply { weeks["2026-11-02"] = TimelineFixtures.week("2026-11-02", final = true) }
        val vm = TimelineViewModel(timeline, scope)
        vm.start()
        vm.loadEarlierWeeks()
        vm.select("2026-11-02")
        vm.refresh()
        assertEquals(listOf("2026-11-16", "2026-11-09", "2026-11-02"), vm.state.value.weeks.map { it.week })
        assertEquals("2026-11-02", vm.state.value.shownWeek?.week)
        assertFalse(vm.state.value.hasEarlierWeeks)
    }

    @Test
    fun aWeekThatSlidesOffTheFirstPageStaysInTheMenu() = runBlocking {
        val timeline = fake()
        val vm = TimelineViewModel(timeline, scope)
        vm.start()
        // A new week begins: the first page now starts a week later.
        timeline.weeksPages[null] = TimelineWeeksPage(listOf(TimelineFixtures.summary("2026-11-23"), TimelineFixtures.summary("2026-11-16")), "2026-11-16")
        vm.refresh()
        assertEquals(listOf("2026-11-23", "2026-11-16", "2026-11-09"), vm.state.value.weeks.map { it.week })
        // Paging goes on from the oldest week known.
        vm.loadEarlierWeeks()
        assertEquals(listOf("2026-11-23", "2026-11-16", "2026-11-09", "2026-11-02"), vm.state.value.weeks.map { it.week })
    }

    @Test
    fun aRefreshAfterSeveralNewWeeksReadsOnUntilTheMenuHasNoHole() = runBlocking {
        val timeline = fake()
        val vm = TimelineViewModel(timeline, scope)
        vm.start()
        vm.select("2026-11-09")
        // Three weeks later: the first page no longer reaches the weeks known.
        timeline.weeksPages[null] = TimelineWeeksPage(listOf(TimelineFixtures.summary("2026-12-07"), TimelineFixtures.summary("2026-11-30")), "2026-11-30")
        timeline.weeksPages["2026-11-30"] = TimelineWeeksPage(listOf(TimelineFixtures.summary("2026-11-23"), TimelineFixtures.summary("2026-11-16")), "2026-11-16")
        vm.refresh()
        assertEquals(listOf("2026-12-07", "2026-11-30", "2026-11-23", "2026-11-16", "2026-11-09"), vm.state.value.weeks.map { it.week })
        assertEquals("2026-11-09", vm.state.value.shownWeek?.week)
        assertTrue(TimelineViewModel.weeksAdjoin("2026-11-16", "2026-11-09"))
        assertFalse(TimelineViewModel.weeksAdjoin("2026-11-30", "2026-11-16"))
    }

    @Test
    fun theWeeksOtherProjectsPageInOnceEach() = runBlocking {
        val timeline = fake().apply {
            weeks["2026-11-16"] = TimelineFixtures.week("2026-11-16", also = listOf(TimelineFixtures.promo, TimelineFixtures.app, TimelineFixtures.pricing))
            weekProjectsPages[0] = TimelineWeekProjectsPage("2026-11-16", 3, 0, 2, listOf(
                // The week's own card is never repeated.
                TimelineWeekProject(TimelineFixtures.docs), TimelineWeekProject(TimelineFixtures.promo, marks = TimelineMarks(forYou = 1)),
            ))
            weekProjectsPages[2] = TimelineWeekProjectsPage("2026-11-16", 3, 2, null, listOf(
                TimelineWeekProject(TimelineFixtures.promo), TimelineWeekProject(TimelineFixtures.app),
            ))
        }
        val vm = TimelineViewModel(timeline, scope)
        vm.start()
        assertEquals(TimelineWeekExtras.Status.MORE, vm.state.value.weekExtras?.status)
        vm.loadMoreWeekProjects()
        assertEquals(listOf("pj_promo"), vm.state.value.weekProjects.map { it.project.id })
        vm.loadMoreWeekProjects()
        assertEquals(listOf("pj_promo", "pj_app"), vm.state.value.weekProjects.map { it.project.id })
        assertEquals(TimelineWeekExtras.Status.DONE, vm.state.value.weekProjectsStatus)
        vm.loadMoreWeekProjects()
        assertEquals(listOf("2026-11-16" to 0, "2026-11-16" to 2), timeline.weekProjectsAsks)
    }

    @Test
    fun aJournalWithoutTheWeekProjectsReadKeepsTheAlsoLine() = runBlocking {
        val timeline = fake().apply {
            weeks["2026-11-16"] = TimelineFixtures.week("2026-11-16", also = listOf(TimelineFixtures.promo))
            weekProjectsError = JournalApiError.NotFound
        }
        val vm = TimelineViewModel(timeline, scope)
        vm.start()
        vm.loadMoreWeekProjects()
        assertEquals(TimelineWeekExtras.Status.UNSUPPORTED, vm.state.value.weekProjectsStatus)
        // Another week starts unsupported, without asking again.
        vm.select("2026-11-09")
        vm.select("2026-11-16")
        assertEquals(TimelineWeekExtras.Status.UNSUPPORTED, vm.state.value.weekProjectsStatus)
        assertEquals(1, timeline.weekProjectsAsks.size)
    }

    @Test
    fun aFailedPageOffersRetry() = runBlocking {
        val timeline = fake().apply {
            weeks["2026-11-16"] = TimelineFixtures.week("2026-11-16", also = listOf(TimelineFixtures.promo))
            weekProjectsError = JournalApiError.Transport("offline")
        }
        val vm = TimelineViewModel(timeline, scope)
        vm.start()
        vm.loadMoreWeekProjects()
        assertEquals(TimelineWeekExtras.Status.FAILED, vm.state.value.weekProjectsStatus)
        timeline.weekProjectsError = null
        timeline.weekProjectsPages[0] = TimelineWeekProjectsPage("2026-11-16", 1, 0, null, listOf(TimelineWeekProject(TimelineFixtures.promo)))
        vm.loadMoreWeekProjects()
        assertEquals(TimelineWeekExtras.Status.DONE, vm.state.value.weekProjectsStatus)
    }

    @Test
    fun picturesToLoadSkipVideosAndRepeats() = runBlocking {
        val picture = chat.matron.android.models.TimelineMedia("bl_1", "image/png", "home.png")
        val video = chat.matron.android.models.TimelineMedia("bl_2", "video/mp4", "tour.mp4")
        val timeline = fake().apply {
            weeks["2026-11-16"] = TimelineFixtures.week("2026-11-16", stories = listOf(
                TimelineStory.of(TimelineFixtures.docs, media = picture), TimelineStory.of(TimelineFixtures.promo, media = video),
                TimelineStory.of(TimelineFixtures.app, media = picture),
            ))
        }
        val vm = TimelineViewModel(timeline, scope)
        vm.start()
        assertEquals(listOf("bl_1"), vm.state.value.imageBlobIDs)
    }
}
