package chat.matron.android.features.missions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.MissionsListModel
import chat.matron.android.designsystem.MissionsListView
import chat.matron.android.viewmodels.MissionsListViewModel
import kotlinx.coroutines.launch

/// The Missions tab's root (apple #209): every mission, open first, closed
/// collapsed, fed by the shell's `MissionsListViewModel`. The SHELL starts
/// and stops that view model (its badge and support gate are read while
/// another tab shows); this screen only reads it. Refresh failures surface
/// as the same inline row the tasks page uses. [rootGesture] is the shell's
/// tab-swipe modifier, applied to the content only — never to the top bar.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionsScreen(
    viewModel: MissionsListViewModel,
    onSelect: (String) -> Unit,
    rootGesture: Modifier = Modifier,
) {
    val open by viewModel.open.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    val isSupported by viewModel.isSupported.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(title = { Text("Missions") }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).then(rootGesture)) {
            MatronTimelineBackground()
            Column(Modifier.fillMaxSize()) {
                error?.let { message ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.dismissError() }) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
                    }
                }
                MissionsListView(
                    model = MissionsListModel(open = open, closed = closed, isSupported = isSupported, isRefreshing = isRefreshing),
                    onSelect = onSelect,
                    onRefresh = { coroutineScope.launch { viewModel.refresh() } },
                )
            }
        }
    }
}
