package chat.matron.android.chat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/// Chat-list access, ported from matron-apple's `ChatService` protocol.
/// `chatSummaries()` / `children()` are the read side (full-list snapshots, not
/// deltas); the rest are actions.
interface ChatService {
    /// Long-lived stream of full chat-list snapshots. Emits the current list
    /// immediately, then a fresh snapshot per change (coalesced by the live
    /// impl). The Apple side is `AsyncThrowingStream` so readiness failures
    /// bubble; a Kotlin `Flow` carries the same (exceptions propagate to the
    /// collector).
    fun chatSummaries(): Flow<List<ChatSummary>>

    /// Long-lived stream of a parent conversation's subagent children, in
    /// creation order (running + finished). Nesting recurses.
    fun children(parentConvoID: String): Flow<List<SubChatSummary>>

    /// Creates a new chat with `botID` and returns its id.
    suspend fun createChat(botID: String): String

    /// Blocks until sync has bootstrapped.
    suspend fun refresh()

    /// Feeds a fresh one-shot snapshot through the summaries pipe (pull-to-
    /// refresh).
    suspend fun forceSnapshot()

    /// Mutes notifications for `roomID`. Idempotent.
    suspend fun mute(roomID: String)

    /// Leaves/hides `roomID`.
    suspend fun leave(roomID: String)

    /// Joined-room ids for a push bootstrap, sourced from [chatSummaries].
    ///
    /// Waits for the first NON-EMPTY snapshot, bounded by [timeout]: the first
    /// yield may be `[]` while sync warms up, and a genuinely room-less account
    /// yields `[]` then nothing — so the bound returns `[]` rather than hanging.
    /// A thrown or finished stream also collapses to `[]` (a cold/failed chat
    /// list isn't worth failing push setup over).
    suspend fun firstSnapshotRoomIDs(timeout: Duration = 30.seconds): List<String> =
        withTimeoutOrNull(timeout) {
            runCatching {
                chatSummaries().firstOrNull { it.isNotEmpty() }?.map { it.id }
            }.getOrNull() ?: emptyList()
        } ?: emptyList()
}
