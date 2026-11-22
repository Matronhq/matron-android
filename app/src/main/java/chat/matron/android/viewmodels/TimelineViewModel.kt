package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.TimelineProviding
import chat.matron.android.models.FinishedProject
import chat.matron.android.models.StalledList
import chat.matron.android.models.StalledProject
import chat.matron.android.models.TimelinePlace
import chat.matron.android.models.TimelineProjectList
import chat.matron.android.models.TimelineWeek
import chat.matron.android.models.TimelineWeekExtras
import chat.matron.android.models.TimelineWeekProject
import chat.matron.android.models.TimelineWeekSummary
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/// The relative dates the timeline's lists say. Ported from matron-apple's
/// `TimelineFormat` (lists part).
object TimelineListFormat {
    /// "19 days ago", "yesterday", under a day "3h ago".
    fun daysAgo(at: Instant, now: Instant): String {
        val seconds = now.epochSecond - at.epochSecond
        val days = seconds / 86_400
        return when {
            days < 1 -> when {
                seconds < 60 -> "just now"
                seconds < 3600 -> "${seconds / 60}m ago"
                else -> "${seconds / 3600}h ago"
            }
            days == 1L -> "yesterday"
            else -> "$days days ago"
        }
    }

    /// "Last moved 19 days ago" (a stalled row); null for no move.
    fun lastMoved(at: Instant?, now: Instant): String? = at?.let { "Last moved ${daysAgo(it, now)}" }

    /// "Moved 3 days ago" (an all-projects row); null for no move.
    fun moved(at: Instant?, now: Instant): String? = at?.let { "Moved ${daysAgo(it, now)}" }

    /// The newest seven weeks of a row's weekly moves (oldest first), padded
    /// with quiet weeks at the old end.
    fun chartBars(weekly: List<Int>): List<Int> {
        val recent = weekly.takeLast(7)
        return List(7 - recent.size) { 0 } + recent
    }

    /// "And 3 more." under a list that shows only its top rows.
    fun moreRows(count: Int): String? = if (count > 0) "And $count more." else null
}

