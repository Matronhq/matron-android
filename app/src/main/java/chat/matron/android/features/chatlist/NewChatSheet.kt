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
import chat.matron.android.viewmodels.BoxCapacity
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
    prepareConversation: suspend (String) -> Unit,
    onCreated: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { NewChatViewModel(api) }
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val foldersError by viewModel.foldersError.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isStarting by viewModel.isStarting.collectAsStateWithLifecycle()
    val capacities by viewModel.capacities.collectAsStateWithLifecycle()
    val capacityPending by viewModel.capacityPending.collectAsStateWithLifecycle()

    var navigated by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
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
                        onClick = { scope.launch { viewModel.select(agent) } },
                    )
                }
            }

            is NewChatViewModel.Phase.Folders -> {
                val agent = current.agent
                Text("Folder on ${agent.name}", style = MaterialTheme.typography.labelMedium)
                when {
                    foldersError != null -> Text(foldersError!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    folders.isEmpty() -> Text("No recent folders on ${agent.name}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = agent.connected, onClick = onClick)
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
                if (agent.connected) "Connected" else "Offline · Last seen ${agent.lastSeenText()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!agent.connected) {
                // Offline rows stay exactly as they were — no capacity to show.
            } else if (capacity != null) {
                capacity.liveSessions?.let { live ->
                    Text(
                        if (live == 0) "No active sessions" else "$live active session" + if (live == 1) "" else "s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Every line the bridge sent, in its order — the box's own
                // reading of its limits, not a summary.
                capacity.limitLines.forEach { line ->
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
                            color = UsageMetersFormat.barColor(line.percent),
                        )
                        BoxCapacity.resetText(line.resetsAt)?.let {
                            Text(
                                "· $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
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
