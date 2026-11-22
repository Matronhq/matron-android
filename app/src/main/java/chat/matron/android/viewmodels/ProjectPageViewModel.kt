package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.ProjectsProviding
import chat.matron.android.journal.TimelineProviding
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionState
import chat.matron.android.models.ProjectFilesPage
import chat.matron.android.models.TimelineHistoryWeek
import chat.matron.android.models.TimelineProjectPage
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/// What one project's page shows.
data class ProjectPageState(
    /// The last page that loaded: a failed read keeps it on screen.
    val page: TimelineProjectPage? = null,
    /// The journal has no such project.
    val isMissing: Boolean = false,
    /// Bumped each time the page lands: its picture is asked for again, so
    /// one that failed gets another try on a refresh.
    val pageLoads: Int = 0,
    /// The project the one asked for was merged into, once the journal says so.
    val redirectedTo: String? = null,
    /// The project's weeks newest first, as far as they have been paged.
    val history: List<TimelineHistoryWeek> = emptyList(),
    val historyLoaded: Boolean = false,
    val hasMoreHistory: Boolean = false,
    /// Bumped each time a page of the history lands: the view's end asks
    /// for the next page again even when the list kept its length (a
    /// reload, or a page of weeks already listed).
    val historyVersion: Int = 0,
    /// The last older-page read of the history failed: its end offers Retry.
    val historyPagingFailed: Boolean = false,
    /// The project's missions, open ones by latest activity, finished ones
    /// by when they closed; null until read.
    val openMissions: List<Mission>? = null,
    val closedMissions: List<Mission> = emptyList(),
    /// The project's files and pictures, newest first, as far as they have
    /// been paged; null until read.
    val files: ProjectFilesPage? = null,
    /// Bumped each time a page of the files lands: the grid's end asks for
    /// the next page again even when the list kept its length.
    val filesVersion: Int = 0,
    /// Bumped by each read of the files' first page (a refresh), not by
    /// older pages: tiles ask again for pictures that failed on a refresh,
    /// and paging never cancels a picture still loading.
    val filesReloads: Int = 0,
    /// The last next-page read of the files failed: the grid's end offers Retry.
    val filesPagingFailed: Boolean = false,
    val error: String? = null,
)

