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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