/// Everything the Projects tab's week page draws, as one value.
data class TimelineState(
    /// Newest first: the first is this week, the second last week.
    val weeks: List<TimelineWeekSummary> = emptyList(),
    val hasEarlierWeeks: Boolean = false,
    /// The week picked; null until the weeks list first answers, then the newest.
    val selectedWeek: String? = null,
    /// The last week that loaded: a failed load keeps it on screen.
    val week: TimelineWeek? = null,
    /// The shown week's projects past its stories, as far as they have been
    /// paged, most active first, and where the paging stands.
    val weekProjectsKey: String? = null,
    val weekProjects: List<TimelineWeekProject> = emptyList(),
    val weekProjectsStatus: TimelineWeekExtras.Status = TimelineWeekExtras.Status.DONE,
    /// Bumped by each page of the week's projects that lands, so the page's
    /// end asks for the next even when one added no new project.
    val weekProjectsPages: Int = 0,
    /// null before the first weeks read; false once the journal answered
    /// 404 (it has no timeline: the tab keeps the missions list).
    val isSupported: Boolean? = null,
    val isRefreshing: Boolean = false,
    /// Bumped each time a week lands: its pictures are asked for again, so
    /// one that failed to load gets another try on a refresh.
    val weekLoads: Int = 0,
    val error: String? = null,
    /// The went-quiet and open-a-long-time lists; null until read.
    val quiet: StalledList? = null,
    val long: StalledList? = null,
    /// The finished list as far as it has been paged; [hasLoadedFinished]
    /// once its first read has landed (an empty list is then an answer).
    val finished: List<FinishedProject> = emptyList(),
    val hasLoadedFinished: Boolean = false,
    val hasMoreFinished: Boolean = false,
    /// Every project as far as it has been paged; null until the first read.
    val allProjects: TimelineProjectList? = null,
    /// null until the all-projects list answers; false once the journal
    /// answered 404 for it: nothing then offers "All projects".
    val projectsSupported: Boolean? = null,
    /// Projects the Coordinator was asked about in this session: their rows
    /// say so instead of offering the Ask again.
    val askedProjectIDs: Set<String> = emptySet(),
    /// Whether a stalled project's row can ask the Coordinator about it.
    val canAsk: Boolean = false,
    /// The paged lists whose last next-page read failed: their end offers Retry.
    val pagingFailed: Set<TimelinePlace> = emptySet(),
    /// Bumped by each page of a paged list that lands (first or next): the
    /// list's end is keyed on it, so it asks again even when the length held.
    val listPages: Map<TimelinePlace, Int> = emptyMap(),
    /// What went wrong on one of the lists below the weeks (a read, or an
    /// Ask), kept apart from [error] so the week page never says it.
    val listError: String? = null,
) {
    /// Whether "All projects" is offered: until the journal says it has no such list.
    val showsAllProjects: Boolean get() = projectsSupported != false

    /// The loaded week when it is the one picked: a pick of another week
    /// shows progress (or the retry) until that week arrives, never the last
    /// week's cards under the new title.
    val shownWeek: TimelineWeek? get() = week?.takeIf { it.week == selectedWeek }

    /// The shown week's paged projects; null when no week is shown.
    val weekExtras: TimelineWeekExtras?
        get() {
            val shown = shownWeek ?: return null
            if (weekProjectsKey != shown.week) {
                return TimelineWeekExtras(shown.week, status = TimelineWeekExtras.Status.DONE)
            }
            return TimelineWeekExtras(shown.week, weekProjects, weekProjectsStatus, pages = weekProjectsPages)
        }

    /// What the week page draws.
    val content: TimelineWeekContent
        get() {
            shownWeek?.let { return TimelineWeekContent.Week(it) }
            if (isSupported == true && weeks.isEmpty() && error == null) return TimelineWeekContent.NothingYet
            return if (error != null) TimelineWeekContent.Failed else TimelineWeekContent.Loading
        }

    /// The week's pictures to load (videos draw their own panel): its
    /// stories', then those of the projects paged in past them.
    val imageBlobIDs: List<String>
        get() {
            val shown = shownWeek ?: return emptyList()
            val extras = weekExtras?.projects?.mapNotNull { it.media } ?: emptyList()
            return (shown.stories.mapNotNull { it.media } + extras).filter { !it.isVideo }.map { it.blobID }.distinct()
        }
}

/// What one of the lists below the weeks draws.
sealed interface TimelineListContent {
    data class Projects(val list: TimelineProjectList) : TimelineListContent
    data class Stalled(val place: TimelinePlace, val list: StalledList) : TimelineListContent
    data class Finished(val rows: List<FinishedProject>) : TimelineListContent
    data object Failed : TimelineListContent
    data object Loading : TimelineListContent
}

/// [place]'s list as [state] has it.
fun TimelineState.listContent(place: TimelinePlace): TimelineListContent {
    val unloaded = if (listError != null) TimelineListContent.Failed else TimelineListContent.Loading
    return when (place) {
        TimelinePlace.Projects -> allProjects?.let { TimelineListContent.Projects(it) } ?: unloaded
        TimelinePlace.Quiet -> quiet?.let { TimelineListContent.Stalled(place, it) } ?: unloaded
        TimelinePlace.Long -> long?.let { TimelineListContent.Stalled(place, it) } ?: unloaded
        TimelinePlace.Finished -> if (hasLoadedFinished) TimelineListContent.Finished(finished) else unloaded
        is TimelinePlace.Week -> unloaded
    }
}

/// The week page's states.
sealed interface TimelineWeekContent {
    data class Week(val week: TimelineWeek) : TimelineWeekContent
    /// The journal answered, with no weeks in it.
    data object NothingYet : TimelineWeekContent
    data object Failed : TimelineWeekContent
    data object Loading : TimelineWeekContent
}

