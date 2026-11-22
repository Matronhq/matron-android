package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.TimelineProviding
import chat.matron.android.models.TimelineWeek
import chat.matron.android.models.TimelineWeekExtras
import chat.matron.android.models.TimelineWeekProject
import chat.matron.android.models.TimelineWeekSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /// null before the first weeks read; false once the journal answered
    /// 404 (it has no timeline: the tab keeps the missions list).
    val isSupported: Boolean? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null,
) {
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
            return TimelineWeekExtras(shown.week, weekProjects, weekProjectsStatus)
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
) {
    private val _state = MutableStateFlow(TimelineState())
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
    private var showJob: Job? = null

    /// The tab came on screen: the first time loads the weeks and the newest
    /// week; later visits re-read them, unless the journal has no timeline.
    fun shown() {
        if (started && _state.value.isSupported == false) return
        val first = !started
        started = true
        showJob?.cancel()
        showJob = scope.launch { if (first) start() else refresh() }
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

    /// Pull to refresh: re-reads the weeks and the week on screen.
    suspend fun refresh() {
        _state.update { it.copy(isRefreshing = true) }
        try {
            start()
        } finally {
            _state.update { it.copy(isRefreshing = false) }
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
            // Earlier weeks paged in before keep their place after the first
            // page, so a week picked from them stays in the menu and the
            // title across a refresh; paging then goes on from the oldest.
            val old = _state.value.weeks
            val kept = old.filter { w -> page.weeks.none { it.week == w.week } && old.indexOf(w) >= page.weeks.size }
            if (kept.isEmpty()) weeksNextBefore = page.nextBefore
            _state.update { it.copy(weeks = page.weeks + kept, hasEarlierWeeks = weeksNextBefore != null, isSupported = true) }
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
            it.copy(week = week, weekProjectsKey = week.week, weekProjects = emptyList(), weekProjectsStatus = status)
        }
    }

    companion object {
        /// Projects per week-projects read.
        const val WEEK_PROJECTS_PAGE_SIZE = 24
    }
}
