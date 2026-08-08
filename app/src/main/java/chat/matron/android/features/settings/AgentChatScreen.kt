package chat.matron.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.journal.AgentChatAllowanceDTO
import chat.matron.android.journal.AgentChatDecision
import chat.matron.android.journal.AgentChatPendingDTO
import chat.matron.android.viewmodels.AgentChatProviding
import chat.matron.android.viewmodels.AgentChatViewModel
import kotlinx.coroutines.launch

/**
 * Settings → Agent Chats. Ports Features/Settings/AgentChatView.swift: requests
 * still waiting on a decision, and the standing allowances that let future
 * requests through without asking.
 *
 * The consent card in a conversation is the primary surface; this screen is the
 * two things a card can't be. A request that arrived while no client was
 * connected has no card to tap, and an allowance granted with "always allow" is
 * otherwise invisible and permanent — this is where it can be seen and taken
 * back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    api: AgentChatProviding,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { AgentChatViewModel(api) }
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val allowances by viewModel.allowances.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val isSupported by viewModel.isSupported.collectAsStateWithLifecycle()
    val busyIDs by viewModel.busyIDs.collectAsStateWithLifecycle()

    var confirmingRevoke by remember { mutableStateOf<AgentChatAllowanceDTO?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Chats") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (errorMessage != null) {
                item {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            if (!isSupported) {
                item {
                    Text(
                        "This server doesn't support agent chats yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                return@LazyColumn
            }

            item { SectionHeader("Waiting for you") }
            if (pending.isEmpty()) {
                item { Hint("No requests waiting.") }
            } else {
                items(pending, key = { it.id }) { row ->
                    PendingRow(
                        row = row,
                        isBusy = row.id in busyIDs,
                        onDecide = { decision ->
                            scope.launch { viewModel.answer(row, decision) }
                        },
                    )
                }
            }
            item {
                Hint(
                    "An agent asking to talk to another agent waits here until you decide. " +
                        "Unanswered requests expire after 24 hours.",
                )
            }

            item { SectionHeader("Always allowed") }
            if (allowances.isEmpty()) {
                item { Hint("None — every request asks you first.") }
            } else {
                items(allowances, key = { it.id }) { allowance ->
                    AllowanceRow(
                        allowance = allowance,
                        isBusy = allowance.id in busyIDs,
                        onRevoke = { confirmingRevoke = allowance },
                    )
                }
            }
            item {
                Hint(
                    "These pairs skip the request entirely. Each one is one-way: allowing A to " +
                        "reach B says nothing about B reaching A.",
                )
            }
        }
    }

    confirmingRevoke?.let { allowance ->
        AlertDialog(
            onDismissRequest = { confirmingRevoke = null },
            title = { Text("Stop always allowing this?") },
            text = {
                Text(
                    "${allowance.fromLabel} will have to ask you again before talking to " +
                        "${allowance.targetLabel}.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRevoke = null
                    scope.launch { viewModel.revoke(allowance) }
                }) { Text("Stop Allowing") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRevoke = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PendingRow(
    row: AgentChatPendingDTO,
    isBusy: Boolean,
    onDecide: (AgentChatDecision) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(row.headline, style = MaterialTheme.typography.bodyLarge)
        row.topic?.let { Labelled("About", it) }
        row.justification?.let { Labelled("Why", it) }
        Text(
            "Asked ${AgentChatViewModel.ageText(row.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { onDecide(AgentChatDecision.DENY) }, enabled = !isBusy) {
                Text("Decline")
            }
            Button(onClick = { onDecide(AgentChatDecision.APPROVE) }, enabled = !isBusy) {
                Text("Approve")
            }
            if (isBusy) CircularProgressIndicator(Modifier.size(18.dp))
        }
        // "Always allow" is deliberately not offered here. It is a standing
        // grant, and this screen shows asks stripped of the conversation they
        // belong to — the consent card in the chat, where the surrounding
        // context is visible, is the place to make a decision that outlives the
        // one request.
    }
}

@Composable
private fun AllowanceRow(
    allowance: AgentChatAllowanceDTO,
    isBusy: Boolean,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${allowance.fromLabel} → ${allowance.targetLabel}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Can start a chat without asking",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRevoke, enabled = !isBusy) { Text("Stop Allowing") }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Labelled(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
