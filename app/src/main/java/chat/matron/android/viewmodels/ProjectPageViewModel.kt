package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.ProjectCloseError
import chat.matron.android.journal.ProjectsProviding
import chat.matron.android.journal.TimelineProviding
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionState
import chat.matron.android.models.Project
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
    /// The project the one asked for was merged into, once the journal says so.
    val redirectedTo: String? = null,
    /// The project's weeks newest first, as far as they have been paged.
    val history: List<TimelineHistoryWeek> = emptyList(),
    val historyLoaded: Boolean = false,
    val hasMoreHistory: Boolean = false,
    /// The project's missions, open ones by latest activity, finished ones
    /// by when they closed; null until read.
    val openMissions: List<Mission>? = null,
    val closedMissions: List<Mission> = emptyList(),
    /// The project's files and pictures, newest first, as far as they have
    /// been paged; null until read.
    val files: ProjectFilesPage? = null,
    /// Bumped each time the files' first page lands: the grid's end asks
    /// for the next page again even when the new list is as long as the old.
    val filesVersion: Int = 0,
    /// The last next-page read of the files failed: the grid's end offers Retry.
    val filesPagingFailed: Boolean = false,
    /// What the "⋯" menu offers: open missions in no project (to add), and
    /// the other open projects (to merge into); [actionsLoaded] once read.
    val unfiled: List<Mission> = emptyList(),
    val mergeTargets: List<Project> = emptyList(),
    val actionsLoaded: Boolean = false,
    /// "Ask the Coordinator about this" was sent from this page.
    val asked: Boolean = false,
    /// A write is in flight: the menu's writes wait for it.
    val isBusy: Boolean = false,
    val error: String? = null,
) {
    /// Which of the "⋯" entries can be chosen.
    val actions: ProjectActionsAvailability
        get() = ProjectActionsAvailability.of(
            isOpen = page?.isOpen == true && !isBusy, isLoaded = actionsLoaded,
            hasUnfiled = unfiled.isNotEmpty(), hasMergeTargets = mergeTargets.isNotEmpty(),
        )
}

/// Which of a project page's "⋯" entries can be chosen. The writes need an
/// open project and its lists loaded; a list with nothing in it is a dimmed
/// entry, never a menu that opens empty. Ported from matron-apple's
/// `ProjectActionsAvailability`.
data class ProjectActionsAvailability(val addMission: Boolean, val rename: Boolean, val merge: Boolean, val close: Boolean) {
    companion object {
        fun of(isOpen: Boolean, isLoaded: Boolean, hasUnfiled: Boolean, hasMergeTargets: Boolean): ProjectActionsAvailability {
            val writable = isOpen && isLoaded
            return ProjectActionsAvailability(
                addMission = writable && hasUnfiled, rename = writable, merge = writable && hasMergeTargets, close = writable,
            )
        }
    }
}

/// The words of a project page's "⋯" menu and what follows from it.
object ProjectActionsFormat {
    const val ADD_MISSION = "Add a mission"
    const val ASK = "Ask the Coordinator about this"
    const val ASKED = "Asked the Coordinator"
    const val RENAME = "Rename…"
    const val MERGE_INTO = "Merge into…"
    const val CLOSE = "Close project…"
    /// The summary the journal records with a close made from the app.
    const val CLOSE_SUMMARY = "Closed from the app."
    const val NOT_OPEN = "This project is closed, so it can't be changed."

