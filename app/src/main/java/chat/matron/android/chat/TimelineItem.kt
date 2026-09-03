package chat.matron.android.chat

import chat.matron.android.events.AgentChatRequest
import chat.matron.android.events.AgentSpawnRequest
import chat.matron.android.events.AskUserEvent
import chat.matron.android.events.DiffEvent
import chat.matron.android.events.LiveOutputEvent
import chat.matron.android.events.SpawnOutcome
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
    /// Event ID this message replies to, if any. Lets the view mark an ask-user
    /// prompt answered when a reply targeting it appears — including replies
    /// from the user's other devices.
    val inReplyToEventID: String? = null,
) {
    /// True for the mid-turn streaming-reply placeholder row
    /// (`JournalTimelineMapper.streamingItem`, id `"eph:<messageRef>"`).
    /// It deliberately borrows the real `Text` Kind so it renders as an
    /// ordinary bubble while a reply streams in, but it is NOT a durable
    /// message — no real event ID, hardcoded `sender = "agent"` — and is
    /// replaced by the durable row once the turn's journal event lands.
    /// A `Kind` check alone (`Text`/`Image`/`File`) can't tell this row
    /// apart from a real one, since it uses `Text` on purpose.
    ///
    /// Single source of truth for the two places that need to tell the
    /// difference and would otherwise drift: `ChatViewModel
    /// .hasMultipleSenders` (must not count this row's "agent" as a
    /// distinct sender) and `timelineAvatarSender` (must not attribute a
    /// bubble to it either — the render-side twin of the same bug, Cursor
    /// Bugbot on apple #141).
    val isEphemeralStreamingPlaceholder: Boolean
        get() = id.startsWith("eph:")
    sealed interface Kind {
        // Invariant: every `eventID` field below always equals the enclosing
        // TimelineItem's `id` (both are `event.seq.toString()` from the same
        // mapping call — see JournalTimelineMapper.timelineItem). Duplicated
        // deliberately so card/view code holding just the Kind can still
        // correlate against store events without threading the parent id through.
        data class Text(val body: String, val formattedHTML: String?) : Kind
        /// [expired]: the journal's media reaper deleted this attachment's
        /// blob and tombstoned the event (`expired: true`, `blob_ref` null —
        /// matron-journal#63). Name/size/caption survive for rendering; the
        /// bytes are permanently gone, so the UI shows "Expired" instead of
        /// offering a dead download. Only fresh syncs carry the flag — a
        /// client that synced the event before the reap discovers expiry via
        /// the 404 on fetch (`ChatViewModel.isMediaUnavailable`). Defaulted
        /// `false` (Kotlin idiom) — which also folds in apple #140, whose
        /// whole diff was updating construction sites the Swift enum change
        /// broke.
        data class Image(
            val url: String?,
            val caption: String?,
            val sizeBytes: Long?,
            val expired: Boolean = false,
        ) : Kind
        data class File(
            val url: String?,
            val filename: String,
            val caption: String?,
            val sizeBytes: Long?,
            /// See [Image.expired].
            val expired: Boolean = false,
        ) : Kind
        /// Member joins, name changes — a state event rendered as a small inline
        /// notice. Currently unproduced: no journal event type maps to this kind
        /// (kept — TimelineItemView, ChatViewModel, and TimelineItemPrettyJson
        /// all branch on it — so a future state-notice event can light up without
        /// another sealed-`when` sweep).
        data class StateChange(val text: String) : Kind
        /// Journal `tool_output` event (rendered as a static card unless it
        /// carries a `viewer_url`, in which case the mapper produces [LiveOutput]
        /// instead). `eventID` is kept on the case so updates can be correlated
        /// against an in-flight running tool call.
        data class ToolCall(val eventID: String, val event: ToolCallEvent) : Kind
        /// Journal `diff` event — a file-edit snippet.
        data class Diff(val eventID: String, val event: DiffEvent) : Kind
        /// A live command-output announcement (journal `tool_output` with a
        /// `viewer_url`).
        data class LiveOutput(val eventID: String, val event: LiveOutputEvent) : Kind
        /// Journal `prompt` or `permission_request` event. `eventID` is used by
        /// the reply path so the bot can correlate the answer.
        data class AskUser(val eventID: String, val event: AskUserEvent) : Kind
        /// A journal `prompt_reply` event answering a prompt with a `choice`.
        /// NOT rendered — kept in the snapshot so the view can mark the prompt
        /// answered across devices.
        data class AskUserAnswer(
            val promptEventID: String,
            val selectedValues: List<String>,
        ) : Kind
        /// The journal's agent-chat consent card — one agent asking to talk to
        /// another. Its own kind, not an [AskUser], because the answer goes
        /// over HTTP (`POST /agent-chat/answer`) rather than into the timeline,
        /// and because who-is-asking and why are the whole point of the
        /// decision. `eventID` is the journal seq, used to remember locally
        /// that this card was answered — the answer produces no journal event
        /// to read that back from.
        data class AgentChatRequestCard(
            val eventID: String,
            val request: AgentChatRequest,
        ) : Kind
        /// The journal's agent-spawn consent card — one agent asking to spawn
        /// a child session on another of the user's devices. Its own kind for
        /// the same reasons as [AgentChatRequestCard]: the answer goes over
        /// HTTP (`POST /agent-spawn/answer`), not into the timeline. Unlike
        /// [AgentChatRequestCard], resolution is NOT remembered locally —
        /// `eventID` correlates this card against a later [SpawnOutcomeRow]
        /// carrying the same `request.requestId`, which is the durable record.
        data class AgentSpawnRequestCard(
            val eventID: String,
            val request: AgentSpawnRequest,
        ) : Kind
        /// A journal `spawn_outcome` event — the durable resolution of an
        /// [AgentSpawnRequestCard]. Correlated to its card by
        /// `outcome.requestId`, not by `eventID` (the outcome is a distinct
        /// journal row with its own seq).
        data class SpawnOutcomeRow(
            val eventID: String,
            val outcome: SpawnOutcome,
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
