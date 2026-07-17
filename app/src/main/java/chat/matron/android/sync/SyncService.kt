package chat.matron.android.sync

import chat.matron.android.models.SyncConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/// Lifecycle + observation surface for the sync engine, ported from
/// matron-apple's `SyncService` protocol. [chat.matron.android.journal.JournalSyncEngine]
/// conforms directly (the Apple side layers this on via
/// `JournalSyncConformance`; Kotlin can't retro-conform, so the engine
/// implements the interface and delegates `start`/`stop` to its own
/// `beginSync`/`endSync`).
interface SyncService {
    /// Starts sync. Caller must keep a strong reference.
    suspend fun start()

    /// Stops sync.
    suspend fun stop()

    /// True after [start] has been called, false after [stop].
    fun isRunning(): Boolean

    /// Suspends until [start] has been called and the engine reports ready.
    suspend fun waitUntilReady()

    /// Long-lived state of the socket's connection for the chat-list banner.
    /// Replays the current value on collect (Swift's "yield current on
    /// subscribe"), then every transition.
    val stateStream: StateFlow<SyncConnectionState>

    /// Ids of conversations created live (first frame while connected and
    /// caught up). Does not replay a reconnect backlog.
    fun newConversations(): Flow<String>
}
