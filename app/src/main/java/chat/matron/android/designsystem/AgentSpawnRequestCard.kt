package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.AgentSpawnCardState
import chat.matron.android.events.AgentSpawnRequest
import chat.matron.android.events.SpawnOutcome

/// The agent-spawn consent card, inline in the timeline — one agent asking
/// to spawn a child session on another of the user's devices, and the two
/// buttons that answer it.
///
/// Mirrors [AgentChatRequestCard] with one difference downstream of
/// [AgentSpawnCardState]'s own doc: [state]'s `Resolved` case is never a
/// locally-remembered decision, only the journal's own `spawn_outcome` event
/// (or a synthetic stand-in) — so a `started` outcome carries the spawned
/// room's id, and the resolved row offers an [onOpen] deep link to it.
@Composable
fun AgentSpawnRequestCard(
    request: AgentSpawnRequest,
    state: AgentSpawnCardState,
    /// Answers this one request. There is no standing consent to grant: the
    /// next ask from the same pair gets its own card.
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    /// Jumps to the spawned session's room. No-op by default — wired once
    /// the app has somewhere to navigate to (agent-spawn-card plan Task 3).
    onOpen: (roomId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val busy = state is AgentSpawnCardState.Sending

    Column(
        modifier
            .shadow(2.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MatronThemeColors.current.bubbleBot)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Agent spawn request",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            request.headline,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // From/Target spelled out. The headline may just be the topic, and a
        // device can be running many sessions — these say which one asked
        // and which one is being asked to run the child.
        Detail("From", request.fromLabel)
        Detail("Target", request.targetLabel)
        Detail("Folder", request.workdir)
        // The full, unapproved seed prompt — the whole point of asking first.
        Text(
            request.task,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        when (state) {
            is AgentSpawnCardState.Resolved -> ResolvedRow(outcome = state.outcome, onOpen = onOpen)

            else -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onDeny, enabled = !busy) { Text("Decline") }
                    Button(onClick = onApprove, enabled = !busy) { Text("Approve") }
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                }
            }
        }

        (state as? AgentSpawnCardState.Failed)?.let {
            Text(
                it.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/// Icon + copy for a resolved card, with the "Open" deep link a `started`
/// outcome (and only that one) carries a room id for.
@Composable
private fun ResolvedRow(outcome: SpawnOutcome, onOpen: (roomId: String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                resolvedIcon(outcome.outcome),
                contentDescription = null,
                tint = if (outcome.outcome == "started") MatronGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                outcome.displayLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val roomId = outcome.roomId
        if (outcome.outcome == "started" && roomId != null) {
            TextButton(onClick = { onOpen(roomId) }) { Text("Open") }
        }
    }
}

private fun resolvedIcon(outcome: String) = when (outcome) {
    "started" -> Icons.Filled.RocketLaunch
    "declined" -> Icons.Filled.Cancel
    "expired" -> Icons.Filled.HourglassEmpty
    "failed" -> Icons.Filled.Error
    else -> Icons.Filled.CheckCircle
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
