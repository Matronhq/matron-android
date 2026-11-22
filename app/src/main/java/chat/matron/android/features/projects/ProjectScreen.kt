package chat.matron.android.features.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.MediaService
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.ProjectFormat
import chat.matron.android.designsystem.ProjectHistoryView
import chat.matron.android.designsystem.ProjectMissionsListView
import chat.matron.android.designsystem.ProjectSection
import chat.matron.android.designsystem.ProjectSummaryView
import chat.matron.android.designsystem.AttachmentFullscreenViewer
import chat.matron.android.designsystem.ImageGallery
import chat.matron.android.designsystem.ProjectFileOpening
import chat.matron.android.designsystem.ProjectFilesGridView
import chat.matron.android.designsystem.projectFileOpening
import chat.matron.android.designsystem.projectPictureBlobIDs
import chat.matron.android.models.ProjectFile
import chat.matron.android.models.ProjectFileSource
import chat.matron.android.designsystem.TimelineFormat
import chat.matron.android.models.ProjectMilestone
import chat.matron.android.viewmodels.ProjectPageState
import chat.matron.android.viewmodels.ProjectPageViewModel
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

/// The sections a project's page lists, top to bottom.
val PROJECT_PAGE_SECTIONS = listOf(ProjectSection.HISTORY, ProjectSection.MISSIONS, ProjectSection.FILES)

/// What a project screen draws.
sealed interface ProjectScreenContent {
    data object Page : ProjectScreenContent
    data object Missing : ProjectScreenContent
    data object Failed : ProjectScreenContent
    data object Loading : ProjectScreenContent

    companion object {
        fun of(state: ProjectPageState): ProjectScreenContent = when {
            state.isMissing -> Missing
            state.page != null -> Page
            state.error != null -> Failed
            else -> Loading
        }
    }
}

/// One project's page on the Projects stack (`ProjectSummaryView`): read on
/// arrival and on every return to it; pull to refresh. The page's three
/// rows push its sections through [onOpenSection]. Ported from
/// matron-apple's `TimelineStoryHost`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    viewModel: ProjectPageViewModel,
    media: MediaService,
    serverURL: HttpUrl,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenSection: (ProjectSection) -> Unit,
    sections: List<ProjectSection> = PROJECT_PAGE_SECTIONS,
    titleActions: @Composable (ProjectPageState) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.refreshPage() }
    val images = rememberTimelineImages(listOfNotNull(state.page?.media?.takeIf { !it.isVideo }?.blobID), media, serverURL)

    ProjectScaffold(title = state.page?.let { "#${it.project.num}" } ?: "Project", onBack = onBack) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; try { viewModel.refresh() } finally { refreshing = false } } },
            modifier = Modifier.fillMaxSize(),
        ) {
            val page = state.page
            when (ProjectScreenContent.of(state)) {
                ProjectScreenContent.Page -> ProjectSummaryView(
                    page = page!!, image = page.media?.let { images[it.blobID] }, sections = sections,
                    onOpenItem = onOpenItem, onOpenSection = onOpenSection, titleActions = { titleActions(state) },
                )
                ProjectScreenContent.Missing -> ProjectPlaceholder(ProjectFormat.MISSING, ProjectFormat.MISSING_DETAIL)
                ProjectScreenContent.Failed -> ProjectPlaceholder(TimelineFormat.FAILED, TimelineFormat.FAILED_DETAIL) {
                    Button(onClick = { scope.launch { viewModel.refreshPage() } }) { Text(TimelineFormat.TRY_AGAIN) }
                }
                ProjectScreenContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
    ProjectErrorAlert(state, viewModel)
}

/// One section of a project's page, pushed one level down: its history week
/// by week, or its missions.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSectionScreen(
    viewModel: ProjectPageViewModel,
    section: ProjectSection,
    media: MediaService,
    serverURL: HttpUrl,
    onBack: () -> Unit,
    onOpenMission: (String) -> Unit,
    onOpenMilestone: (ProjectMilestone) -> Unit,
    /// Opens a file where it was posted: its item, or its chat at that point.
    /// Returns what to tell the user when the file can't be opened there
    /// (an item that is gone, say); null when it opened.
    onOpenFileSource: suspend (ProjectFileSource) -> String? = { null },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel, section) {
        viewModel.refreshPage()
        when (section) {
            ProjectSection.HISTORY -> viewModel.loadHistoryIfNeeded()
            ProjectSection.MISSIONS -> viewModel.loadMissions()
            ProjectSection.FILES -> viewModel.loadFiles()
        }
    }
    val pictures = state.history.mapNotNull { it.media }.filter { !it.isVideo }.map { it.blobID } +
        (state.files?.rows ?: emptyList()).filter { it.isImage }.map { it.blobID }.distinct()
    val images = rememberTimelineImages(pictures, media, serverURL)
    val title = ProjectFormat.sectionTitle(section)
    var gallery by remember { mutableStateOf<ImageGallery?>(null) }
    var sourceMessage by remember { mutableStateOf<String?>(null) }
    var sourceJob by remember { mutableStateOf<Job?>(null) }

    ProjectScaffold(title = title, onBack = onBack) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; try { viewModel.refresh() } finally { refreshing = false } } },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isMissing -> ProjectPlaceholder(ProjectFormat.MISSING, ProjectFormat.MISSING_DETAIL)
                section == ProjectSection.HISTORY && state.historyLoaded -> ProjectHistoryView(
                    weeks = state.history, now = Instant.now(), images = images, hasMore = state.hasMoreHistory,
                    onOpenMilestone = onOpenMilestone, onLoadMore = { scope.launch { viewModel.loadMoreHistory() } },
                    version = state.historyVersion, pagingFailed = state.historyPagingFailed,
                )
                section == ProjectSection.MISSIONS && state.openMissions != null -> ProjectMissionsListView(
                    open = state.openMissions!!, closed = state.closedMissions, now = Instant.now(), onOpenMission = onOpenMission,
                )
                section == ProjectSection.FILES && state.files != null -> ProjectFilesGridView(
                    files = state.files!!.rows, images = images, now = Instant.now(), hasMore = state.files!!.hasMore,
                    onOpenFile = { file ->
                        when (projectFileOpening(file)) {
                            // The latest tap wins: an earlier one still resolving
                            // its item never navigates or explains after it.
                            ProjectFileOpening.PICTURE -> {
                                sourceJob?.cancel()
                                val rows = state.files!!.rows
                                val loaded = images[file.blobID]
                                if (loaded != null) {
                                    gallery = projectGallery(rows, file, images, serverURL, media)
                                } else {
                                    // Its thumbnail hasn't landed: fetch it, then open on it.
                                    sourceJob = scope.launch {
                                        val bytes = try { media.image(projectMediaURL(serverURL, file.blobID)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }
                                        // A later tap won while this one was fetching: it says nothing.
                                        ensureActive()
                                        if (bytes == null) {
                                            sourceMessage = PICTURE_UNAVAILABLE
                                        } else {
                                            gallery = projectGallery(rows, file, images + (file.blobID to bytes), serverURL, media)
                                        }
                                    }
                                }
                            }
                            ProjectFileOpening.SOURCE -> {
                                sourceJob?.cancel()
                                sourceJob = scope.launch { sourceMessage = onOpenFileSource(file.source) }
                            }
                        }
                    },
                    onLoadMore = { scope.launch { viewModel.loadMoreFiles() } },
                    version = state.filesVersion,
                    pagingFailed = state.filesPagingFailed,
                )
                state.error != null -> ProjectPlaceholder(TimelineFormat.FAILED, TimelineFormat.FAILED_DETAIL) {
                    Button(onClick = { scope.launch { viewModel.refresh() } }) { Text(TimelineFormat.TRY_AGAIN) }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
    gallery?.let { AttachmentFullscreenViewer(gallery = it, onDismiss = { gallery = null }) }
    sourceMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { sourceMessage = null },
            confirmButton = { TextButton(onClick = { sourceMessage = null }) { Text("OK") } },
            title = { Text(ProjectFormat.sectionTitle(ProjectSection.FILES)) },
            text = { Text(message) },
        )
    }
    ProjectErrorAlert(state, viewModel, shown = sectionLoaded(state, section))
}

