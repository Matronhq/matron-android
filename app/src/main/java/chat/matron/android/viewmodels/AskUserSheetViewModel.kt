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
/// `AskUserSheetViewModel`. The send path answers on the wire protocol the
/// prompt arrived on ([AskUserEvent.replyChannel]): a plain text reply targeting
/// the prompt for `ask_user` events, or a `button_response` for the bridge's
/// live buttons protocol.
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
    /// Send; [awaitExpiry] auto-dismisses at the same moment.
    val isExpired: Boolean
        get() {
            val expiresAt = event.expiresAt ?: return false
            return !Instant.now().isBefore(expiresAt)
        }

    /// Tapping Send is a commitment: dismissing the sheet while the send is
    /// suspended does NOT revoke the in-flight answer. Dismissal-without-Send is
    /// handled by the view layer.
    suspend fun send() {
        if (isExpired || _isSending.value || hasSent) return
        _isSending.value = true
        try {
            when (event.replyChannel) {
                AskUserEvent.ReplyChannel.TEXT_REPLY -> {
                    val body = constructReplyBody()
                    if (body.isEmpty()) return
                    timeline.sendText(body, promptEventID)
                }
                AskUserEvent.ReplyChannel.BUTTON_RESPONSE -> {
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

    /// Sleeps until [AskUserEvent.expiresAt], then calls [onExpire] (unless
    /// cancelled). No-op for prompts without an expiry.
    suspend fun awaitExpiry(onExpire: () -> Unit) {
        val expiresAt = event.expiresAt ?: return
        val millis = java.time.Duration.between(Instant.now(), expiresAt).toMillis().coerceAtLeast(0)
        delay(millis)
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

    /// Wire `value`s for the button-response channel, in option order. The
    /// buttons protocol only produces choice/multiChoice kinds; the rest return
    /// empty (send then refuses), defensive only.
    private fun selectedValues(): List<String> = when (val kind = event.kind) {
        is AskUserEvent.InputKind.Choice -> kind.options.filter { selectedChoiceIDs.contains(it.id) }.map { it.value }
        is AskUserEvent.InputKind.MultiChoice -> kind.options.filter { selectedChoiceIDs.contains(it.id) }.map { it.value }
        AskUserEvent.InputKind.Text, AskUserEvent.InputKind.Boolean -> emptyList()
    }
}
