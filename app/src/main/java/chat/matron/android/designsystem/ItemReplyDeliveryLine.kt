package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.matron.android.models.QueuedReplyState

/// The words under a reply of the user's that the agent hasn't got yet.
/// [editing] is whether the reply is held while its text is edited in the
/// reply box.
fun itemReplyDeliveryText(state: QueuedReplyState, editing: Boolean = false): String = when {
    editing && state.canSendNow -> "Editing: held until you send the edited reply or cancel the edit"
    else -> when (state) {
        is QueuedReplyState.Queued -> "Queued: the agent is mid-turn and hasn't seen this yet"
        QueuedReplyState.Sending -> "Sending now…"
        QueuedReplyState.Cancelling -> "Cancelling…"
        is QueuedReplyState.SendFailed -> "Still queued. ${state.reason}"
        QueuedReplyState.Cancelled -> "Cancelled: not sent to the agent"
        QueuedReplyState.NotDelivered -> "Not delivered: the session ended before the agent got it"
    }
}

/// What Send now says it does: a card that can't release a single reply
/// sends everything queued for the agent, as in the conversation.
fun itemReplySendNowHint(state: QueuedReplyState): String =
    if (state.card?.offersSendOne == false) {
        "Delivers this reply to the agent now, with anything else queued for it, interrupting its current turn"
    } else {
        "Delivers this reply to the agent now, interrupting its current turn"
    }

private fun deliveryIcon(state: QueuedReplyState, editing: Boolean): ImageVector = when {
    editing && state.canSendNow -> Icons.Outlined.Edit
    state is QueuedReplyState.Queued || state == QueuedReplyState.Sending -> Icons.Outlined.Schedule
    state is QueuedReplyState.SendFailed || state == QueuedReplyState.NotDelivered -> Icons.Outlined.ErrorOutline
    else -> Icons.Outlined.Cancel
}

/// The line under a reply of the user's that the agent hasn't got yet, so it
/// never reads as sent when it wasn't. A reply that reached the agent draws
/// nothing here: it looks like every other comment.
///
/// While the reply waits on the busy queue it offers Edit (hold it and put its
/// text in the reply box), Cancel (withdraw it) and Send now (deliver it now,
/// interrupting the agent's turn). While it is being edited, only Cancel edit.
/// A reply that never reached the agent offers Edit and resend. A `null`
/// handler draws no button: a button wired to nothing would look like it
/// acted and do nothing.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemReplyDeliveryLine(
    state: QueuedReplyState,
    onSendNow: (() -> Unit)?,
    onCancel: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onEditAndResend: (() -> Unit)? = null,
    isEditing: Boolean = false,
    onCancelEdit: (() -> Unit)? = null,
    /// Edit's hold is on its way: a spinner, and no buttons yet.
    holdPending: Boolean = false,
) {
    val color = if (state.isFailure) MatronRed else MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(deliveryIcon(state, isEditing), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(
                itemReplyDeliveryText(state, isEditing),
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (state.isAwaitingBridge || holdPending) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        when {
            state.isAwaitingBridge || holdPending -> Unit
            isEditing && state.canSendNow -> onCancelEdit?.let { cancelEdit ->
                OutlinedButton(
                    onClick = cancelEdit,
                    modifier = Modifier.semantics { contentDescription = "Cancel edit. Lets this reply go as it was" },
                ) { Text("Cancel edit") }
            }
            state.canSendNow -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                onEdit?.let { edit ->
                    OutlinedButton(
                        onClick = edit,
                        modifier = Modifier.semantics {
                            contentDescription = "Edit. Holds this reply so the agent doesn't get it while you edit it in the reply box"
                        },
                    ) { Text("Edit") }
                }
                onCancel?.let { cancel ->
                    OutlinedButton(
                        onClick = cancel,
                        modifier = Modifier.semantics {
                            contentDescription = "Cancel. Withdraws this reply before the agent sees it"
                        },
                    ) { Text("Cancel") }
                }
                onSendNow?.let { send ->
                    Button(
                        onClick = send,
                        modifier = Modifier.semantics { contentDescription = "Send now. ${itemReplySendNowHint(state)}" },
                    ) { Text("Send now") }
                }
            }
            state.canResend -> onEditAndResend?.let { resend ->
                OutlinedButton(
                    onClick = resend,
                    modifier = Modifier.semantics { contentDescription = "Edit and resend. Puts this reply's text in the reply box" },
                ) { Text("Edit and resend") }
            }
        }
    }
}
