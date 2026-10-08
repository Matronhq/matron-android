package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import chat.matron.android.models.StagedAttachment

/// Whether there is something to submit — non-blank text or a staged tray.
/// The send button's one definition, mirroring `ComposerViewModel.canSend`.
fun itemCommentCanSubmit(draft: String, hasAttachments: Boolean = false): Boolean =
    draft.isNotBlank() || hasAttachments

/// What an attached keyboard's key-down should do to the reply field.
enum class ItemCommentEnterAction {
    /// Submit the draft and swallow the event.
    SEND,

    /// Swallow the event without submitting — the press must neither post
    /// again nor leak a newline into the field.
    SWALLOW,

    /// Leave the event to the field's own handling (i.e. insert a newline).
    PASS_THROUGH,
}

/// Hardware-keyboard Return handling (apple #198's Mac rule, mapped to an
/// attached keyboard on Android — the soft keyboard's Return still inserts
/// a newline): plain Enter sends when there is something to send and is
/// swallowed; Shift+Enter, or Enter on an empty draft, falls through so the
/// field inserts the newline — matching the chat composer's Enter /
/// Shift+Enter split.
///
/// Two extra cases exist only on Android, because [ItemCommentComposer] reads
/// `draft`/`isBusy` from the composition and `onSubmit` only *launches* the
/// post — both stay stale until the next recomposition, so a second key-down
/// arriving in that window would be judged against the pre-send state:
///
///  - [isRepeat] (the OS auto-repeating a held key, `repeatCount > 0`) is
///    always swallowed. Without this a held Enter sends once and then, as the
///    draft empties, every later repeat falls through and types newlines into
///    the freshly cleared field; a repeat arriving before the send lands would
///    post the reply twice. A repeat never decides anything — only the first
///    key-down of a press does. Shift+Enter is exempt (it is handled above and
///    can never send), so holding Shift+Enter still inserts newlines.
///  - [isBusy] swallows rather than falling through, so an Enter pressed while
///    a post is in flight cannot append a newline to the in-flight draft.
fun itemCommentEnterAction(
    isEnter: Boolean,
    shift: Boolean,
    isRepeat: Boolean,
    isBusy: Boolean,
    draft: String,
    hasAttachments: Boolean = false,
): ItemCommentEnterAction = when {
    !isEnter -> ItemCommentEnterAction.PASS_THROUGH
    shift -> ItemCommentEnterAction.PASS_THROUGH
    isRepeat -> ItemCommentEnterAction.SWALLOW
    isBusy -> ItemCommentEnterAction.SWALLOW
    itemCommentCanSubmit(draft, hasAttachments) -> ItemCommentEnterAction.SEND
    else -> ItemCommentEnterAction.PASS_THROUGH
}

/// Reply composer for the item thread. Matches the chat composer's shape
/// rather than forking its own look: the staged-attachment tray above the
/// row, attach on the left, a growing rounded field, the mic always (a voice
/// note carries the draft and tray with it) and send beside it once there is
/// something to send. A leaf — the host owns attachment picking, staging and
/// voice-note recording; this only forwards the intents. Ported from
/// matron-apple's `ItemCommentComposer`.
@Composable
fun ItemCommentComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    isBusy: Boolean,
    onSubmit: () -> Unit,
    onAttach: () -> Unit,
    onVoiceNote: () -> Unit,
    modifier: Modifier = Modifier,
    attachments: List<StagedAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
) {
    val sendable = itemCommentCanSubmit(draft, attachments.isNotEmpty())
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = ItemTypography.measure),
    ) {
        AttachmentTray(attachments = attachments, onRemove = onRemoveAttachment)
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        when (
                            itemCommentEnterAction(
                                isEnter = enter,
                                shift = event.isShiftPressed,
                                isRepeat = event.nativeKeyEvent.repeatCount > 0,
                                isBusy = isBusy,
                                draft = draft,
                                hasAttachments = attachments.isNotEmpty(),
                            )
                        ) {
                            ItemCommentEnterAction.SEND -> { onSubmit(); true }
                            ItemCommentEnterAction.SWALLOW -> true
                            ItemCommentEnterAction.PASS_THROUGH -> false
                        }
                    },
            )
            IconButton(onClick = onVoiceNote, enabled = !isBusy) {
                Icon(Icons.Filled.Mic, contentDescription = "Record voice note", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sendable) {
                IconButton(onClick = onSubmit, enabled = !isBusy) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send reply", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
