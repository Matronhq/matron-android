package chat.matron.android.features.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.matron.android.chat.MediaService
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.TimelineFormat
import chat.matron.android.designsystem.TimelineWeekLayout
import chat.matron.android.designsystem.TimelineWeekView
import chat.matron.android.models.TimelineWeekSummary
import chat.matron.android.viewmodels.KeyValueStore
import chat.matron.android.viewmodels.TimelineState
import chat.matron.android.viewmodels.TimelineViewModel
import chat.matron.android.viewmodels.TimelineWeekContent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

/// One entry of the week menu: "This week", "Last week", then their dates.
data class WeekChoice(val week: String, val title: String)

fun weekChoices(weeks: List<TimelineWeekSummary>): List<WeekChoice> =
    weeks.mapIndexed { index, summary -> WeekChoice(summary.week, TimelineFormat.menuTitle(index, summary.week)) }

/// The bar's title: the picked week's menu name, else "Projects".
fun projectsBarTitle(state: TimelineState): String =
    weekChoices(state.weeks).firstOrNull { it.week == state.selectedWeek }?.title ?: TimelineFormat.PROJECTS

/// The pictures of a timeline page by blob id, loaded once each through the
/// session's authenticated media service as bytes Coil decodes. Only the
/// current page's stay. A failed load is left out, so the next pass retries.
@Composable
fun rememberTimelineImages(blobIDs: List<String>, media: MediaService, serverURL: HttpUrl): SnapshotStateMap<String, Any> {
    val images = remember { mutableStateMapOf<String, Any>() }
    LaunchedEffect(blobIDs) {
        val wanted = blobIDs.toSet()
        images.keys.filter { it !in wanted }.forEach { images.remove(it) }
        for (blobID in blobIDs) {
            if (images.containsKey(blobID)) continue
            launch {
                val url = serverURL.newBuilder().addPathSegment("media").addPathSegment(blobID).build().toString()
                val bytes = try {
                    media.image(url)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Throwable) {
                    null
                }
                if (bytes != null) images[blobID] = bytes
            }
        }
    }
    return images
}

/// The Projects tab's root: the weekly timeline's week page, the week
/// picked from the title's menu. The old missions list stays one tap away
/// in the toolbar. A journal too old for the timeline (404 on the weeks)
/// keeps the missions list here instead ([legacy]). A card opens its
/// project through [onOpenProject] when the host has a project page.
/// [rootGesture] is the shell's tab-swipe modifier, applied to the content
/// only. Ported from matron-apple's `ProjectsTabRoot` and `TimelinePhoneRoot`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    viewModel: TimelineViewModel,
    media: MediaService,
    serverURL: HttpUrl,
    layoutStore: KeyValueStore,
    onOpenMissions: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenProject: ((String) -> Unit)? = null,
    rootGesture: Modifier = Modifier,
    legacy: @Composable () -> Unit,
) {
    LaunchedEffect(viewModel) { viewModel.shown() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.isSupported == false) {
        legacy()
        return
    }
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf(TimelineWeekLayout.fromWire(layoutStore.getString(TimelineWeekLayout.STORAGE_KEY))) }
    val images = rememberTimelineImages(state.imageBlobIDs, media, serverURL)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { WeekMenu(state, onPick = { viewModel.selectInBackground(it) }, onEarlier = { scope.launch { viewModel.loadEarlierWeeks() } }) },
                actions = {
                    IconButton(onClick = onOpenMissions, modifier = Modifier.testTag("projects.missions")) {
                        Icon(Icons.Outlined.SportsScore, contentDescription = "Missions")
                    }
                    IconButton(onClick = onOpenMemories) { Icon(Icons.Outlined.Lightbulb, contentDescription = "Memories") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).then(rootGesture)) {
            MatronTimelineBackground()
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { scope.launch { viewModel.refresh() } },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val content = state.content) {
                    is TimelineWeekContent.Week -> TimelineWeekView(
                        week = content.week,
                        extras = state.weekExtras,
                        now = Instant.now(),
                        images = images,
                        layout = layout,
                        onLayoutChange = { layout = it; layoutStore.setString(TimelineWeekLayout.STORAGE_KEY, it.wire) },
                        onOpenProject = onOpenProject,
                        onLoadMore = { viewModel.loadMoreWeekProjectsInBackground() },
                    )
                    TimelineWeekContent.NothingYet -> Placeholder(TimelineFormat.NOTHING_YET, TimelineFormat.NOTHING_YET_DETAIL)
                    TimelineWeekContent.Failed -> Placeholder(TimelineFormat.FAILED, TimelineFormat.FAILED_DETAIL) {
                        Button(onClick = { scope.launch { viewModel.refresh() } }) { Text(TimelineFormat.TRY_AGAIN) }
                    }
                    TimelineWeekContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
    // A failed read over a page already on screen says so once.
    val error = state.error
    if (error != null && state.content is TimelineWeekContent.Week) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            confirmButton = { TextButton(onClick = { viewModel.dismissError() }) { Text("OK") } },
            title = { Text(TimelineFormat.PROJECTS) },
            text = { Text(error) },
        )
    }
}

/// The title as a menu: the weeks newest first, the picked one checked,
/// then "Earlier weeks" while the journal has more.
@Composable
private fun WeekMenu(state: TimelineState, onPick: (String) -> Unit, onEarlier: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choices = weekChoices(state.weeks)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.testTag("projects.weekMenu"),
        ) {
            TextButton(onClick = { open = true }, enabled = choices.isNotEmpty()) {
                Text(projectsBarTitle(state), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                if (choices.isNotEmpty()) Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick a week", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.title) },
                    leadingIcon = { if (choice.week == state.selectedWeek) Icon(Icons.Filled.Check, contentDescription = "Selected") else Box(Modifier.size(24.dp)) },
                    onClick = { open = false; onPick(choice.week) },
                )
            }
            if (state.hasEarlierWeeks) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text(TimelineFormat.EARLIER_WEEKS) }, onClick = { onEarlier() })
            }
        }
    }
}

/// The empty and failed states, in a scrollable column so the pull to
/// refresh around them still answers a pull.
@Composable
private fun Placeholder(title: String, detail: String, action: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Layers, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            action()
        }
    }
}
