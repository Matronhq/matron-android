package chat.matron.android.features.memories

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.MemoriesListModel
import chat.matron.android.designsystem.MemoriesListView
import chat.matron.android.viewmodels.MemoriesListViewModel
import kotlinx.coroutines.launch

/// The Memories list (spec 2026-09-27 memories, "Apps"): the user's standing
/// rules and facts, reached from the Missions tab's toolbar. Starts its
/// view model when shown — the first fetch happens here, so a journal
/// without `/memories` reports "not available" on this screen only.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesScreen(
    viewModel: MemoriesListViewModel,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val isSupported by viewModel.isSupported.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memories") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (isSupported != false) {
                        IconButton(onClick = onNew) { Icon(Icons.Filled.Add, contentDescription = "New memory") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            MatronTimelineBackground()
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Standing rules and facts every agent can read. The Coordinator starts each session with this list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                error?.let { message ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.dismissError() }) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
                    }
                }
                MemoriesListView(
                    model = MemoriesListModel(memories = memories, isSupported = isSupported, isRefreshing = isRefreshing),
                    onSelect = onSelect,
                    onRefresh = { coroutineScope.launch { viewModel.refresh() } },
                )
            }
        }
    }
}
