package chat.matron.android.features.decisions

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.ForYouCopy
import chat.matron.android.designsystem.DecisionsListModel
import chat.matron.android.designsystem.DecisionsListView
import chat.matron.android.designsystem.DecisionsRow
import chat.matron.android.designsystem.decisionsRowMatches
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.features.gotonumber.GoToNumberButton
import chat.matron.android.models.ForYouTab
import chat.matron.android.viewmodels.ItemsPanelViewModel
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// The For you tab's root (app shell, spec §3; once "Decisions"): every open item awaiting
/// the user across every conversation, and on its Done tab every closed
/// question, decision and notice, fed by the shell's app-wide
/// `ItemsPanelViewModel(convoID = null)`. The SHELL starts and stops that
/// view model (its badge must be live app-wide, not only while this tab
/// shows); this screen only reads it. Refresh failures surface through the
/// VM's `error`, the same inline row the tasks page uses (spec §7).
///
/// [originLabels] is re-read whenever the set of origin conversations
/// changes (a conversation may have been titled since). [rootGesture] is
/// the shell's tab-swipe modifier, applied to the content only — never to
/// the top bar.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionsScreen(
    viewModel: ItemsPanelViewModel,
    originLabels: suspend () -> Map<String, String>,
    onSelect: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    /// The toolbar's "Go to number" entry; the shell owns the dialog.
    onGoToNumber: () -> Unit = {},
    rootGesture: Modifier = Modifier,
) {
    val awaiting by viewModel.awaitingYou.collectAsStateWithLifecycle()
    val isSupported by viewModel.isSupported.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // The search field's text. It survives a tab switch, like the web's.
    var query by rememberSaveable { mutableStateOf("") }

    val tab by viewModel.forYouTab.collectAsStateWithLifecycle()
    val decided by viewModel.decided.collectAsStateWithLifecycle()
    val decidedVisibleCount by viewModel.decidedVisibleCount.collectAsStateWithLifecycle()
    // Only while the Done tab shows, so the open list never pays for it.
    // While searching, the Done tab filters everything it knows rather than
    // the visible window, so a match further down is not hidden.
    val searching = query.isNotBlank()
    val closed = remember(tab, decided, decidedVisibleCount, searching) {
        when {
            tab != ForYouTab.CLOSED -> emptyList()
            searching -> decided
            else -> decided.take(decidedVisibleCount)
        }
    }
    // The Done captions' clock, ticking only while that tab shows.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(tab) {
        while (tab == ForYouTab.CLOSED) {
            now = Instant.now()
            delay(60_000)
        }
    }

    val origins = remember(awaiting, closed) { (awaiting + closed).map { it.originConvoID }.distinct() }
    LaunchedEffect(origins) {
        if (origins.isNotEmpty()) titles = runCatching { originLabels() }.getOrDefault(emptyMap())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ForYouCopy.TITLE) },
                actions = { GoToNumberButton(onClick = onGoToNumber) },
            )
        },
    ) { padding ->
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
                val openRows = awaiting.map { DecisionsRow(it, titles[it.originConvoID]) }
                    .filter { decisionsRowMatches(it, query) }
                val closedRows = closed.map { DecisionsRow(it, titles[it.originConvoID]) }
                    .filter { decisionsRowMatches(it, query) }
                DecisionsListView(
                    model = DecisionsListModel(
                        rows = openRows,
                        isSupported = isSupported,
                        isRefreshing = isRefreshing,
                        tab = tab,
                        closed = closedRows,
                        closedTotalCount = if (searching) closedRows.size else decided.size,
                        query = query,
                    ),
                    onQueryChange = { query = it },
                    onSelect = onSelect,
                    onOpenConversation = onOpenConversation,
                    onRefresh = { coroutineScope.launch { viewModel.refresh() } },
                    onMarkSeen = { item -> coroutineScope.launch { viewModel.markSeen(item) } },
                    onSelectTab = viewModel::selectForYouTab,
                    onShowMoreClosed = viewModel::showMoreDecided,
                    now = now,
                )
            }
        }
    }
}
