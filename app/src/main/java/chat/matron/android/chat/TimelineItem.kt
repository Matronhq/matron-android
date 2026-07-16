package chat.matron.android.chat

import chat.matron.android.events.AskUserEvent
import chat.matron.android.events.DiffEvent
import chat.matron.android.events.LiveOutputEvent
import chat.matron.android.events.ToolCallEvent
import chat.matron.android.models.TimelineSendState
import java.time.Instant

/// DTO consumed by the UI for a single timeline row. `id` is stable across the
/// local-echo → remote-event transition so list-diffing stays smooth as a sent
/// message is acknowledged.
data class TimelineItem(
    val id: String,
    val sender: String,
    val timestamp: Instant,
    val kind: Kind,
    /// `true` if the local user sent this event.
    val isOwn: Boolean,
    val sendState: TimelineSendState = TimelineSendState.Sent,
    /// Event ID this message replies to, if any. Lets the view mark an
    /// `ask_user` prompt answered when a reply targeting it appears — including
    /// replies from the user's other devices.
    val inReplyToEventID: String? = null,
) {
    sealed interface Kind {
        data class Text(val body: String, val formattedHTML: String?) : Kind
        data class Image(val url: String?, val caption: String?, val sizeBytes: Long?) : Kind
        data class File(
            val url: String?,
            val filename: String,
            val caption: String?,
            val sizeBytes: Long?,
        ) : Kind
        /// Member joins, name changes — a state event rendered as a small inline
        /// notice.
        data class StateChange(val text: String) : Kind
        /// `chat.matron.tool_call` event. `eventID` is kept on the case so
        /// updates can be correlated against an in-flight running tool call.
        data class ToolCall(val eventID: String, val event: ToolCallEvent) : Kind
        /// Journal `diff` event — a file-edit snippet.
        data class Diff(val eventID: String, val event: DiffEvent) : Kind
        /// A live command-output announcement (journal `tool_output` with a
        /// `viewer_url`).
        data class LiveOutput(val eventID: String, val event: LiveOutputEvent) : Kind
        /// `chat.matron.ask_user` event. `eventID` is used by the reply path so
        /// the bot can correlate the answer.
        data class AskUser(val eventID: String, val event: AskUserEvent) : Kind
        /// A `chat.matron.button_response` answer to a buttons prompt. NOT
        /// rendered — kept in the snapshot so the view can mark the prompt
        /// answered across devices.
        data class AskUserAnswer(
            val promptEventID: String,
            val selectedValues: List<String>,
        ) : Kind
        /// Transient typing / tool-use indicator. Not persisted; appended as a
        /// trailing overlay row while the agent is thinking or running a tool.
        data class ActivityIndicator(val label: String) : Kind
        /// Live tool-output overlay (journal `tool_stream` ephemerals). Not
        /// persisted; retired when the durable `tool_output` row with the same
        /// `messageRef` lands. `command` is null until a `sync` frame supplies
        /// meta.
        data class ToolStreamLive(
            val messageRef: String,
            val command: String?,
            val text: String,
            val headTruncated: Boolean,
        ) : Kind
        /// Catch-all for events we don't render specially yet. UI shows a
        /// placeholder so the event isn't silently dropped.
        data class Unknown(val eventType: String) : Kind
    }
}
