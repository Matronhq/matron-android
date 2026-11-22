package chat.matron.android.features.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.AllProjectsListView
import chat.matron.android.designsystem.FinishedListView
import chat.matron.android.designsystem.StalledListView
import chat.matron.android.designsystem.TimelineFormat
import chat.matron.android.designsystem.TimelineListCopy
import chat.matron.android.models.TimelinePlace
import chat.matron.android.viewmodels.TimelineListContent
import chat.matron.android.viewmodels.TimelineViewModel
import chat.matron.android.viewmodels.listContent
import java.time.Instant
import kotlinx.coroutines.launch

/// One of the lists below the weeks, pushed from the week page: every
/// project, went quiet, open a long time, or finished. Reads the list from
/// the shell's timeline view model unless it was read since the last
/// refresh; pull to refresh reads it again. A row opens its project.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineListScreen(
    viewModel: TimelineViewModel,
    place: TimelinePlace,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel, place) { viewModel.loadList(place) }
    val now = Instant.now()

    ProjectScaffold(title = TimelineListCopy.title(place), onBack = onBack) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    try { viewModel.refresh(); viewModel.loadList(place) } finally { refreshing = false }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val content = state.listContent(place)) {
                is TimelineListContent.Projects -> AllProjectsListView(
                    content.list, now, onOpenProject,
                    onLoadMore = if (content.list.nextBefore != null) ({ viewModel.loadMoreInBackground(place) }) else null,
                    pagingFailed = place in state.pagingFailed,
                )
                is TimelineListContent.Stalled -> StalledListView(
                    lede = if (place == TimelinePlace.Quiet) TimelineListCopy.QUIET_LEDE else TimelineListCopy.LONG_LEDE,
                    list = content.list, now = now, asked = state.askedProjectIDs, onOpenProject = onOpenProject,
                    onAsk = if (state.canAsk) ({ viewModel.askInBackground(it) }) else null,
                )
                is TimelineListContent.Finished -> FinishedListView(
                    content.rows, now, onOpenProject,
                    onLoadMore = if (state.hasMoreFinished) ({ viewModel.loadMoreInBackground(place) }) else null,
                    pagingFailed = place in state.pagingFailed,
                )
                TimelineListContent.Failed -> ProjectPlaceholder(TimelineFormat.FAILED, TimelineFormat.FAILED_DETAIL) {
                    Button(onClick = { scope.launch { viewModel.loadList(place) } }) { Text(TimelineFormat.TRY_AGAIN) }
                }
                TimelineListContent.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
    // A failed Ask, or a failed later page, over a list already on screen.
    val error = state.error
    if (error != null && listShowsError(state.listContent(place))) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            confirmButton = { TextButton(onClick = { viewModel.dismissError() }) { Text("OK") } },
            title = { Text(TimelineListCopy.title(place)) },
            text = { Text(error) },
        )
    }
}

/// Whether a list screen says an error in an alert: only over a list on
/// screen (before that, the screen itself offers Try again).
fun listShowsError(content: TimelineListContent): Boolean =
    content !is TimelineListContent.Failed && content !is TimelineListContent.Loading
