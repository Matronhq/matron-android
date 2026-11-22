package chat.matron.android.viewmodels

import chat.matron.android.chat.TimelineService
import chat.matron.android.events.AskUserEvent
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Drives the ask-user sheet for one prompt. Ported from matron-apple's
/// `AskUserSheetViewModel`. Every answer goes out as a journal `prompt_reply`
/// op; the send path picks which field it lands in
/// ([AskUserEvent.replyChannel]): free text via `text=` for text-kind prompts,
/// or the picked option's wire value(s) via `choice=` for choice/multi-choice
/// prompts.
class AskUserSheetViewModel(
    val event: AskUserEvent,
    /// The event ID of the prompt — the reply's correlation target.
    val promptEventID: String,
    private val timeline: TimelineService,
    private val onClose: () -> Unit,
) {
    /// User-editable answer inputs.
    var textInput: String = ""
    var selectedChoiceIDs: Set<String> = emptySet()
    var booleanAnswer: Boolean? = null

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /// Set once a send has reached the wire successfully. With [isSending] it
    /// makes [send] idempotent: a second Send tap while the first is suspended,
    /// or after success but before the dismiss animation lands, must not answer
    /// the same prompt twice. Errors leave it false so retry stays open.
    private var hasSent = false

    /// True once [AskUserEvent.expiresAt] has passed. UI uses this to disable
    /// Send; [awaitExpiry] fires at the same moment so the caller can re-render
    /// into the expired state — it does not dismiss the sheet itself, so the
    /// card stays visible (now Send-disabled) until the view layer closes it.
    val isExpired: Boolean
        get() {
            val expiresAt = event.expiresAt ?: return false
            return !Instant.now().isBefore(expiresAt)
        }

    /// Tapping Send is a commitment: dismissing the sheet while the send is
    /// suspended does NOT revoke the in-flight answer. Dismissal-without-Send is
    /// handled by the view layer.
    suspend fun send() {
        // Not while an edit holds the message, or is on its way to: Send now
        // would deliver the original the edit is replacing.
        if (isExpired || _isSending.value || hasSent || _isEditing.value || _isSendingEdit.value) return
        _isSending.value = true
        try {
            when (event.replyChannel) {
                AskUserEvent.ReplyChannel.TEXT_REPLY -> {
                    val body = constructReplyBody()
                    if (body.isEmpty()) return
                    timeline.sendText(body, promptEventID)
                }
                AskUserEvent.ReplyChannel.CHOICE_REPLY -> {
                    val values = selectedValues()
                    if (values.isEmpty()) return
                    timeline.sendButtonResponse(values, promptEventID)
                }
            }
            hasSent = true
            onClose()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _error.value = error.message ?: error.toString()
        } finally {
            _isSending.value = false
        }
    }

    // MARK: Editing a queued message

    /// The editor's text while a queued message is being edited.
    var editText: String = ""

    /// The message is held on the bridge's queue and the editor is open.
    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    /// An Edit, Save or Cancel edit is on its way to the journal.
    private val _isSendingEdit = MutableStateFlow(false)
    val isSendingEdit: StateFlow<Boolean> = _isSendingEdit.asStateFlow()

    private val _editError = MutableStateFlow<String?>(null)
    val editError: StateFlow<String?> = _editError.asStateFlow()

    /// Whether this card offers Edit at all ([AskUserEvent.queuedEdit]).
    val offersEdit: Boolean get() = event.queuedEdit != null && !hasSent && !isExpired

    /// How long the bridge holds the message before it goes as it was.
    val holdMinutes: Int get() = maxOf(1, (event.queuedEdit?.holdSeconds ?: 600) / 60)

    /// Save is pointless on an empty editor: the bridge refuses an empty edit.
    val canSaveEdit: Boolean get() = editText.isNotBlank()

    /// Edit: hold the message on the bridge's queue, then open the editor
    /// with its text. The editor only opens once the hold has reached the
    /// journal, so an edit never runs while the message can still go out.
    suspend fun beginEdit() {
        val edit = event.queuedEdit ?: return
        if (_isEditing.value || _isSendingEdit.value || hasSent || _isSending.value) return
        _isSendingEdit.value = true
        try {
            timeline.sendQueuedEdit(AskUserEvent.QueuedEdit.HOLD_CHOICE, null, promptEventID)
            editText = edit.text
            _editError.value = null
            _isEditing.value = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _editError.value = "Couldn't hold the message for editing. ${error.message ?: error}"
        } finally {
            _isSendingEdit.value = false
        }
    }

    /// Save: the edited text takes the message's place in the queue (or goes
    /// at once if the agent finished its turn meanwhile). Unchanged text just
    /// releases the hold.
    suspend fun saveEdit() {
        if (!_isEditing.value || _isSendingEdit.value || hasSent) return
        val text = editText.trim()
        if (text.isEmpty()) return
        if (text == event.queuedEdit?.text?.trim()) {
            cancelEdit()
            return
        }
        _isSendingEdit.value = true
        try {
            timeline.sendQueuedEdit(AskUserEvent.QueuedEdit.SAVE_CHOICE, text, promptEventID)
            hasSent = true
            _isEditing.value = false
            _editError.value = null
            onClose()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _editError.value = "Couldn't save the edit. ${error.message ?: error}"
        } finally {
            _isSendingEdit.value = false
        }
    }

    /// Cancel edit: release the hold; the message keeps its original text.
    /// The editor closes either way: a release that doesn't reach the bridge
    /// still ends when the hold times out.
    suspend fun cancelEdit() {
        if (!_isEditing.value || _isSendingEdit.value) return
        _isSendingEdit.value = true
        _isEditing.value = false
        try {
            timeline.sendQueuedEdit(AskUserEvent.QueuedEdit.CANCEL_CHOICE, null, promptEventID)
            _editError.value = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            _editError.value = "Couldn't release the message. It goes with its original text within $holdMinutes minutes."
        } finally {
            _isSendingEdit.value = false
        }
    }

    /// Sleeps until [AskUserEvent.expiresAt], then calls [onExpire] (unless
    /// cancelled). No-op for prompts without an expiry.
    suspend fun awaitExpiry(onExpire: () -> Unit) {
        val expiresAt = event.expiresAt ?: return
        // Loop rather than a single delay: toMillis() truncates sub-ms
        // remainder (and delay() can wake early), which could fire onExpire
        // while isExpired was still false — the sheet dismissed but Send
        // still enabled for one frame, and a flaky assertion on slow CI.
        while (Instant.now().isBefore(expiresAt)) {
            val millis = java.time.Duration.between(Instant.now(), expiresAt).toMillis().coerceAtLeast(1)
            delay(millis)
        }
        onExpire()
    }

    /// Reply body for the text-reply channel: the chosen option's label, the
    /// free-text input, or Yes/No.
    private fun constructReplyBody(): String = when (val kind = event.kind) {
        AskUserEvent.InputKind.Text -> textInput.trim()
        is AskUserEvent.InputKind.Choice -> {
            val id = selectedChoiceIDs.firstOrNull()
            val option = id?.let { chosen -> kind.options.firstOrNull { it.id == chosen } }
            // No option picked — fall back to the "Other…" field.
            option?.label ?: textInput.trim()
        }
        is AskUserEvent.InputKind.MultiChoice -> {
            val chosen = kind.options.filter { selectedChoiceIDs.contains(it.id) }.map { it.label }.toMutableList()
            val other = textInput.trim()
            if (other.isNotEmpty()) chosen.add(other)
            chosen.joinToString(", ")
        }
        AskUserEvent.InputKind.Boolean -> when (booleanAnswer) {
            true -> "Yes"
            false -> "No"
            null -> ""
        }
    }

    /// Wire `value`s for the `choice=` reply channel, in option order. Only
    /// choice/multiChoice kinds use this channel; the rest return empty (send
    /// then refuses), defensive only.
    private fun selectedValues(): List<String> = when (val kind = event.kind) {
        is AskUserEvent.InputKind.Choice -> kind.options.filter { selectedChoiceIDs.contains(it.id) }.map { it.value }
        is AskUserEvent.InputKind.MultiChoice -> kind.options.filter { selectedChoiceIDs.contains(it.id) }.map { it.value }
        AskUserEvent.InputKind.Text, AskUserEvent.InputKind.Boolean -> emptyList()
    }
}
