package chat.matron.android.features.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.UsageMetersFormat
import chat.matron.android.journal.DeviceDTO
import chat.matron.android.viewmodels.AgentRPCProviding
import chat.matron.android.viewmodels.AgentCapacityFreshness
import chat.matron.android.viewmodels.BoxCapacity
import chat.matron.android.viewmodels.BoxCapacityCaching
import chat.matron.android.viewmodels.NewChatViewModel
import chat.matron.android.viewmodels.lastSeenText
import kotlinx.coroutines.launch

/**
 * The new-chat flow. Ports Features/ChatList/NewChatSheet.swift: pick a connected
 * agent → pick (or type) a folder → the agent starts a session there. On `Done`
 * a placeholder convo row is ensured (via [prepareConversation]) and [onCreated]
 * fires so the caller can dismiss and navigate immediately.
 */
@Composable
fun NewChatSheet(
    api: AgentRPCProviding,
    /// Per-account last-known capacity store, so sleeping boxes still show
    /// their quota (apple #164).
    capacityCache: BoxCapacityCaching,
    prepareConversation: suspend (String) -> Unit,
    onCreated: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { NewChatViewModel(api, capacityCache) }
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val foldersError by viewModel.foldersError.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isStarting by viewModel.isStarting.collectAsStateWithLifecycle()
    val capacities by viewModel.capacities.collectAsStateWithLifecycle()
    val capacityPending by viewModel.capacityPending.collectAsStateWithLifecycle()
    val isWakingBox by viewModel.isWakingBox.collectAsStateWithLifecycle()
    val wakeStartedAt by viewModel.wakeStartedAt.collectAsStateWithLifecycle()
    val wakeGaveUp by viewModel.wakeGaveUp.collectAsStateWithLifecycle()

    var navigated by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    // Anything that removes the sheet counts as abandoning the flow —
    // including the wake loops, which would otherwise keep re-asking a box
    // (and a retried start could silently spawn a session) for two minutes.
    DisposableEffect(Unit) { onDispose { viewModel.abandon() } }
    LaunchedEffect(phase) {
        val done = phase as? NewChatViewModel.Phase.Done ?: return@LaunchedEffect
        if (navigated) return@LaunchedEffect
        navigated = true
        prepareConversation(done.convoID)
        onCreated(done.convoID)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("New Chat", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }

        when (val current = phase) {
            is NewChatViewModel.Phase.LoadingAgents ->
                LoadingRow("Looking for your agents…")

            is NewChatViewModel.Phase.Agents -> {
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Start a chat on", style = MaterialTheme.typography.labelMedium)
                if (current.agents.isEmpty()) {
                    Text(
                        "No agents yet — pair one in Settings → Manage Devices.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                current.agents.forEach { agent ->
                    AgentRow(
                        agent = agent,
                        capacity = capacities[agent.id],
                        capacityPending = agent.id in capacityPending,
                        // Read after `capacities` so a reload's seed and its
                        // freshness recompose together.
                        freshness = capacities.let { viewModel.capacityFreshness(agent.id) },
                        onClick = { scope.launch { viewModel.select(agent) } },
                    )
                }
            }

            is NewChatViewModel.Phase.Folders -> {
                val agent = current.agent
                Text("Folder on ${agent.name}", style = MaterialTheme.typography.labelMedium)
                if (isWakingBox) {
                    WakingRow(agent.name, wakeStartedAt)
                } else if (wakeGaveUp) {
                    TextButton(onClick = { scope.launch { viewModel.retryWake() } }) { Text("Try Again") }
                }
                when {
                    foldersError != null -> Text(foldersError!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    folders.isEmpty() && !isWakingBox && !wakeGaveUp ->
                        Text("No recent folders on ${agent.name}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                folders.forEach { folder ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isStarting) { scope.launch { viewModel.start(folder.path) } }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(
                            folder.path,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                HorizontalDivider()
                Text("Other folder", style = MaterialTheme.typography.labelMedium)
                CustomFolderStarter(viewModel = viewModel, isStarting = isStarting) { workdir ->
                    scope.launch { viewModel.start(workdir) }
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }

            is NewChatViewModel.Phase.Done -> LoadingRow("Starting…")
        }
    }
}

@Composable
private fun CustomFolderStarter(
    viewModel: NewChatViewModel,
    isStarting: Boolean,
    onStart: (String) -> Unit,
) {
    var path by remember { mutableStateOf(viewModel.customPath) }
    var browser by remember { mutableStateOf(viewModel.browserEnabled) }

    OutlinedTextField(
        value = path,
        onValueChange = { path = it; viewModel.customPath = it },
        label = { Text("~/path/to/project") },
        singleLine = true,
        keyboardOptions = KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Browser tools", modifier = Modifier.weight(1f))
        Switch(checked = browser, onCheckedChange = { browser = it; viewModel.browserEnabled = it })
    }
    Button(
        onClick = { onStart(path) },
        enabled = !isStarting && path.trim().isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isStarting) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                Text("Starting…")
            }
        } else {
            Text("Start Here")
        }
    }
    Text(
        "Blank starts in the agent's default folder — pick a recent one above, or type a path.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/// One machine in the picker. [capacity] is the fanned-out `recent_folders`
/// answer for this box (null while unknown or after a failed fetch) and is
/// display-only — the row's click/enabled behaviour never depends on it.
@Composable
private fun AgentRow(
    agent: DeviceDTO,
    capacity: BoxCapacity?,
    capacityPending: Boolean,
    freshness: AgentCapacityFreshness = AgentCapacityFreshness.Live,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Asleep rows are pickable too: the journal wakes the box on the
            // first ask (apple #168).
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    agent.name.ifEmpty { "Unnamed agent" },
                    color = if (agent.connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                capacity?.accountEmail?.let { email ->
                    Text(
                        email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            Text(
                if (agent.connected) "Connected" else "Asleep · Last seen ${agent.lastSeenText()} — tap to wake",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (capacity != null && capacityHasContent(capacity, freshness)) {
                // Cached blocks drop the live-session count — a suspended box
                // runs nothing — while quota lines survive because they
                // describe a window (apple #164).
                shownSessions(capacity, freshness)?.let { live ->
                    Text(
                        if (live == 0) "No active sessions" else "$live active session" + if (live == 1) "" else "s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Every line the bridge sent, in its order — the box's own
                // reading of its limits, not a summary.
                capacity.limitLines.forEach { line ->
                    // One clock reading per line so the caption and the
                    // stale check can't disagree about "now".
                    val nowMs = System.currentTimeMillis()
                    // A percent from before the line's own reset moment is
                    // stale — render it in the caption's muted colour (with
                    // the "reset" caption) rather than the usual
                    // green/orange/red, which would vouch for a dead number.
                    val expired = BoxCapacity.hasReset(line.resetsAt, nowMs)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            line.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${line.percent}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            // Last-known numbers de-emphasise too: a tint would
                            // vouch for them as current.
                            color = if (expired || freshness.isStale) MaterialTheme.colorScheme.outline
                            else UsageMetersFormat.barColor(line.percent),
                        )
                        BoxCapacity.resetText(line.resetsAt, nowMs)?.let {
                            Text(
                                "· $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                freshness.ageText()?.let { age ->
                    Text(
                        age,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            } else if (capacityPending) {
                Text(
                    "Checking…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/// The live-session count a row shows: never for last-known numbers — a
/// suspended box runs nothing, and the count would be a lie. Pure so it's
/// unit-testable (apple #164's `shownSessions`).
internal fun shownSessions(capacity: BoxCapacity?, freshness: AgentCapacityFreshness): Int? =
    if (freshness.isStale) null else capacity?.liveSessions

/// Whether a capacity block renders anything at all under this freshness —
/// a sessions line, quota lines, or (cached) an account email.
internal fun capacityHasContent(capacity: BoxCapacity, freshness: AgentCapacityFreshness): Boolean =
    shownSessions(capacity, freshness) != null || capacity.limitLines.isNotEmpty() ||
        (freshness.isStale && capacity.accountEmail != null)

/// "Waking dev-7…" with a live elapsed-time caption while a wake loop runs.
@Composable
private fun WakingRow(agentName: String, wakeStartedAt: Long?) {
    val elapsed by produceState(0L, wakeStartedAt) {
        while (true) {
            value = wakeStartedAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L
            kotlinx.coroutines.delay(1_000)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
        Column {
            Text("Waking $agentName…")
            if (wakeStartedAt != null) {
                Text(
                    if (elapsed < 60) "${elapsed}s" else "${elapsed / 60}m ${elapsed % 60}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
        Text(label)
    }
}
