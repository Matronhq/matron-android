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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    /// Answers this one request. There is no standing consent to grant: the
    /// next ask from the same pair gets its own card.
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        // Both ends spelled out. The headline names the two devices, but a box
        // can be running a dozen sessions — these say which one asked and
        // which one is being asked. They fall back to the device name alone
        // when the journal named no session, never to an empty row.
        Detail("From", request.fromLabel)
        Detail("To", request.toLabel)
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
                    OutlinedButton(onClick = onDeny, enabled = !busy) { Text("Decline") }
                    Button(onClick = onApprove, enabled = !busy) { Text("Approve") }
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
