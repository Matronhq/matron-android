package chat.matron.android.features.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.ItemGlyph
import chat.matron.android.designsystem.ItemsListModel
import chat.matron.android.designsystem.ItemsListView
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.PendingItemRowModel
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.TrackerItem
import chat.matron.android.viewmodels.ItemsPanelViewModel
import kotlinx.coroutines.launch

/// A conversation's tasks page: the items panel as its own navigation
/// destination (how iOS ended up after apple #194, not the deleted
/// right-edge drawer), reached from the chat top bar's tracker button. Item
/// taps hand off to [onSelect] so the detail opens as its own route and the
/// system back returns here.
///
/// [originLabels] is read when the scope is All (the "All" rows caption
/// their origin conversation); [viewModel] is the per-room panel VM shared
/// with the chat screen's badge, so its observation uses the generation
/// guard rather than a bare stop.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    viewModel: ItemsPanelViewModel,
    originLabels: suspend () -> Map<String, String>,
    onBack: () -> Unit,
    onSelect: (TrackerItem) -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val scope by viewModel.itemsScope.collectAsStateWithLifecycle()
    val isSupported by viewModel.isSupported.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val pending by viewModel.pendingCreates.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    DisposableEffect(viewModel) {
        viewModel.start()
        val generation = viewModel.observationGeneration
        onDispose { viewModel.stop(generation) }
    }
    // Origin labels only matter in the All scope; re-read on each switch and
    // when the sections change (a conversation may have been titled since).
    LaunchedEffect(scope, sections) {
        if (scope is ItemsScope.All) titles = runCatching { originLabels() }.getOrDefault(emptyMap())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
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
                ItemsListView(
                    model = ItemsListModel(
                        needsYou = sections.needsYou, tasks = sections.tasks, decisions = sections.decisions, done = sections.done,
                        originTitles = titles, isSupported = isSupported, isRefreshing = isRefreshing,
                        pending = pending.map { PendingItemRowModel(it.id, it.kind, it.title, isFailed = it.lastError != null, error = it.lastError) },
                    ),
                    scope = scope,
                    convoID = viewModel.convoID,
                    onScopeChange = { viewModel.setScope(it) },
                    onSelect = onSelect,
                    onMove = { id, index -> coroutineScope.launch { viewModel.move(id, index) } },
                    onCreate = { showCreate = true },
                    onOpenConversation = onOpenConversation,
                )
            }
        }
    }

    if (showCreate) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showCreate = false }, sheetState = sheetState) {
            NewItemSheet(
                onCreate = { kind, title, body ->
                    showCreate = false
                    coroutineScope.launch { viewModel.create(kind, title, body) }
                },
                onCancel = { showCreate = false },
            )
        }
    }
}

/// The "+" sheet: kind picker, title, body. Files the item through the
/// panel VM's outbox path, so it lands as a Pending row until the server
/// confirms it.
@Composable
internal fun NewItemSheet(onCreate: (ItemKind, String, String) -> Unit, onCancel: () -> Unit) {
    var kind by remember { mutableStateOf(ItemKind.TASK) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("New item", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ItemKind.entries.forEach { k ->
                FilterChip(
                    selected = kind == k,
                    onClick = { kind = k },
                    label = { Text(ItemGlyph.label(k)) },
                    leadingIcon = { Icon(ItemGlyph.icon(k), contentDescription = null) },
                )
            }
        }
        OutlinedTextField(
            value = title, onValueChange = { title = it.take(200) }, label = { Text("Title") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = body, onValueChange = { body = it }, label = { Text("Details (Markdown)") },
            minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onCreate(kind, title, body) }, enabled = title.isNotBlank()) { Text("File") }
        }
    }
}
