package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.AskUserEvent

/// Inline, non-blocking rendering of a bot question in the timeline — the
/// replacement for a blocking modal. Bot-bubble styling. Two states:
///
/// - **Unanswered:** embeds the shared [AskUserSheetBody] (prompt + inputs +
///   Send). The body disables its controls + shows the expired notice when
///   [isExpired].
/// - **Answered:** the prompt plus "✓ You chose: <answerSummary>" (or
///   "✓ Answered" when the specific choice can't be resolved), non-interactive.
///
/// Fully hoisted on plain values + intent lambdas so it stays a pure leaf.
@Composable
fun AskUserCard(
    event: AskUserEvent,
    isAnswered: Boolean,
    answerSummary: String?,
    textInput: String,
    onTextChange: (String) -> Unit,
    selectedChoiceIDs: Set<String>,
    onToggleChoice: (String) -> Unit,
    onPickChoice: (String) -> Unit,
    onPickBoolean: (Boolean) -> Unit,
    isSending: Boolean,
    isExpired: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    /// Edit on a queued message ([AskUserEvent.queuedEdit]), `null` when the
    /// card offers none.
    edit: QueuedEditControls? = null,
) {
    val cardModifier = modifier
        .shadow(2.dp, RoundedCornerShape(12.dp))
        .clip(RoundedCornerShape(12.dp))
        .background(MatronThemeColors.current.bubbleBot)

    if (isAnswered) {
        Column(cardModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(event.prompt, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MatronGreen, modifier = Modifier.size(18.dp))
                Text(
                    answerSummary?.let { "You chose: $it" } ?: "Answered",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else if (edit != null && edit.isEditing) {
        QueuedMessageEditor(edit, cardModifier)
    } else if (edit != null) {
        Column(cardModifier) {
            AskUserSheetBody(
                event = event,
                textInput = textInput,
                onTextChange = onTextChange,
                selectedChoiceIDs = selectedChoiceIDs,
                onToggleChoice = onToggleChoice,
                onPickChoice = onPickChoice,
                onPickBoolean = onPickBoolean,
                isSending = isSending,
                isExpired = isExpired,
                onSend = onSend,
                error = error,
            )
            QueuedEditRow(edit, enabled = !isSending && !isExpired)
        }
    } else {
        AskUserSheetBody(
            event = event,
            textInput = textInput,
            onTextChange = onTextChange,
            selectedChoiceIDs = selectedChoiceIDs,
            onToggleChoice = onToggleChoice,
            onPickChoice = onPickChoice,
            onPickBoolean = onPickBoolean,
            isSending = isSending,
            isExpired = isExpired,
            onSend = onSend,
            modifier = cardModifier,
            error = error,
        )
    }
}

/// What the card needs to draw Edit and the editor it opens: plain values and
/// lambdas, so the card stays free of view-model types.
data class QueuedEditControls(
    val text: String,
    val onTextChange: (String) -> Unit,
    val isEditing: Boolean,
    val isBusy: Boolean,
    val canSave: Boolean,
    val error: String?,
    val holdMinutes: Int,
    val onEdit: () -> Unit,
    val onSave: () -> Unit,
    val onCancel: () -> Unit,
)

/// Edit, under the card's own Send now / Cancel.
@Composable
private fun QueuedEditRow(edit: QueuedEditControls, enabled: Boolean) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = edit.onEdit, enabled = enabled && !edit.isBusy) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Edit", modifier = Modifier.padding(start = 6.dp))
            }
            if (edit.isBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        edit.error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MatronRed) }
    }
}

/// The open editor: the message is held until Save or Cancel edit.
@Composable
private fun QueuedMessageEditor(edit: QueuedEditControls, modifier: Modifier) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.PauseCircle, contentDescription = null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Held while you edit. It won't be sent until you save or cancel " +
                    "(or after ${edit.holdMinutes} minutes, with the original text).",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = edit.text,
            onValueChange = edit.onTextChange,
            enabled = !edit.isBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 220.dp),
        )
        edit.error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MatronRed) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = edit.onCancel, enabled = !edit.isBusy) { Text("Cancel edit") }
            Button(onClick = edit.onSave, enabled = !edit.isBusy && edit.canSave) { Text("Save") }
            if (edit.isBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}
