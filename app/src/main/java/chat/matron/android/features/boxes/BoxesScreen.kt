package chat.matron.android.features.boxes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.BoxBadge
import chat.matron.android.designsystem.BoxMeterView
import chat.matron.android.designsystem.BoxStateLabel
import chat.matron.android.designsystem.MatronGreen
import chat.matron.android.designsystem.MatronRed
import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.RosterSession
import chat.matron.android.viewmodels.AllowanceSpare
import chat.matron.android.viewmodels.BoxAllowances
import chat.matron.android.viewmodels.BoxCapacity
import chat.matron.android.viewmodels.BoxConnectionState
import chat.matron.android.viewmodels.BoxDisk
import chat.matron.android.viewmodels.BoxRow
import chat.matron.android.viewmodels.BoxesSummary
import chat.matron.android.viewmodels.BoxesViewModel
import chat.matron.android.viewmodels.NewChatViewModel
import kotlinx.coroutines.launch

/// The Boxes screen: a card per paired box with its state, live sessions,
/// usage meters, defaults and disk, plus the login with the most allowance
/// to spare per day. Reached from the Chats overflow menu. Port of
/// matron-apple's `BoxesScreen`.
@Composable
fun BoxesScreen(
    viewModel: BoxesViewModel,
    onStartSession: (DeviceDTO) -> Unit,
    onOpenSessions: (Long) -> Unit,
    onBack: () -> Unit,
    /// Opens Usage history; null hides the row.
    onOpenUsageHistory: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val waking by viewModel.wakingRows.collectAsStateWithLifecycle()
    val failed by viewModel.rowWakeFailed.collectAsStateWithLifecycle()
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(Unit) { viewModel.watch() }

    val rows = viewModel.rows(state, waking, failed)
    BoxesContent(
        rows = rows,
        summary = viewModel.summary(rows),
        hasLoaded = state.hasLoaded,
        errorMessage = state.errorMessage,
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                try {
                    viewModel.load()
                } finally {
                    refreshing = false
                }
            }
        },
        onWake = { device -> scope.launch { viewModel.wake(device) } },
        onStartSession = onStartSession,
        onOpenSessions = onOpenSessions,
        onBack = onBack,
        onOpenUsageHistory = onOpenUsageHistory,
    )
}

/// The screen's body from plain values, so it draws from fixtures.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxesContent(
    rows: List<BoxRow>,
    summary: BoxesSummary,
    hasLoaded: Boolean,
    errorMessage: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onWake: (DeviceDTO) -> Unit,
    onStartSession: (DeviceDTO) -> Unit,
    onOpenSessions: (Long) -> Unit,
    onBack: () -> Unit,
    /// Frozen clock (epoch ms) for screenshots; null = now.
    fixedNowMs: Long? = null,
    /// Opens Usage history; null hides the row.
    onOpenUsageHistory: (() -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Boxes") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (rows.isNotEmpty()) {
                    item(key = "counts") {
                        Text(
                            summary.countsText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                if (errorMessage != null) {
                    item(key = "error") {
                        Text(
                            errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
                summary.spare?.let { spare ->
                    item(key = "spare") { SpareCard(spare, summary, fixedNowMs) }
                }
                if (rows.isNotEmpty() && onOpenUsageHistory != null) {
                    item(key = "history") { UsageHistoryLink(onOpenUsageHistory) }
                }
                items(rows, key = { it.id }) { row ->
                    BoxCard(row, onWake, onStartSession, onOpenSessions, fixedNowMs)
                }
                if (rows.isEmpty()) {
                    item(key = "empty") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
                            if (hasLoaded) {
                                Text(
                                    "No boxes yet. Pair one in Settings → Devices.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BoxesCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) { content() }
}

@Composable
private fun UsageHistoryLink(onOpen: () -> Unit) {
    BoxesCard(Modifier.clickable(onClick = onOpen)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Usage history", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "How much of each week's allowance went unused",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SpareCard(spare: AllowanceSpare, summary: BoxesSummary, fixedNowMs: Long?) {
    val names = BoxesSummary.namesText(summary.spareBoxNames)
    val nowMs = fixedNowMs ?: System.currentTimeMillis()
    BoxesCard {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Most spare per day",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${spare.accountEmail ?: names} · ${spare.percentPerDay}% a day",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MatronGreen,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$names · ${spare.percentLeft}% left, ${BoxCapacity.resetCaption(spare.resetsAt, nowMs).orEmpty()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BoxCard(
    row: BoxRow,
    onWake: (DeviceDTO) -> Unit,
    onStartSession: (DeviceDTO) -> Unit,
    onOpenSessions: (Long) -> Unit,
    fixedNowMs: Long?,
) {
    val lines = row.capacity?.limitLines.orEmpty()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    BoxesCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        row.device.name.ifEmpty { "Unnamed box" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (row.state == BoxConnectionState.Online) MaterialTheme.colorScheme.onSurface else secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.isNearLimit) {
                        BoxBadge("Near limit", MatronRed)
                    } else if (row.isMostSpare) {
                        BoxBadge("Most spare", MatronGreen)
                    }
                }
                TrailingControls(row, onWake, onStartSession, onOpenSessions)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val caption = row.wake?.caption
                if (caption != null) {
                    Text(caption, style = MaterialTheme.typography.bodySmall, color = secondary)
                } else {
                    BoxStateLabel(row.state)
                }
                row.capacity?.accountEmail?.let {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (lines.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lines.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            pair.forEach { line ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(line.label, style = MaterialTheme.typography.labelSmall, color = secondary, maxLines = 1)
                                    BoxMeterView(line, row.freshness, fixedNowMs = fixedNowMs)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            footerText(row)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = secondary, maxLines = 2)
            }
            row.readingAtMs?.let { at ->
                Text(
                    "as of ${BoxAllowances.ageText(at, fixedNowMs ?: System.currentTimeMillis())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/// The default agent and model, then the free disk: "Claude · Opus · 47G free of 145G".
internal fun footerText(row: BoxRow): String? {
    val disk = row.capacity?.disk?.let { "${BoxDisk.sizeText(it.freeBytes)} free of ${BoxDisk.sizeText(it.totalBytes)}" }
    val parts = listOfNotNull(row.defaultsText, disk)
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

@Composable
private fun TrailingControls(
    row: BoxRow,
    onWake: (DeviceDTO) -> Unit,
    onStartSession: (DeviceDTO) -> Unit,
    onOpenSessions: (Long) -> Unit,
) {
    val name = row.device.name
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.liveCount?.let { live ->
            if (row.sessions.isEmpty()) {
                Text("$live live", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "$live live ›",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onOpenSessions(row.id) }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .semantics { contentDescription = "$live sessions on $name" },
                )
            }
        }
        if (row.wake == NewChatViewModel.RowWake.Waking) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp).semantics { contentDescription = "Waking $name" },
                strokeWidth = 2.dp,
            )
        } else if (row.state == BoxConnectionState.Asleep) {
            OutlinedButton(
                onClick = { onWake(row.device) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 32.dp).semantics { contentDescription = "Wake $name" },
            ) { Text("Wake") }
        }
        if (row.canStartSession) {
            OutlinedIconButton(onClick = { onStartSession(row.device) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Start a session on $name", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/// The sessions on one box, pushed from its card's "N live ›".
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxSessionsScreen(
    name: String,
    sessions: List<RosterSession>,
    onOpenChat: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(sessions, key = { it.id }) { session ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenChat(session.id) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(if (session.isRunning) MatronGreen else Color.Gray),
                    )
                    Text(
                        session.title.ifEmpty { "Untitled session" },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (session.isRunning) "Running" else "Waiting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