/// Backs one project's page on the Projects tab: the page itself, its
/// week-by-week history a page at a time once the reader opens it, and its
/// missions. A 404 means the journal has no such project. A project merged
/// into another answers with the one it became: [ProjectPageState.redirectedTo]
/// names it and every later read asks for it. A read that was called off
/// sets no error. Ported from matron-apple's `ProjectSummaryViewModel` and
/// the missions half of `ProjectDetailViewModel`.
class ProjectPageViewModel(
    projectID: String,
    private val timeline: TimelineProviding,
    private val projects: ProjectsProviding,
) {
    /// The project every read asks for: the one given, then the one it was merged into.
    var projectID: String = projectID
        private set

    private val _state = MutableStateFlow(ProjectPageState())
    val state: StateFlow<ProjectPageState> = _state.asStateFlow()

    private var pageGeneration = 0
    private var historyGeneration = 0
    private var historyNextBefore: String? = null
    private var loadingMoreHistoryGeneration: Int? = null
    private var isReloadingHistory = false
    /// The sections the reader opened: a refresh (or Try again) reads them
    /// again even when their first read failed.
    private var historyAsked = false
    private var missionsAsked = false
    private var missionsGeneration = 0
    private var filesAsked = false
    private var filesGeneration = 0
    private var isReloadingFiles = false
    /// The files list a next-page read is in flight for: a read of a list
    /// a reload has since replaced does not hold up the new list's paging.
    private var loadingMoreFilesGeneration: Int? = null

    /// Reads the page: on arrival, on coming back, and for "Try again".
    suspend fun refreshPage() {
        _state.update { it.copy(error = null) }
        pageGeneration += 1
        val generation = pageGeneration
        try {
            val loaded = timeline.timelineProject(projectID)
            if (generation != pageGeneration) return
            if (loaded.mergedFromID != null && loaded.project.id != projectID) {
                projectID = loaded.project.id
                _state.update { it.copy(redirectedTo = loaded.project.id) }
            }
            _state.update { it.copy(page = loaded, isMissing = false, pageLoads = it.pageLoads + 1) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation != pageGeneration) return
            // Gone: nothing of the old page stays on screen.
            if (error is JournalApiError.NotFound) _state.update { it.copy(isMissing = true, page = null) }
            else _state.update { it.copy(error = error.message) }
        }
    }

    /// Re-reads the page and whatever sections were opened (pull to refresh,
    /// or after a change to the project).
    suspend fun refresh() {
        refreshPage()
        if (_state.value.isMissing) return
        if (historyAsked) loadHistory()
        if (missionsAsked) loadMissions()
        if (filesAsked) loadFiles()
    }

    /// Reads the history's first page unless it has loaded (or is being read).
    suspend fun loadHistoryIfNeeded() {
        if (_state.value.historyLoaded || isReloadingHistory) return
        loadHistory()
    }

    /// Reads the history's first page, replacing what was loaded.
    suspend fun loadHistory() {
        historyAsked = true
        historyGeneration += 1
        val generation = historyGeneration
        isReloadingHistory = true
        try {
            val loaded = timeline.timelineProjectHistory(projectID)
            if (generation != historyGeneration) return
            historyNextBefore = loaded.nextBefore
            _state.update {
                it.copy(
                    history = loaded.weeks, historyLoaded = true, hasMoreHistory = loaded.nextBefore != null,
                    historyVersion = it.historyVersion + 1, historyPagingFailed = false,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation == historyGeneration) _state.update { it.copy(error = error.message) }
        } finally {
            if (generation == historyGeneration) isReloadingHistory = false
        }
    }

    suspend fun loadMoreHistory() {
        val before = historyNextBefore ?: return
        // A read of a list a reload has since replaced does not hold up the
        // new list's paging.
        if (isReloadingHistory || loadingMoreHistoryGeneration == historyGeneration) return
        val generation = historyGeneration
        loadingMoreHistoryGeneration = generation
        _state.update { it.copy(historyPagingFailed = false) }
        try {
            val loaded = timeline.timelineProjectHistory(projectID, before = before)
            if (generation != historyGeneration) return
            // A cursor that does not move on would read the same page forever.
            val next = loaded.nextBefore?.takeIf { it != before }
            historyNextBefore = next
            _state.update { s ->
                s.copy(
                    history = s.history + loaded.weeks.filter { w -> s.history.none { it.week == w.week } },
                    hasMoreHistory = next != null, historyVersion = s.historyVersion + 1,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // Said by the Retry at the end of the list, not in an alert.
            if (generation == historyGeneration) _state.update { it.copy(historyPagingFailed = true) }
        } finally {
            if (loadingMoreHistoryGeneration == generation) loadingMoreHistoryGeneration = null
        }
    }

    /// Reads the project's missions (`GET /projects/:id`).
    suspend fun loadMissions() {
        missionsAsked = true
        // Only the newest read's answer (or failure) lands.
        val generation = ++missionsGeneration
        try {
            val detail = projects.project(projectID)
            if (generation != missionsGeneration) return
            val (open, closed) = missionSections(detail.missions)
            _state.update { it.copy(openMissions = open, closedMissions = closed) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation == missionsGeneration) _state.update { it.copy(error = error.message) }
        }
    }

    /// Reads the files' first page unless they have loaded (or are being
    /// read): the Files section coming on screen again keeps what was paged.
    suspend fun loadFilesIfNeeded() {
        if (_state.value.files != null || isReloadingFiles) return
        loadFiles()
    }

    /// Reads the first page of the project's files, replacing what was loaded.
    suspend fun loadFiles() {
        filesAsked = true
        val generation = ++filesGeneration
        // A next-page read of the list this replaces is dropped, and paging
        // waits for this first page.
        isReloadingFiles = true
        try {
            val page = projects.projectFiles(projectID)
            if (generation == filesGeneration) _state.update { it.copy(files = page, filesVersion = it.filesVersion + 1, filesReloads = it.filesReloads + 1, filesPagingFailed = false) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // Said in an alert over the files on screen (before any, the
            // screen offers Try again); the grid's end asks again from the
            // list shown, so paging never stalls behind the failed reload.
            if (generation == filesGeneration) _state.update { it.copy(error = error.message, filesVersion = it.filesVersion + 1, filesReloads = it.filesReloads + 1, filesPagingFailed = false) }
        } finally {
            if (generation == filesGeneration) isReloadingFiles = false
        }
    }

    /// Reads the next (older) page of files while there is one. A failure
    /// leaves Retry at the end of the grid.
    suspend fun loadMoreFiles() {
        val shown = _state.value.files ?: return
        val before = shown.nextBefore ?: return
        if (isReloadingFiles || loadingMoreFilesGeneration == filesGeneration) return
        val generation = filesGeneration
        loadingMoreFilesGeneration = generation
        _state.update { it.copy(filesPagingFailed = false) }
        try {
            val loaded = projects.projectFiles(projectID, before = before)
            // A cursor that does not move on would read the same page forever.
            val page = if (loaded.nextBefore == before) loaded.copy(nextBefore = null) else loaded
            if (generation == filesGeneration) _state.update { it.copy(files = shown.appending(page), filesVersion = it.filesVersion + 1) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // Said by the Retry at the grid's end, not in an alert.
            if (generation == filesGeneration) _state.update { it.copy(filesPagingFailed = true) }
        } finally {
            if (loadingMoreFilesGeneration == generation) loadingMoreFilesGeneration = null
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    companion object {
        /// Open missions by latest milestone (never-checkpointed last), then
        /// newest first; finished ones by when they closed, newest first.
        fun missionSections(missions: List<Mission>): Pair<List<Mission>, List<Mission>> {
            val open = missions.filter { it.state == MissionState.OPEN }.sortedWith(
                compareByDescending<Mission> { it.lastMilestoneAt != null }
                    .thenByDescending { it.lastMilestoneAt ?: Instant.MIN }
                    .thenByDescending { it.createdAt },
            )
            val closed = missions.filter { it.state == MissionState.CLOSED }.sortedByDescending { it.closedAt ?: Instant.MIN }
            return open to closed
        }
    }
}
