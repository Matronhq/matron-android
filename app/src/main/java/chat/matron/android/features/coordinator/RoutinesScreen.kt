package chat.matron.android.features.coordinator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.RoutineEditorView
import chat.matron.android.designsystem.RoutinesListModel
import chat.matron.android.designsystem.RoutinesListView
import chat.matron.android.viewmodels.RoutinesViewModel
import kotlinx.coroutines.launch

/// Settings → Coordinator → Routines. The list is [RoutinesListView];
/// editing and creating push [RoutineEditorScreen]. Nothing loads until the
/// screen appears, so an older journal's 404 stays here. Ported from
/// matron-apple's `RoutinesScreen`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    viewModel: RoutinesViewModel,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val isSupported by viewModel.isSupported.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadError by viewModel.loadError.collectAsStateWithLifecycle()
    val busyIDs by viewModel.busyIDs.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Routines") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (isSupported != false) {
                        IconButton(onClick = onNew, modifier = Modifier.testTag("routines.new")) {
                            Icon(Icons.Filled.Add, contentDescription = "New routine")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            RoutinesListView(
                model = RoutinesListModel(
                    routines = routines, isSupported = isSupported != false,
                    isLoading = isLoading, loadError = loadError, busyIDs = busyIDs,
                ),
                onSelect = onSelect,
                onSetEnabled = { id, enabled -> scope.launch { viewModel.setEnabled(enabled, id) } },
                onRun = { id -> scope.launch { viewModel.showActionMessage(viewModel.run(id) ?: "Sent to the Coordinator.") } },
                onRefresh = { scope.launch { viewModel.load() } },
            )
        }
    }

    actionMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissActionMessage() },
            title = { Text("Routines") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { viewModel.dismissActionMessage() }) { Text("OK") } },
        )
    }
}

/// The editor for one routine by id, or a new one when [routineID] is
/// `null`. A routine no longer in the loaded list — deleted elsewhere —
/// says so rather than binding to a stale record.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditorScreen(
    viewModel: RoutinesViewModel,
    routineID: String?,
    onBack: () -> Unit,
) {
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val routine = routineID?.let { id -> routines?.firstOrNull { it.id == id } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(routine?.title ?: if (routineID == null) "New routine" else "Routine", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (routineID != null && routine == null) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Text("Routine not found", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(
                        "It may have been deleted on another device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            } else {
                RoutineEditorView(
                    routine = routine,
                    onSave = { draft ->
                        viewModel.save(routine?.id, draft.name, draft.routineDraft(isNew = routine == null))
                            .also { if (it == null) onBack() }
                    },
                    onDelete = {
                        val id = routine?.id
                        if (id == null) null else viewModel.delete(id).also { if (it == null) onBack() }
                    },
                    onRun = routine?.let { r -> { viewModel.run(r.id) } },
                )
            }
        }
    }
}
