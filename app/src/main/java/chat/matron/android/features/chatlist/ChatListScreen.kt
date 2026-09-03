package chat.matron.android.features.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import chat.matron.android.chat.ChatService
import chat.matron.android.chat.ChatSummary
import chat.matron.android.chat.SessionTag
import chat.matron.android.designsystem.RelativeMinuteTimeView
import chat.matron.android.designsystem.SessionTagText
import chat.matron.android.designsystem.SyncBannerState
import chat.matron.android.designsystem.UnreadBadge
import chat.matron.android.models.MatronDebug
import chat.matron.android.viewmodels.ChatListViewModel
import kotlinx.coroutines.launch

/**
 * Chat-list screen. Ports Features/ChatList/ChatListView.swift: grouped recency
 * sections, per-row long-press mute/leave, pull-to-refresh, an inline connection
 * indicator, and a toolbar (search when the index is available, new chat, and an
 * overflow menu with Settings + Sign Out).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    viewModel: ChatListViewModel,
    connectionState: SyncBannerState,
    hasEverConnected: Boolean,
    searchAvailable: Boolean,
    chat: ChatService,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var overflowOpen by remember { mutableStateOf(false) }

    // iOS shows this list plain on systemBackground (white), not on the
    // timeline cream — the theme's default `background` here reads brown.
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("Chats") },
                navigationIcon = {
                    ConnectionIndicator(connectionState, hasEverConnected)
                },
                actions = {
                    if (searchAvailable) {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Default.Edit, contentDescription = "New chat")
                    }
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { overflowOpen = false; onOpenSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text("Sign Out") },
                            onClick = { overflowOpen = false; onSignOut() },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                isLoading && groups.isEmpty() -> CenteredMessage(
                    // A backlog replay is progress, not a stalled socket —
                    // name what's actually happening (matron-apple #84).
                    if (connectionState is SyncBannerState.CatchingUp) "Loading messages…" else "Connecting…",
                    showSpinner = true,
                )
                error != null && groups.isEmpty() -> CenteredMessage(error!!)
                groups.isEmpty() -> CenteredMessage("No chats yet. Provision a bot via dev-boxer to get started.")
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    groups.forEach { group ->
                        item(key = "header-${group.id}") {
                            Text(
                                group.group.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                        items(group.summaries, key = { it.id }) { summary ->
                            ChatRow(
                                summary = summary,
                                onOpen = { onOpenChat(summary.id) },
                                onMute = {
                                    scope.launch {
                                        runCatching { chat.mute(summary.id) }
                                            .onFailure { MatronDebug.breadcrumb("ChatListScreen: mute failed for ${summary.id}: $it") }
                                    }
                                },
                                onLeave = {
                                    scope.launch {
                                        runCatching { chat.leave(summary.id) }
                                            .onFailure { MatronDebug.breadcrumb("ChatListScreen: leave failed for ${summary.id}: $it") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionIndicator(state: SyncBannerState, hasEverConnected: Boolean) {
    when (state) {
        is SyncBannerState.Running -> Unit
        is SyncBannerState.Connecting, is SyncBannerState.CatchingUp -> Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
            Text(
                when {
                    state is SyncBannerState.CatchingUp -> "Loading messages…"
                    hasEverConnected -> "Reconnecting…"
                    else -> "Connecting…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is SyncBannerState.Offline -> Text(
            "Offline",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    summary: ChatSummary,
    onOpen: () -> Unit,
    onMute: () -> Unit,
    onLeave: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // `A:bc Title` as ONE text so the tag leads the eye scan
                // (colored box letter + session short — the trailing BoxChip
                // capsule this replaces put the machine at the END and cost
                // a capsule of width) and the whole line truncates together,
                // which also retires the capsule's 144dp width cap: there is
                // no unweighted sibling left to starve the title.
                // Tag halves are gated upstream (JournalChatService): no
                // letter for single-box users, no short for titles the
                // bridge never prefixed.
                val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                Text(
                    titleLine(summary, darkTheme, MaterialTheme.colorScheme.onSurfaceVariant),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary.snippet.ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.heightIn(min = 32.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                summary.lastActivity?.let { activity ->
                    RelativeMinuteTimeView(
                        source = activity,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                UnreadBadge(count = summary.unreadCount)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Mute") }, onClick = { menuOpen = false; onMute() })
            DropdownMenuItem(text = { Text("Leave") }, onClick = { menuOpen = false; onLeave() })
        }
    }
}

/// `A:bc Title` (or `A↔B:bc Title` for a multi-agent room) as one styled
/// string — a multi-agent room leads with every participating box as a
/// colored letter (`A↔B`, `A,B,C`); the tag already says "room", so the
/// bridge's 🔗 title marker is dropped beside it. Falls through to the
/// single-box `A:bc` tag, then to the bare title. Ports the iOS ChatRow's
/// `titleLine` composition (apple #152).
private fun titleLine(summary: ChatSummary, darkTheme: Boolean, secondary: Color): AnnotatedString {
    // Same marker discipline as every tagged surface: the room marker drops
    // only when a room tag will actually render in its place.
    val title = if (summary.roomBoxNames.size >= 2) {
        SessionTag.titleBesideRoomTag(summary.title)
    } else {
        summary.title
    }
    return SessionTagText.titleLine(
        title = title,
        boxLetter = summary.boxShort,
        boxName = summary.boxName,
        sessionShort = summary.sessionShort,
        roomBoxNames = summary.roomBoxNames,
        roomBoxShorts = summary.roomBoxShorts,
        darkTheme = darkTheme,
        secondary = secondary,
    )
}

@Composable
private fun CenteredMessage(text: String, showSpinner: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showSpinner) CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.padding(0.dp))
        }
    }
}

/**
 * Looks up the current [ChatSummary] for a navigation id across all groups —
 * the port of `ChatListView.currentSummary(for:)`. Returns `null` when the room
 * is absent from the latest snapshot (left elsewhere, or a just-started convo
 * whose first frame hasn't landed).
 */
fun currentSummary(groups: List<ChatListViewModel.GroupedSummaries>, id: String): ChatSummary? {
    for (group in groups) {
        group.summaries.firstOrNull { it.id == id }?.let { return it }
    }
    return null
}
