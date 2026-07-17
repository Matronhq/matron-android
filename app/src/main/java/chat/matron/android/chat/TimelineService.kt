package chat.matron.android.chat

import chat.matron.android.models.SessionStatusUpdate
import chat.matron.android.models.SyncConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/// Per-room timeline access, one instance per open room. Ported from
/// matron-apple's `TimelineService` protocol. `items()` is the read side (full
/// snapshots, newest last); the rest are the write side.
interface TimelineService {
    /// Full timeline snapshots, newest item last.
    fun items(): Flow<List<TimelineItem>>

    /// Sends a plain text message (markdown allowed). When [inReplyTo] is a
    /// numeric journal seq the send becomes a `prompt_reply` targeting it.
    suspend fun sendText(body: String, inReplyTo: String?)

    /// Sends a `button_response` answer to a buttons prompt. [inReplyTo] is the
    /// prompt's journal seq.
    suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String)

    /// Sends an image attachment. [caption] rides on the event itself.
    suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?)

    /// Sends a file attachment. [caption] behaves as for [sendImage].
    suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?)

    /// Paginates older history. Returns `true` if the fetched page had new rows.
    suspend fun paginateBackward(requestSize: Int): Boolean

    /// Marks the most recent visible event as read.
    suspend fun markAsRead()

    /// Per-convo stream of session-status updates. Default: an empty stream, so
    /// fakes without a status source need no override.
    fun sessionStatus(): Flow<SessionStatusUpdate> = emptyFlow()

    /// The underlying sync engine's connection state. Default: an empty stream,
    /// so fakes without a connectivity source need no override. Lets a VM cheaply
    /// observe connectivity without a new dependency of its own.
    fun connectionState(): Flow<SyncConnectionState> = emptyFlow()

    /// Plain send with no reply relation.
    suspend fun sendText(body: String) = sendText(body, null)
}
