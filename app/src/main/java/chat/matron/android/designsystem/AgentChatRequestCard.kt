package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.AgentChatCardState
import chat.matron.android.events.AgentChatRequest

/// The agent-chat consent card, inline in the timeline — one agent asking to
/// talk to another, and the two buttons that answer it.
///
/// Deliberately its own card rather than an [AskUserCard]: a consent decision
/// rests on facts (who is asking, about what, and why) that a generic prompt
/// has nowhere to put, and its answer leaves over HTTP rather than into the
/// conversation, so there is no timeline echo to read the outcome back from —
/// hence the explicit [state].
@Composable
fun AgentChatRequestCard(
    request: AgentChatRequest,
    state: AgentChatCardState,
    /// Carries the "always allow" switch, because approving with it on is one
    /// decision, not two — and it is the only way to create a standing
    /// allowance at all.
    onApprove: (alwaysAllow: Boolean) -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Card-local: nothing outside needs to observe the switch, and keeping it
    // here means a recycled row can't inherit a stale value from another card.
    var alwaysAllow by remember(request.roomID, request.targetDeviceID) { mutableStateOf(false) }
    val busy = state is AgentChatCardState.Sending

    Column(
        modifier
            .shadow(2.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MatronThemeColors.current.bubbleBot)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Agent chat request",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            request.headline,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        request.topic?.let { Detail("About", it) }
        request.justification?.let { Detail("Why", it) }

        when (state) {
            is AgentChatCardState.Answered -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    if (state.approved) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    contentDescription = null,
                    tint = if (state.approved) MatronGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (state.approved) "Approved" else "Declined",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The row stopped awaiting an answer: it timed out (24h), or the
            // decision was already made on another device. Either way there is
            // nothing left to press.
            AgentChatCardState.Expired -> Text(
                "This request is no longer waiting for an answer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = alwaysAllow, onCheckedChange = { alwaysAllow = it }, enabled = !busy)
                    Text(
                        "Always allow ${request.requesterLabel} to do this",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onDeny, enabled = !busy) { Text("Decline") }
                    Button(onClick = { onApprove(alwaysAllow) }, enabled = !busy) { Text("Approve") }
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp))
                }
            }
        }

        (state as? AgentChatCardState.Failed)?.let {
            Text(
                it.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
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
