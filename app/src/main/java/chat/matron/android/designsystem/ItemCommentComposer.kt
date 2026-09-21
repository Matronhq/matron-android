package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/// Whether [draft] is non-blank enough to submit — the mic/send switch's one
/// definition, mirroring `ComposerViewModel.canSend`.
fun itemCommentCanSubmit(draft: String): Boolean = draft.isNotBlank()

/// Hardware-keyboard Return handling (apple #198's Mac rule, mapped to an
/// attached keyboard on Android — the soft keyboard's Return still inserts
/// a newline): plain Enter sends when there is something to send and is
/// swallowed; Shift+Enter, or Enter on an empty draft, falls through so the
/// field inserts the newline — matching the chat composer's Enter /
/// Shift+Enter split.
fun itemCommentEnterSends(isEnter: Boolean, shift: Boolean, draft: String): Boolean =
    isEnter && !shift && itemCommentCanSubmit(draft)

/// Reply composer for the item thread. Matches the chat composer's shape
/// rather than forking its own look: attach on the left, a growing rounded
/// field, mic when the draft is empty and send once it isn't. A leaf — the
/// host owns attachment picking and voice-note recording; this only forwards
/// the intents. Ported from matron-apple's `ItemCommentComposer`.
@Composable
fun ItemCommentComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    isBusy: Boolean,
    onSubmit: () -> Unit,
    onAttach: () -> Unit,
    onVoiceNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sendable = itemCommentCanSubmit(draft)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = ItemTypography.measure)
            .padding(8.dp)
            // Pull the row down to hide the keyboard (apple #200).
            .dragDownDismissesKeyboard(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onAttach, enabled = !isBusy) {
            Icon(Icons.Filled.AttachFile, contentDescription = "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Reply…") },
            maxLines = 8,
            enabled = !isBusy,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier
                .weight(1f)
                // Attached keyboard: Enter sends, Shift+Enter newlines.
                .onPreviewKeyEvent { event ->
                    val enter = event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter)
                    if (itemCommentEnterSends(enter, event.isShiftPressed, draft) && !isBusy) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
        )
        if (sendable) {
            IconButton(onClick = onSubmit, enabled = !isBusy) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send reply", tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            IconButton(onClick = onVoiceNote, enabled = !isBusy) {
                Icon(Icons.Filled.Mic, contentDescription = "Record voice note", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
