package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import chat.matron.android.models.StagedAttachment

/// The voice-note recorder as the reply composer sees it.
enum class ItemCommentVoice {
    /// No recording: send posts the draft and tray.
    IDLE,

    /// The mic was tapped and the recorder has not started yet (the
    /// permission prompt, or the recorder spinning up). Send waits: posting
    /// the text alone now would leave the note that is about to start
    /// recording with nothing to go with.
    STARTING,

    /// A voice note is recording. The composer's send is the one send: it
    /// stops the note and posts it with the draft and tray as one comment.
    RECORDING,

    /// The stopped note is being prepared to go with the draft and tray.
    /// Send waits, as for [STARTING]: until the note's send has taken the
    /// draft and tray, a second tap would post them on their own beside it.
    SENDING,
}

/// What the composer's send does.
enum class ItemCommentSendRoute {
    /// Post the draft and tray as a comment.
    COMMENT,

    /// Stop the recording and post it, with the draft and tray, as one comment.
    WITH_VOICE_NOTE,

    /// Nothing to send yet.
    NONE,
}

/// Whether there is something to submit — non-blank text or a staged tray,
/// or a voice note recording (which is sendable on its own). Never while the
/// microphone is still starting. The send button's one definition, mirroring
/// `ComposerViewModel.canSend`.
fun itemCommentCanSubmit(
    draft: String,
    hasAttachments: Boolean = false,
    voice: ItemCommentVoice = ItemCommentVoice.IDLE,
): Boolean = itemCommentSendRoute(draft, hasAttachments, voice) != ItemCommentSendRoute.NONE

/// The one place that decides what a send (the button or an attached
/// keyboard's Enter) does. While a note records, every send goes through the
/// recording, so the composer can never post the text and leave the note
/// running behind it.
fun itemCommentSendRoute(
    draft: String,
    hasAttachments: Boolean = false,
    voice: ItemCommentVoice = ItemCommentVoice.IDLE,
): ItemCommentSendRoute = when (voice) {
    ItemCommentVoice.RECORDING -> ItemCommentSendRoute.WITH_VOICE_NOTE
    ItemCommentVoice.STARTING, ItemCommentVoice.SENDING -> ItemCommentSendRoute.NONE
    ItemCommentVoice.IDLE ->
        if (draft.isNotBlank() || hasAttachments) ItemCommentSendRoute.COMMENT else ItemCommentSendRoute.NONE
}

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
///
/// While a voice note records, Enter is the same single send as the button
/// (it sends the note, even over an empty draft); while the microphone is
/// still starting, or the stopped note is still on its way into the send, it
/// is swallowed, so it cannot post the text alone.
fun itemCommentEnterAction(
    isEnter: Boolean,
    shift: Boolean,
    isRepeat: Boolean,
    isBusy: Boolean,
    draft: String,
    hasAttachments: Boolean = false,
    voice: ItemCommentVoice = ItemCommentVoice.IDLE,
): ItemCommentEnterAction = when {
    !isEnter -> ItemCommentEnterAction.PASS_THROUGH
    shift -> ItemCommentEnterAction.PASS_THROUGH
    isRepeat -> ItemCommentEnterAction.SWALLOW
    isBusy -> ItemCommentEnterAction.SWALLOW
    voice == ItemCommentVoice.STARTING || voice == ItemCommentVoice.SENDING -> ItemCommentEnterAction.SWALLOW
    itemCommentCanSubmit(draft, hasAttachments, voice) -> ItemCommentEnterAction.SEND
    else -> ItemCommentEnterAction.PASS_THROUGH
}

/// Reply composer for the item thread. Matches the chat composer's shape
/// rather than forking its own look: the staged-attachment tray above the
/// row, attach on the left, a growing rounded field, the mic always (a voice
/// note carries the draft and tray with it) and send beside it once there is
/// something to send. A leaf — the host owns attachment picking, staging and
/// voice-note recording; this only forwards the intents. Ported from
/// matron-apple's `ItemCommentComposer`.
///
/// While [voice] is recording, a strip above the row shows it with its
/// Cancel, and the row's send is the only send: it is enabled with an empty
/// draft, and the host routes it ([itemCommentSendRoute]) to stop the note
/// and post it with the draft and tray as one comment. The mic is disabled
/// then, and send is disabled while the microphone is still starting or the
/// stopped note is still being taken into the send.
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
    voice: ItemCommentVoice = ItemCommentVoice.IDLE,
    onCancelRecording: () -> Unit = {},
) {
    val hasAttachments = attachments.isNotEmpty()
    val sendable = itemCommentCanSubmit(draft, hasAttachments, voice)
    // Shown (disabled) while the mic starts over a draft, so the button does
    // not vanish and reappear under the user's finger.
    val showsSend = sendable || draft.isNotBlank() || hasAttachments
    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = ItemTypography.measure),
    ) {
        if (voice == ItemCommentVoice.RECORDING) {
            ItemCommentRecordingStrip(onCancel = onCancelRecording)
        }
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
                                hasAttachments = hasAttachments,
                                voice = voice,
                            )
                        ) {
                            ItemCommentEnterAction.SEND -> { onSubmit(); true }
                            ItemCommentEnterAction.SWALLOW -> true
                            ItemCommentEnterAction.PASS_THROUGH -> false
                        }
                    },
            )
            // Never a second send: while a note records (or starts) the
            // mic does nothing — the send beside it carries the note.
            val micEnabled = !isBusy && voice == ItemCommentVoice.IDLE
            IconButton(onClick = onVoiceNote, enabled = micEnabled) {
                Icon(
                    Icons.Filled.Mic, contentDescription = "Record voice note",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (micEnabled) 1f else 0.38f),
                )
            }
            if (showsSend) {
                val enabled = sendable && !isBusy
                IconButton(onClick = onSubmit, enabled = enabled) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (voice == ItemCommentVoice.RECORDING) "Send reply with voice note" else "Send reply",
                        tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/// The live-recording strip above the reply row: what is happening and a
/// way out. It has no send of its own — the row's send carries the note.
@Composable
private fun ItemCommentRecordingStrip(onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Delete, contentDescription = "Cancel recording", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        Text("Recording…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
