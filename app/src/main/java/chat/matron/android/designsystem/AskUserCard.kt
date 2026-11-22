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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.AskUserEvent
import chat.matron.android.events.compactLine
import chat.matron.android.events.isReminderCard
import java.time.Instant

/// Inline, non-blocking rendering of a bot question in the timeline — the
/// replacement for a blocking modal. Bot-bubble styling. Two states:
///
/// - **Unanswered:** embeds the shared [AskUserSheetBody] (prompt + inputs +
///   Send). The body disables its controls + shows the expired notice when
///   [isExpired].
/// - **Answered:** the prompt plus "✓ You chose: <answerSummary>" (or
///   "✓ Answered" when the specific choice can't be resolved), non-interactive.
///
/// With [foldsReminder] (the timeline turns it on), the bridge's reminder
/// card ([isReminderCard]) draws as one line with its buttons on it, the
/// reminder text one tap away.
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
    foldsReminder: Boolean = false,
    /// The line's time, when folded.
    timestamp: Instant? = null,
    initiallyExpanded: Boolean = false,
) {
    if (foldsReminder && event.isReminderCard) {
        ReminderRow(event, isAnswered, answerSummary, onPickChoice, isSending, isExpired, error, timestamp, initiallyExpanded, modifier)
        return
    }
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

/// The reminder as one line: Send now / Cancel while it is pending, the
/// choice once answered, nothing once it has fired or gone.
@Composable
private fun ReminderRow(
    event: AskUserEvent,
    isAnswered: Boolean,
    answerSummary: String?,
    onPickChoice: (String) -> Unit,
    isSending: Boolean,
    isExpired: Boolean,
    error: String?,
    timestamp: Instant?,
    initiallyExpanded: Boolean,
    modifier: Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val choice = event.kind as? AskUserEvent.InputKind.Choice
    CompactCardRow(
        line = event.compactLine,
        timestamp = timestamp,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
        trailing = {
            if (isAnswered) {
                Text(
                    answerSummary?.let { splitLeadingGlyph(it).second } ?: "Answered",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            } else if (!isExpired && choice != null) {
                // On the line, beside the buttons it is about: a failed tap
                // must not look like a no-op on a folded row.
                error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                for (option in choice.options) {
                    TextButton(
                        onClick = { onPickChoice(option.id) },
                        enabled = !isSending,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) { Text(splitLeadingGlyph(option.label).second, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                }
                if (isSending) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        },
    ) {
        CompactCardTextDetail(event.prompt)
    }
}
