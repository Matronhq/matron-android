package chat.matron.android.chat

import chat.matron.android.models.AttachmentBatchTag
import chat.matron.android.models.SessionStatusUpdate
import chat.matron.android.models.SyncConnectionState
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/// One TOC entry from a bridge summary pass, as consumed by the Chat layer.
/// Mirrors the journal module's `SummaryEntryEntity` but lives here so Chat
/// consumers (view models, the summaries sheet) don't import the storage
/// layer. Ported from matron-apple's `ConversationSummaryEntry`.
data class ConversationSummaryEntry(
    /// The summary event's own journal seq — the transcript anchor a
    /// jump-to-point navigation scrolls to.
    val seq: Long,
    /// One-line "what just happened" (collapsed row text).
    val toc: String,
    /// The fuller rolling paragraph (expanded row text); may be empty.
    val detail: String,
    val date: Instant,
)

/// Per-room timeline access, one instance per open room. Ported from
/// matron-apple's `TimelineService` protocol. `items()` is the read side (full
/// snapshots, newest last); the rest are the write side.
interface TimelineService {
    /// Full timeline snapshots, newest item last.
    fun items(): Flow<List<TimelineItem>>

    /// Sends a plain text message (markdown allowed). When [inReplyTo] is a
    /// numeric journal seq the send becomes a `prompt_reply` targeting it.
    suspend fun sendText(body: String, inReplyTo: String?)

    /// Sends a `prompt_reply` with `choice=` — the answer to a choice/multi-choice
    /// prompt. [inReplyTo] is the prompt's journal seq.
    suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String)

    /// Sends an image attachment. [caption] rides on the event itself.
    suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?)

    /// Sends a file attachment. [caption] behaves as for [sendImage].
    suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?)

    /// Progress-reporting variants: [progress] receives the uploaded fraction
    /// (0…1), off-main. Defaults drop the handler and forward to the plain
    /// sends so fakes and outbox-less implementations compile unchanged;
    /// [JournalTimelineService] overrides both.
    suspend fun sendImage(
        data: ByteArray, filename: String, mimeType: String, caption: String?, progress: ((Double) -> Unit)?,
    ) = sendImage(data, filename, mimeType, caption)

    suspend fun sendFile(
        data: ByteArray, filename: String, mimeType: String, caption: String?, progress: ((Double) -> Unit)?,
    ) = sendFile(data, filename, mimeType, caption)

    /// Batch-tagged variants, used by the composer when one send carries
    /// several attachments. [batch] marks each upload's place in that send so
    /// the journal bridge can gather the frames back into one prompt
    /// ([AttachmentBatchTag]). The defaults drop the tag and forward to the
    /// progress sends, so fakes and transports without batch delivery (where
    /// each attachment simply arrives as its own message, today's behavior)
    /// compile unchanged; [JournalTimelineService] overrides both.
    suspend fun sendImage(
        data: ByteArray, filename: String, mimeType: String, caption: String?,
        batch: AttachmentBatchTag?, progress: ((Double) -> Unit)?,
    ) = sendImage(data, filename, mimeType, caption, progress)

    suspend fun sendFile(
        data: ByteArray, filename: String, mimeType: String, caption: String?,
        batch: AttachmentBatchTag?, progress: ((Double) -> Unit)?,
    ) = sendFile(data, filename, mimeType, caption, progress)

    /// Retries a pending/failed own-message (the timeline's tap-to-retry
    /// affordance). [itemID] is the timeline item's id. Implementations
    /// without an offline outbox inherit the default no-op.
    suspend fun retrySend(itemID: String) {}

    /// Discards an unsent own-message. Default no-op, same as [retrySend].
    suspend fun discardSend(itemID: String) {}

    /// Paginates older history. Returns `true` if the fetched page had new rows.
    suspend fun paginateBackward(requestSize: Int): Boolean

    /// Marks the most recent visible event as read.
    suspend fun markAsRead()

    /// Per-convo stream of session-status updates. Default: an empty stream, so
    /// fakes without a status source need no override.
    fun sessionStatus(): Flow<SessionStatusUpdate> = emptyFlow()

    /// Durable turn state for this conversation ("running" / "waiting" /
    /// "done"), flipped by the bridge at turn boundaries. Covers the whole
    /// turn, unlike the ephemeral activity indicator — drives the floating
    /// stop button. Default: an empty stream, same rationale as
    /// [sessionStatus].
    fun sessionState(): Flow<String> = emptyFlow()

    /// TOC summary entries for this conversation, newest-first; re-yields on
    /// every change. Default: an empty stream, same rationale as
    /// [sessionStatus] (fakes and non-journal backends need no override).
    fun summaryEntriesStream(): Flow<List<ConversationSummaryEntry>> = emptyFlow()

    /// The underlying sync engine's connection state. Default: an empty stream,
    /// so fakes without a connectivity source need no override. Lets a VM cheaply
    /// observe connectivity without a new dependency of its own.
    fun connectionState(): Flow<SyncConnectionState> = emptyFlow()

    /// Plain send with no reply relation.
    suspend fun sendText(body: String) = sendText(body, null)
}