    fun askMessage(title: String): String = "Tell me about the project “$title”: where it is and what is next."
    fun closeConfirmation(title: String): String = "Close “$title”? Its missions stay as they are."
    fun mergeConfirmation(title: String, into: String): String =
        "Merge “$title” into “$into”? Its missions move there and this project closes."
}

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
    /// Open missions filed in no project, from the local cache.
    private val unfiledMissions: suspend () -> List<Mission> = { emptyList() },
    /// Sends a question to the Coordinator's conversation; null hides the Ask.
    private val askCoordinator: (suspend (String) -> Unit)? = null,
    /// A mission was filed here: refresh its cached row.
    private val missionChanged: suspend (String) -> Unit = {},
    /// The project was changed from this page: the timeline's lists that
    /// name it read again.
    private val projectChanged: () -> Unit = {},
) {
    /// Whether the page offers "Ask the Coordinator about this".
    val canAsk: Boolean get() = askCoordinator != null

    /// The project every read asks for: the one given, then the one it was merged into.
    var projectID: String = projectID
        private set

    private val _state = MutableStateFlow(ProjectPageState())
    val state: StateFlow<ProjectPageState> = _state.asStateFlow()

    private var pageGeneration = 0
    private var historyGeneration = 0
    private var historyNextBefore: String? = null
    private var isLoadingMoreHistory = false
    private var isReloadingHistory = false
    /// The sections the reader opened: a refresh (or Try again) reads them
    /// again even when their first read failed.
    private var historyAsked = false
    private var missionsAsked = false
    private var missionsGeneration = 0
    private var filesAsked = false
    private var actionsAsked = false
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
            _state.update { it.copy(page = loaded, isMissing = false) }
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
        if (actionsAsked) loadActionTargets()
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
            _state.update { it.copy(history = loaded.weeks, historyLoaded = true, hasMoreHistory = loaded.nextBefore != null) }
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
        if (isLoadingMoreHistory || isReloadingHistory) return
        isLoadingMoreHistory = true
        val generation = historyGeneration
        try {
            val loaded = timeline.timelineProjectHistory(projectID, before = before)
            if (generation != historyGeneration) return
            historyNextBefore = loaded.nextBefore
            _state.update { s ->
                s.copy(history = s.history + loaded.weeks.filter { w -> s.history.none { it.week == w.week } }, hasMoreHistory = loaded.nextBefore != null)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation == historyGeneration) _state.update { it.copy(error = error.message) }
        } finally {
            isLoadingMoreHistory = false
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

    /// Reads the first page of the project's files, replacing what was loaded.
    suspend fun loadFiles() {
        filesAsked = true
        val generation = ++filesGeneration
        // A next-page read of the list this replaces is dropped, and paging
        // waits for this first page.
        isReloadingFiles = true
        try {
            val page = projects.projectFiles(projectID)
            if (generation == filesGeneration) _state.update { it.copy(files = page, filesVersion = it.filesVersion + 1, filesPagingFailed = false) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation == filesGeneration) _state.update { it.copy(error = error.message) }
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
            val page = projects.projectFiles(projectID, before = before)
            if (generation == filesGeneration) _state.update { it.copy(files = shown.appending(page)) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (generation == filesGeneration) _state.update { it.copy(error = error.message, filesPagingFailed = true) }
        } finally {
            if (loadingMoreFilesGeneration == generation) loadingMoreFilesGeneration = null
        }
    }

    // MARK: The "⋯" menu

    /// Reads what the menu lists: open missions in no project and the other
    /// open projects.
    suspend fun loadActionTargets() {
        actionsAsked = true
        try {
            val open = projects.listProjects(openOnly = true)
            val unfiled = unfiledMissions()
            _state.update { s ->
                s.copy(
                    mergeTargets = open.filter { it.id != projectID && it.state == MissionState.OPEN }.sortedBy { it.title.lowercase() },
                    unfiled = unfiled.filter { it.state == MissionState.OPEN && it.projectID == null },
                    actionsLoaded = true,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message) }
        }
    }

    /// `PATCH /projects/:id {title}`. False when the journal refused.
    suspend fun rename(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        return write { projects.renameProject(projectID, trimmed) }
    }

    /// Merges this project into [into]: its missions move there and this
    /// project closes, so the page becomes [into]'s.
    suspend fun merge(into: String): Boolean {
        val from = projectID
        val ok = write(reload = false) { projects.mergeProject(from, into) }
        if (ok) {
            projectID = into
            // Nothing of the closed project stays on screen: until the
            // surviving project's page lands, the page shows its progress
            // (or its retry), never the old project under the new id. The
            // Ask was about the old project, and the lists are the new one's.
            _state.update {
                it.copy(redirectedTo = into, page = null, asked = false, actionsLoaded = false, unfiled = emptyList(), mergeTargets = emptyList())
            }
            refresh()
        }
        return ok
    }

    /// `POST /projects/:id/close` with the app's summary.
    suspend fun close(): Boolean = write { projects.closeProject(projectID, ProjectActionsFormat.CLOSE_SUMMARY) }

    /// Files [missionID] in this project.
    suspend fun addMission(missionID: String): Boolean {
        val ok = write {
            projects.setMissionProject(missionID, projectID)
            missionChanged(missionID)
        }
        if (ok) _state.update { s -> s.copy(unfiled = s.unfiled.filter { it.id != missionID }) }
        return ok
    }

    /// Asks the Coordinator where the project is and what is next.
    suspend fun ask() {
        val send = askCoordinator ?: return
        val title = _state.value.page?.project?.title ?: return
        try {
            send(ProjectActionsFormat.askMessage(title))
            _state.update { it.copy(asked = true) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _state.update { it.copy(error = error.message) }
        }
    }

    /// Runs one write: busy while it runs, the journal's refusal said in
    /// words, then the page (and every section opened) read again.
    private suspend fun write(reload: Boolean = true, body: suspend () -> Unit): Boolean {
        if (_state.value.isBusy) return false
        _state.update { it.copy(isBusy = true, error = null) }
        try {
            body()
        } catch (cancel: CancellationException) {
            _state.update { it.copy(isBusy = false) }
            throw cancel
        } catch (error: Throwable) {
            val message = when (error) {
                is ProjectCloseError -> error.message
                JournalApiError.Conflict -> ProjectActionsFormat.NOT_OPEN
                else -> error.message
            }
            _state.update { it.copy(isBusy = false, error = message) }
            return false
        }
        _state.update { it.copy(isBusy = false) }
        projectChanged()
        if (reload) refresh()
        return true
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