/// Backs the Projects tab's weekly timeline. Weeks load newest first, the
/// picked week's stories with them, and the week's other projects page in as
/// the page reaches its end. A journal without the timeline routes (404 on
/// the weeks list) leaves [TimelineState.isSupported] false. A read that was
/// called off sets no error: nothing failed. Ported from matron-apple's
/// `TimelineViewModel`; every mutation runs on [scope]'s (main) thread, as
/// the Swift original is main-actor bound, and the generation counters drop
/// answers a newer read has overtaken.
class TimelineViewModel(
    private val timeline: TimelineProviding,
    private val scope: CoroutineScope,
    /// Sends a question to the Coordinator's conversation; null when there
    /// is no Coordinator to ask.
    private val askCoordinator: (suspend (String) -> Unit)? = null,
    private val now: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow(TimelineState(canAsk = askCoordinator != null))
    val state: StateFlow<TimelineState> = _state.asStateFlow()

    private var weeksNextBefore: String? = null
    private var weeksGeneration = 0
    private var weekGeneration = 0
    private var weekProjectsGeneration = 0
    private var weekProjectsNextOffset: Int? = null
    private var isLoadingEarlier = false
    private var isReloadingWeeks = false
    /// Once the journal has 404'd the week-projects read, nothing asks again.
    private var weekProjectsSupported: Boolean? = null
    private var started = false
    /// Lists read since the last refresh: a visit shows them without reading again.
    private val fresh = mutableSetOf<TimelinePlace>()
    private var refreshEpoch = 0
    private var finishedNextBefore: String? = null
    private var finishedGeneration = 0
    private var projectsGeneration = 0
    /// The list generation a next-page read is in flight for: a read of a
    /// list a reload has since replaced does not hold up the new one's paging.
    private var loadingFinishedGeneration: Int? = null
    private var loadingProjectsGeneration: Int? = null
    private val askingProjectIDs = mutableSetOf<String>()
    /// Bumped when a list screen goes away: a read or an Ask it started
    /// never raises an error after it.
    private var listVisit = 0
    private var refreshesRunning = 0
    private var showJob: Job? = null

    /// The tab came on screen: the first time loads the weeks and the newest
    /// week; later visits re-read them, unless the journal has no timeline.
    fun shown() {
        if (started && _state.value.isSupported == false) return
        val first = !started
        started = true
        showJob?.cancel()
        showJob = scope.launch {
            if (first) {
                start()
                if (_state.value.isSupported == true) loadCounts()
            } else {
                refresh()
            }
        }
    }

    /// Loads the weeks list and the picked week (the newest on the first call).
    suspend fun start() {
        _state.update { it.copy(error = null) }
        if (!loadWeeks()) return
        if (_state.value.selectedWeek == null) {
            _state.value.weeks.firstOrNull()?.let { first -> _state.update { it.copy(selectedWeek = first.week) } }
        }
        loadWeek()
    }

    /// Pull to refresh: re-reads the weeks and the week on screen; the lists
    /// read again on their next visit.
    suspend fun refresh() {
        refreshEpoch += 1
        fresh.clear()
        // A next page still on its way belongs to the lists this replaces.
        projectsGeneration += 1
        finishedGeneration += 1
        // A refresh overtakes what a list was still waiting on.
        listVisit += 1
        // Refreshes can overlap (a pull while the tab's own re-read runs):
        // the indicator stays until the last of them ends.
        refreshesRunning += 1
        _state.update { it.copy(isRefreshing = true) }
        try {
            start()
            // The counts on the week page's rows are read again too.
            if (_state.value.isSupported == true) loadCounts()
        } finally {
            refreshesRunning -= 1
            if (refreshesRunning == 0) _state.update { it.copy(isRefreshing = false) }
        }
    }

    /// Picks [week] from the menu and reads it.
    suspend fun select(week: String) {
        _state.update { it.copy(selectedWeek = week, error = null) }
        loadWeek()
    }

    fun selectInBackground(week: String) {
        scope.launch { select(week) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    suspend fun loadEarlierWeeks() {
        val before = weeksNextBefore ?: return
        if (isLoadingEarlier || isReloadingWeeks) return
        isLoadingEarlier = true
        val generation = weeksGeneration
        try {
            val page = timeline.timelineWeeks(before = before)
            if (generation != weeksGeneration) return
            weeksNextBefore = page.nextBefore
            _state.update { s -> s.copy(weeks = s.weeks + page.weeks.filter { w -> s.weeks.none { it.week == w.week } }, hasEarlierWeeks = page.nextBefore != null) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation == weeksGeneration) _state.update { it.copy(error = error.message) }
        } finally {
            isLoadingEarlier = false
        }
    }

    /// Reads the next page of the shown week's projects past its stories:
    /// when the page reaches its end, and for Retry. Asks for nothing while a
    /// page is being read, after the last, or from a journal without the read.
    suspend fun loadMoreWeekProjects() {
        val s = _state.value
        val key = s.weekProjectsKey ?: return
        val offset = weekProjectsNextOffset ?: return
        if (s.weekProjectsStatus != TimelineWeekExtras.Status.MORE && s.weekProjectsStatus != TimelineWeekExtras.Status.FAILED) return
        val generation = weekProjectsGeneration
        _state.update { it.copy(weekProjectsStatus = TimelineWeekExtras.Status.LOADING) }
        try {
            val page = timeline.timelineWeekProjects(key, offset, WEEK_PROJECTS_PAGE_SIZE)
            if (generation != weekProjectsGeneration) return
            weekProjectsSupported = true
            val next = page.nextOffset?.takeIf { it > offset }
            weekProjectsNextOffset = next
            _state.update { current ->
                // A project shows once: the week's own cards come first, and
                // a list that shifted between pages repeats none.
                val seen = ((current.week?.stories ?: emptyList()).map { it.project.id } + current.weekProjects.map { it.project.id }).toMutableSet()
                current.copy(
                    weekProjectsPages = current.weekProjectsPages + 1,
                    weekProjects = current.weekProjects + page.projects.filter { seen.add(it.project.id) },
                    // An offset that does not move on would read the same page forever.
                    weekProjectsStatus = if (next != null) TimelineWeekExtras.Status.MORE else TimelineWeekExtras.Status.DONE,
                )
            }
        } catch (cancel: CancellationException) {
            if (generation == weekProjectsGeneration) _state.update { it.copy(weekProjectsStatus = TimelineWeekExtras.Status.FAILED) }
            throw cancel
        } catch (error: Throwable) {
            if (generation != weekProjectsGeneration) return
            if (error is JournalApiError.NotFound && offset == 0) {
                weekProjectsSupported = false
                weekProjectsNextOffset = null
                _state.update { it.copy(weekProjectsStatus = TimelineWeekExtras.Status.UNSUPPORTED) }
            } else {
                // The row offers Retry rather than asking again at once.
                _state.update { it.copy(weekProjectsStatus = TimelineWeekExtras.Status.FAILED) }
            }
        }
    }

    fun loadMoreWeekProjectsInBackground() {
        scope.launch { loadMoreWeekProjects() }
    }

    /// Reads the first page of weeks. False when there is nothing to build
    /// on (unsupported journal, a read called off, or a failed read, which
    /// sets the error).
    private suspend fun loadWeeks(): Boolean {
        weeksGeneration += 1
        val generation = weeksGeneration
        isReloadingWeeks = true
        try {
            val page = timeline.timelineWeeks()
            if (generation != weeksGeneration) return false
            // Weeks known before keep their place after the first page (one
            // that slid off it when a new week began, and earlier weeks paged
            // in), so a week picked from them stays in the menu and the
            // title across a refresh; paging then goes on from the oldest.
            var fresh = page.weeks
            var cursor = page.nextBefore
            var kept = _state.value.weeks.filter { w -> fresh.none { it.week == w.week } }
            // More weeks began than one page holds: read on until the new
            // weeks reach the kept ones, so the menu has no hole in it.
            var reads = 0
            while (kept.isNotEmpty() && cursor != null && !weeksAdjoin(fresh.lastOrNull()?.week, kept.first().week) && reads < MAX_GAP_READS) {
                val next = timeline.timelineWeeks(before = cursor)
                if (generation != weeksGeneration) return false
                fresh = fresh + next.weeks.filter { w -> fresh.none { it.week == w.week } }
                cursor = next.nextBefore
                kept = kept.filter { w -> fresh.none { it.week == w.week } }
                reads += 1
            }
            // A hole that could not be filled: start over from the new weeks.
            if (kept.isNotEmpty() && !weeksAdjoin(fresh.lastOrNull()?.week, kept.first().week)) kept = emptyList()
            if (kept.isEmpty()) weeksNextBefore = cursor
            val weeks = fresh + kept
            _state.update { s ->
                // A pick no longer in the list falls back to the newest week.
                val selected = s.selectedWeek?.takeIf { key -> weeks.any { it.week == key } } ?: weeks.firstOrNull()?.week?.takeIf { s.selectedWeek != null }
                s.copy(weeks = weeks, hasEarlierWeeks = weeksNextBefore != null, isSupported = true, selectedWeek = selected)
            }
            return true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation == weeksGeneration) {
                if (error is JournalApiError.NotFound) {
                    _state.update { it.copy(isSupported = false) }
                } else {
                    _state.update { it.copy(error = error.message) }
                }
            }
            return false
        } finally {
            if (generation == weeksGeneration) isReloadingWeeks = false
        }
    }

    private suspend fun loadWeek() {
        val target = _state.value.selectedWeek ?: return
        weekGeneration += 1
        val generation = weekGeneration
        try {
            val loaded = timeline.timelineWeek(target)
            // A later pick moved on, or a newer read started, while this was in flight.
            if (_state.value.selectedWeek != target || generation != weekGeneration) return
            resetWeekProjects(loaded)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation != weekGeneration || _state.value.selectedWeek != target) return
            _state.update { it.copy(error = error.message) }
        }
    }

    /// A week landed: its projects past the stories start over, with nothing
    /// to read when no other project moved or the journal has no such read.
    private fun resetWeekProjects(week: TimelineWeek) {
        weekProjectsGeneration += 1
        val status = when {
            weekProjectsSupported == false -> TimelineWeekExtras.Status.UNSUPPORTED
            week.also.isEmpty() -> TimelineWeekExtras.Status.DONE
            else -> TimelineWeekExtras.Status.MORE
        }
        weekProjectsNextOffset = if (status == TimelineWeekExtras.Status.MORE) 0 else null
        _state.update {
            it.copy(week = week, weekProjectsKey = week.week, weekProjects = emptyList(), weekProjectsStatus = status, weekLoads = it.weekLoads + 1)
        }
    }

    // MARK: The lists below the weeks

    /// Reads the went-quiet and open-a-long-time lists and the first page of
    /// all projects, those not read since the last refresh, so the week
    /// page's rows have their counts before any is visited. A failure stays
    /// quiet: the count is a hint, and the visit retries.
    suspend fun loadCounts() {
        val epoch = refreshEpoch
        if (TimelinePlace.Quiet !in fresh) quietly { timeline.wentQuiet() }?.let { list ->
            if (epoch == refreshEpoch && TimelinePlace.Quiet !in fresh) { _state.update { it.copy(quiet = list) }; fresh += TimelinePlace.Quiet }
        }
        if (TimelinePlace.Long !in fresh) quietly { timeline.openLong() }?.let { list ->
            if (epoch == refreshEpoch && TimelinePlace.Long !in fresh) { _state.update { it.copy(long = list) }; fresh += TimelinePlace.Long }
        }
        if (TimelinePlace.Projects !in fresh && _state.value.projectsSupported != false) {
            // Not a new generation: a visit that starts meanwhile wins, and
            // this read never makes the visit's own read look stale.
            val generation = projectsGeneration
            try {
                val list = timeline.timelineProjects()
                // An answer a refresh overtook says nothing, not even whether
                // the journal has the list.
                if (epoch == refreshEpoch) _state.update { it.copy(projectsSupported = true) }
                if (epoch == refreshEpoch && generation == projectsGeneration) {
                    _state.update { it.copy(allProjects = list, pagingFailed = it.pagingFailed - TimelinePlace.Projects, listPages = it.listPages + (TimelinePlace.Projects to ((it.listPages[TimelinePlace.Projects] ?: 0) + 1))) }
                    fresh += TimelinePlace.Projects
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                if (error is JournalApiError.NotFound && epoch == refreshEpoch) _state.update { it.copy(projectsSupported = false) }
            }
        }
    }

    /// A list came on screen: read it unless it was read since the last refresh.
    suspend fun loadList(place: TimelinePlace) {
        // A visit starts without an earlier visit's error, even when the
        // list is already read.
        _state.update { it.copy(listError = null) }
        if (place is TimelinePlace.Week || place in fresh) return
        val epoch = refreshEpoch
        val visit = listVisit
        try {
            when (place) {
                // An answer a refresh has overtaken is dropped, and the list
                // stays unread so its next visit asks again.
                TimelinePlace.Quiet -> timeline.wentQuiet().let { list -> if (epoch != refreshEpoch) return; _state.update { it.copy(quiet = list) } }
                TimelinePlace.Long -> timeline.openLong().let { list -> if (epoch != refreshEpoch) return; _state.update { it.copy(long = list) } }
                TimelinePlace.Finished -> {
                    val generation = ++finishedGeneration
                    val page = timeline.finishedProjects()
                    if (generation != finishedGeneration || epoch != refreshEpoch) return
                    finishedNextBefore = page.nextBefore
                    _state.update {
                        it.copy(
                            finished = page.rows, hasLoadedFinished = true, hasMoreFinished = page.nextBefore != null,
                            listPages = it.listPages + (TimelinePlace.Finished to ((it.listPages[TimelinePlace.Finished] ?: 0) + 1)),
                            pagingFailed = it.pagingFailed - TimelinePlace.Finished,
                        )
                    }
                }
                TimelinePlace.Projects -> {
                    val generation = ++projectsGeneration
                    val page = try {
                        timeline.timelineProjects()
                    } catch (error: JournalApiError.NotFound) {
                        if (epoch == refreshEpoch) _state.update { it.copy(projectsSupported = false) }
                        throw error
                    }
                    if (generation != projectsGeneration || epoch != refreshEpoch) return
                    _state.update { it.copy(allProjects = page, projectsSupported = true, pagingFailed = it.pagingFailed - TimelinePlace.Projects, listPages = it.listPages + (TimelinePlace.Projects to ((it.listPages[TimelinePlace.Projects] ?: 0) + 1))) }
                }
                is TimelinePlace.Week -> return
            }
            fresh += place
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // Said only on the visit that asked, and only if no refresh
            // overtook it.
            if (epoch == refreshEpoch && visit == listVisit) _state.update { it.copy(listError = error.message) }
        }
    }

    fun loadListInBackground(place: TimelinePlace) {
        scope.launch { loadList(place) }
    }

    suspend fun loadMoreFinished() {
        val before = finishedNextBefore ?: return
        if (loadingFinishedGeneration == finishedGeneration) return
        val generation = finishedGeneration
        loadingFinishedGeneration = generation
        _state.update { it.copy(pagingFailed = it.pagingFailed - TimelinePlace.Finished) }
        try {
            val page = timeline.finishedProjects(before = before)
            if (generation != finishedGeneration) return
            finishedNextBefore = page.nextBefore
            _state.update { s ->
                val have = s.finished.map { it.project.id }.toSet()
                s.copy(
                    finished = s.finished + page.rows.filter { it.project.id !in have }, hasMoreFinished = page.nextBefore != null,
                    listPages = s.listPages + (TimelinePlace.Finished to ((s.listPages[TimelinePlace.Finished] ?: 0) + 1)),
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation == finishedGeneration) _state.update { it.copy(pagingFailed = it.pagingFailed + TimelinePlace.Finished) }
        } finally {
            if (loadingFinishedGeneration == generation) loadingFinishedGeneration = null
        }
    }

    suspend fun loadMoreProjects() {
        val shown = _state.value.allProjects ?: return
        val before = shown.nextBefore ?: return
        if (loadingProjectsGeneration == projectsGeneration) return
        val generation = projectsGeneration
        loadingProjectsGeneration = generation
        _state.update { it.copy(pagingFailed = it.pagingFailed - TimelinePlace.Projects) }
        try {
            val page = timeline.timelineProjects(before = before)
            if (generation != projectsGeneration) return
            val have = shown.rows.map { it.project.id }.toSet()
            _state.update { it.copy(allProjects = TimelineProjectList(page.total, shown.rows + page.rows.filter { r -> r.project.id !in have }, page.nextBefore), listPages = it.listPages + (TimelinePlace.Projects to ((it.listPages[TimelinePlace.Projects] ?: 0) + 1))) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation == projectsGeneration) _state.update { it.copy(pagingFailed = it.pagingFailed + TimelinePlace.Projects) }
        } finally {
            if (loadingProjectsGeneration == generation) loadingProjectsGeneration = null
        }
    }

    fun loadMoreInBackground(place: TimelinePlace) {
        scope.launch {
            when (place) {
                TimelinePlace.Finished -> loadMoreFinished()
                TimelinePlace.Projects -> loadMoreProjects()
                else -> Unit
            }
        }
    }

    /// Hands a stalled project to the Coordinator as a question.
    suspend fun ask(stalled: StalledProject) {
        val send = askCoordinator ?: return
        val id = stalled.project.id
        // One question per project: a second tap while the first is on its
        // way, or after it went, sends nothing.
        if (id in _state.value.askedProjectIDs || !askingProjectIDs.add(id)) return
        val visit = listVisit
        val moved = stalled.lastMovedAt?.let { "It last moved ${TimelineListFormat.daysAgo(it, now())}." } ?: "It hasn't moved yet."
        try {
            send("What should we do with the project “${stalled.project.title}”? $moved")
            _state.update { it.copy(askedProjectIDs = it.askedProjectIDs + id) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (visit == listVisit) _state.update { it.copy(listError = error.message) }
        } finally {
            askingProjectIDs.remove(id)
        }
    }

    fun dismissListError() {
        _state.update { it.copy(listError = null) }
    }

    /// A list screen went away: what it was still waiting on says nothing
    /// on whichever page shows next.
    fun leaveList() {
        listVisit += 1
        dismissListError()
    }

    fun askInBackground(stalled: StalledProject) {
        scope.launch { ask(stalled) }
    }

    private suspend fun <T> quietly(read: suspend () -> T): T? = try {
        read()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Throwable) {
        null
    }

    companion object {
        /// Extra week pages a refresh reads to close a hole before the weeks it kept.
        private const val MAX_GAP_READS = 4

        /// Whether [older] is the week right before [newer] (week keys are
        /// Mondays, `YYYY-MM-DD`). Keys that are not dates count as adjoining.
        fun weeksAdjoin(newer: String?, older: String): Boolean {
            newer ?: return true
            return try {
                java.time.LocalDate.parse(newer).minusDays(7) == java.time.LocalDate.parse(older)
            } catch (_: java.time.format.DateTimeParseException) {
                true
            }
        }

        /// Projects per week-projects read.
        const val WEEK_PROJECTS_PAGE_SIZE = 24
    }
}