/// Whether [section]'s content is on screen: only then does a failed read
/// say so in an alert over it; before, the screen shows the retry.
fun sectionLoaded(state: ProjectPageState, section: ProjectSection): Boolean = when (section) {
    ProjectSection.HISTORY -> state.historyLoaded
    ProjectSection.MISSIONS -> state.openMissions != null
    ProjectSection.FILES -> state.files != null
}

/// Said when a tapped picture could not be fetched for the viewer.
const val PICTURE_UNAVAILABLE = "Couldn't load this picture. Check the connection and try again."

internal fun projectMediaURL(serverURL: HttpUrl, blobID: String): String =
    serverURL.newBuilder().addPathSegment("media").addPathSegment(blobID).build().toString()

/// The project's pictures in the grid's order, opened on [tapped].
internal fun projectGallery(files: List<ProjectFile>, tapped: ProjectFile, images: Map<String, Any>, serverURL: HttpUrl, media: MediaService): ImageGallery {
    val blobIDs = projectPictureBlobIDs(files)
    val names = files.filter { it.isImage }.associate { it.blobID to it.name }
    fun url(blobID: String) = projectMediaURL(serverURL, blobID)
    val byURL = blobIDs.associateBy(::url)
    return ImageGallery(
        entries = blobIDs.map { ImageGallery.Entry(id = it, url = url(it), expired = false, name = names[it]) },
        startIndex = blobIDs.indexOf(tapped.blobID).coerceAtLeast(0),
        initial = images[tapped.blobID],
        // A neighbour the grid already loaded opens from its bytes.
        load = { u -> byURL[u]?.let { images[it] } ?: media.image(u) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            MatronTimelineBackground()
            content()
        }
    }
}

/// A failed read over a page already on screen says so once.
@Composable
private fun ProjectErrorAlert(state: ProjectPageState, viewModel: ProjectPageViewModel, shown: Boolean = state.page != null) {
    val error = state.error ?: return
    if (!shown) return
    AlertDialog(
        onDismissRequest = { viewModel.dismissError() },
        confirmButton = { TextButton(onClick = { viewModel.dismissError() }) { Text("OK") } },
        title = { Text(TimelineFormat.PROJECTS) },
        text = { Text(error) },
    )
}

@Composable
internal fun ProjectPlaceholder(title: String, detail: String, action: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Layers, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            action()
        }
    }
}
