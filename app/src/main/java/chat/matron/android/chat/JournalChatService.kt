package chat.matron.android.chat

import chat.matron.android.journal.JournalStore
import chat.matron.android.journal.JournalSyncEngine
import chat.matron.android.journal.db.ConversationEntity
import chat.matron.android.models.BotIdentity
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/// Errors surfaced by the journal chat/timeline services. `data object`/
/// `data class` cases give value-equality for `assertEquals`.
sealed class JournalChatError(message: String) : Exception(message) {
    data object CreationNotSupported : JournalChatError(
        "Creating conversations from the app needs server support (convo_create) — coming soon."
    )
    data object MediaNotSupported : JournalChatError(
        "Attachments need the server's /media endpoint — coming soon."
    )
    data class InvalidPromptReference(val id: String) : JournalChatError(
        "Can't answer this prompt — its reference (\"$id\") isn't a journal row."
    )
}

/// [ChatService] over the local journal mirror. The chat list is a pure read of
/// the store; freshness is the sync engine's job. Ported from matron-apple's
/// `JournalChatService`.
class JournalChatService(
    private val store: JournalStore,
    private val engine: JournalSyncEngine,
    private val coalesceInterval: Duration = 250.milliseconds,
) : ChatService {

    override fun chatSummaries(): Flow<List<ChatSummary>> = flow {
        // A reconnect replay applies each missed frame in its own store
        // transaction, so a catch-up burst yields one snapshot per frame. The
        // first goes out immediately (instant paint from the local mirror),
        // then at most one per `coalesceInterval`, always the newest —
        // `conflate()` drops every intermediate snapshot that lands while the
        // pacer sleeps (the Apple `bufferingNewest(1)` + `Task.sleep` pacer).
        store.conversationsFlow().conflate().collect { records ->
            emit(records.map(::summary))
            delay(coalesceInterval)
        }
    }

    override fun children(parentConvoID: String): Flow<List<SubChatSummary>> =
        store.childrenFlow(parentConvoID).map { records -> records.map(::childSummary) }

    override suspend fun createChat(botID: String): String = throw JournalChatError.CreationNotSupported

    override suspend fun refresh() = engine.waitUntilReady()

    override suspend fun forceSnapshot() = engine.refreshSummaries()

    override suspend fun mute(roomID: String) = store.setMuted(true, roomID)

    override suspend fun leave(roomID: String) = store.setHidden(true, roomID)

    companion object {
        fun summary(record: ConversationEntity): ChatSummary {
            val activityMS = record.lastActivityTS ?: record.createdAt.takeIf { it > 0 }
            return ChatSummary(
                id = record.id,
                title = record.title.ifEmpty { record.id },
                bot = BotIdentity(matrixID = "agent:claude", displayName = "Claude", avatarURL = null),
                lastActivity = activityMS?.let { Instant.ofEpochMilli(it) },
                unreadCount = record.unreadCount,
                snippet = record.snippet,
                parentConvoID = record.parentConvoID,
            )
        }

        fun childSummary(record: ConversationEntity): SubChatSummary = SubChatSummary(
            id = record.id,
            title = record.title.ifEmpty { record.id },
            isRunning = record.sessionState == "running",
        )
    }
}
