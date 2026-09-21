package chat.matron.android.features.missions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.MissionDetailModel
import chat.matron.android.designsystem.MissionDetailView
import chat.matron.android.models.Milestone
import chat.matron.android.viewmodels.MissionDetailViewModel
import kotlinx.coroutines.launch

/// One mission page (apple #209). Owns the view model's observation for the
/// life of the pushed screen and maps it into [MissionDetailModel]; a
/// refresh or close failure surfaces in a "Missions" alert.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionDetailScreen(
    viewModel: MissionDetailViewModel,
    onBack: () -> Unit,
    /// A milestone's conversation and its anchor seq — the host opens the
    /// transcript there.
    onOpenMilestone: (convoID: String, seq: Long) -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val mission by viewModel.mission.collectAsStateWithLifecycle()
    val milestones by viewModel.milestones.collectAsStateWithLifecycle()
    val sessionTags by viewModel.sessionTags.collectAsStateWithLifecycle()
    val openItems by viewModel.openItems.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val showOnlyUserInput by viewModel.showOnlyUserInput.collectAsStateWithLifecycle()
    val closeSummary by viewModel.closeSummaryDraft.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(mission?.let { "#${it.num}" } ?: "Mission") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            MatronTimelineBackground()
            MissionDetailView(
                model = MissionDetailModel.from(
                    mission = mission, milestones = milestones, sessionTags = sessionTags, openItems = openItems,
                    conversations = conversations, showOnlyUserInput = showOnlyUserInput,
                    closeSummary = closeSummary, isBusy = isBusy,
                ),
                onToggleUserInputOnly = { viewModel.setShowOnlyUserInput(it) },
                onOpenMilestone = { milestone: Milestone -> onOpenMilestone(milestone.convoID, milestone.seq) },
                onOpenItem = onOpenItem,
                onOpenConversation = onOpenConversation,
                onEditCloseSummary = { viewModel.setCloseSummaryDraft(it) },
                onClose = { coroutineScope.launch { viewModel.close() } },
                onRefresh = {
                    coroutineScope.launch {
                        isRefreshing = true
                        try { viewModel.refresh() } finally { isRefreshing = false }
                    }
                },
                isRefreshing = isRefreshing,
            )
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissError() },
            title = { Text("Missions") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { viewModel.dismissError() }) { Text("OK") } },
        )
    }
}
