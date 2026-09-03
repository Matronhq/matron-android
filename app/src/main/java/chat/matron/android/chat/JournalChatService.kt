package chat.matron.android.chat

import chat.matron.android.journal.JournalStore
import chat.matron.android.journal.JournalSyncEngine
import chat.matron.android.journal.SessionState
import chat.matron.android.journal.db.ConversationEntity
import chat.matron.android.models.BotIdentity
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/// Errors surfaced by the journal chat/timeline services. `data object`/
/// `data class` cases give value-equality for `assertEquals`.
sealed class JournalChatError(message: String) : Exception(message) {
    data object CreationNotSupported : JournalChatError(
        "Creating conversations from the app needs server support (convo_create) — coming soon."
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
    /// The user's tag-character overrides (Settings → Devices). Optional so
    /// tests that never touch tags need no store; null means "no overrides,
    /// ever" — derived letters only.
    private val overrides: BoxLetterOverrides? = null,
) : ChatService {

    override fun chatSummaries(): Flow<List<ChatSummary>> = flow {
        // A reconnect replay lands in batched transactions (one commit per
        // ~250 frames), but a catch-up burst still yields several snapshots.
        // The first goes out immediately (instant paint from the local
        // mirror), then at most one per `coalesceInterval`, always the newest
        // — `conflate()` drops every intermediate snapshot that lands while
        // the pacer sleeps (the Apple `bufferingNewest(1)` + `Task.sleep`
        // pacer).
        //
        // The agent roster rides a second flow because Room's invalidation
        // tracker only re-fires a Flow for the tables its query reads: a
        // rename writes `agent`, which the conversations query never touches,
        // so without this every open chip would keep the old label until some
        // unrelated conversation write happened to re-fire the list.
        // `combine` is the Kotlin shape of the Apple original's two-input
        // doorbell (`SummaryInputs` + a one-slot signal stream): it re-emits
        // on either input, always over the newest pair, and both Room flows
        // deliver an initial value on collect so the very first paint already
        // carries its chips.
        // A tag-character override (Settings → Devices) writes only the
        // preference store — no journal record, no Room re-fire — so its
        // change flow rides the same combine to re-derive letters live.
        val overridesFlow = overrides?.flow ?: flowOf(emptyMap())
        combine(store.conversationsFlow(), store.agentNamesFlow(), overridesFlow) { records, boxNames, letterOverrides ->
            // Derived once per snapshot, not per row — the letters depend on
            // the whole name set (common-prefix strip).
            val boxLetters = SessionTag.boxLetters(boxNames, letterOverrides)
            records.map { summary(it, boxNames, boxLetters) }
        }.conflate().collect { summaries ->
            emit(summaries)
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
        /// [boxNames] is the id → name map of the user's agent boxes. The chip
        /// gate lives here: fewer than two boxes means no chip on any row.
        fun summary(
            record: ConversationEntity,
            boxNames: Map<Long, String> = emptyMap(),
            boxLetters: Map<Long, String> = emptyMap(),
        ): ChatSummary {
            val activityMS = record.lastActivityTS ?: record.createdAt.takeIf { it > 0 }
            // The bridge bakes a `[bc] ` session short into earned titles —
            // peel it off so rows show the clean title and restyle the short
            // as part of the leading `A:bc` tag.
            val (sessionShort, cleanTitle) = SessionTag.splitTitle(record.title)
            val boxName = boxName(record, boxNames)
            val roomTags = roomTags(record, boxNames, boxLetters)
            return ChatSummary(
                id = record.id,
                title = cleanTitle.ifEmpty { record.id },
                bot = BotIdentity(matrixID = "agent:claude", displayName = "Claude", avatarURL = null),
                lastActivity = activityMS?.let { Instant.ofEpochMilli(it) },
                unreadCount = record.unreadCount,
                snippet = record.snippet,
                parentConvoID = record.parentConvoID,
                boxName = boxName,
                sessionShort = sessionShort,
                // Same gate as the chip: a letter only means something when
                // there is more than one box to tell apart.
                boxShort = if (boxName != null) record.agentDeviceID?.let(boxLetters::get) else null,
                roomBoxNames = roomTags.map { it.first },
                roomBoxShorts = roomTags.map { it.second },
            )
        }

        /// The tag for a multi-agent room: every participant id resolved to
        /// its box name AND display letter (name to letter pairs), deduped by
        /// name in journal order. Same two-box gate as [boxName] — one box
        /// means nothing to disambiguate. Empty unless at least two DISTINCT
        /// boxes resolve (a local room's two ends share one box, and a
        /// participant whose device was revoked resolves to nothing), so rows
        /// can fall back to the single-box tag. Ported from matron-apple's
        /// `JournalChatService.roomTags(for:boxNames:boxLetters:)`.
        fun roomTags(
            record: ConversationEntity?,
            boxNames: Map<Long, String>,
            boxLetters: Map<Long, String>,
        ): List<Pair<String, String>> {
            if (boxNames.size < 2) return emptyList()
            val ids = record?.participantIDs ?: return emptyList()
            if (ids.size < 2) return emptyList()
            val seen = mutableSetOf<String>()
            val tags = mutableListOf<Pair<String, String>>()
            for (id in ids) {
                val name = boxNames[id] ?: continue
                if (seen.add(name)) tags.add(name to (boxLetters[id] ?: "?"))
            }
            return if (tags.size >= 2) tags else emptyList()
        }

        /// The chip rule for a single conversation: named only when the user
        /// has two or more boxes AND this conversation's box resolves. Pure,
        /// so it is unit-testable without a live sync engine. Ported from
        /// matron-apple's `JournalChatService.boxName(for:boxNames:)`.
        fun boxName(record: ConversationEntity?, boxNames: Map<Long, String>): String? {
            if (boxNames.size < 2) return null
            return record?.agentDeviceID?.let(boxNames::get)
        }

        fun childSummary(record: ConversationEntity): SubChatSummary = SubChatSummary(
            id = record.id,
            title = record.title.ifEmpty { record.id },
            isRunning = SessionState.fromWire(record.sessionState) == SessionState.Running,
        )
    }
}
