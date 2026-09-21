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
import androidx.compose.ui.unit.dp

/// Whether [draft] is non-blank enough to submit — the mic/send switch's one
/// definition, mirroring `ComposerViewModel.canSend`.
fun itemCommentCanSubmit(draft: String): Boolean = draft.isNotBlank()

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
            .padding(8.dp),
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
            modifier = Modifier.weight(1f),
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
