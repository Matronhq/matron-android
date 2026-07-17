package chat.matron.android.journal

import androidx.room.withTransaction
import chat.matron.android.journal.db.ConversationEntity
import chat.matron.android.journal.db.EventEntity
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.journal.db.MetaEntity
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/// Thrown by the [JournalStore.failApplyForTesting] injection hook to simulate
/// a disk-full / SQLite I/O error without a real failing backend.
class JournalStoreWriteException : Exception("simulated write failure")

/// Local mirror of the user's journal. The UI reads ONLY this store; the sync
/// engine is the only writer. `cursor` advances inside the same transaction as
/// the event insert — the wedge-proof property. Ported method-for-method from
/// matron-apple's `JournalStore` (GRDB → Room).
///
/// Methods are `suspend` (Room DAO/transaction access is asynchronous), where
/// the Swift originals were synchronous over a serial `DatabaseQueue`.
class JournalStore(
    private val db: MatronDatabase,
    private val ownSender: String,
) {
    private val conversationDao = db.conversationDao()
    private val eventDao = db.eventDao()
    private val metaDao = db.metaDao()

    /// Test-only failure injection, checked before the transaction opens so the
    /// cursor is left untouched on a simulated failure — the same shape a real
    /// write failure takes.
    var failApplyForTesting: ((Long) -> Boolean)? = null

    // MARK: Cursor

    suspend fun cursor(): Long = metaDao.value(CURSOR_KEY)?.toLongOrNull() ?: 0

    // MARK: Snapshot

    suspend fun applyColdSnapshot(convos: List<ConvoSummaryDTO>, headSeq: Long) {
        db.withTransaction {
            for (c in convos) upsertSummary(c, resetLocalState = true)
            metaDao.upsert(MetaEntity(CURSOR_KEY, headSeq.toString()))
        }
    }

    suspend fun refreshSummaries(convos: List<ConvoSummaryDTO>) {
        db.withTransaction {
            for (c in convos) upsertSummary(c, resetLocalState = false)
        }
    }

    private suspend fun upsertSummary(c: ConvoSummaryDTO, resetLocalState: Boolean) {
        val existing = conversationDao.byId(c.id)
        if (existing != null) {
            var updated = existing.copy(title = c.title, sessionState = c.sessionState)
            // parent_convo_id is immutable once known: set only when this row
            // doesn't have one yet, never repointed or cleared.
            if (updated.parentConvoID == null && c.parentConvoID != null) {
                updated = updated.copy(parentConvoID = c.parentConvoID)
            }
            if (c.lastSeq > updated.lastSeq) {
                updated = updated.copy(lastSeq = c.lastSeq, snippet = c.snippet)
            }
            // Monotonic max so a stale snapshot can't roll a fresher live-frame
            // timestamp backwards; a missing last_ts leaves it alone.
            val ts = c.lastTS
            if (ts != null && ts > (updated.lastActivityTS ?: 0)) {
                updated = updated.copy(lastActivityTS = ts)
            }
            conversationDao.upsert(updated)
        } else {
            conversationDao.upsert(
                ConversationEntity(
                    id = c.id, title = c.title, sessionState = c.sessionState,
                    lastSeq = c.lastSeq, snippet = c.snippet, createdAt = c.createdAt,
                    lastActivityTS = c.lastTS, muted = false, hidden = false,
                    readUpToSeq = if (resetLocalState) c.lastSeq else 0,
                    unreadCount = 0, parentConvoID = c.parentConvoID,
                )
            )
        }
    }

    // MARK: Journal apply

    /// Applies one journal frame inside a single transaction. Returns `false`
    /// (a no-op) when `seq <= cursor` (a duplicate/replayed frame); otherwise
    /// inserts the event, updates the conversation summary, advances the cursor,
    /// and returns `true`.
    suspend fun applyJournal(event: JournalEvent): Boolean {
        if (failApplyForTesting?.invoke(event.seq) == true) throw JournalStoreWriteException()
        return db.withTransaction {
            val current = metaDao.value(CURSOR_KEY)?.toLongOrNull() ?: 0
            if (event.seq <= current) return@withTransaction false
            eventDao.insertReplace(EventEntity.from(event))

            var convo = conversationDao.byId(event.convoID) ?: ConversationEntity(
                id = event.convoID, title = "", sessionState = SessionState.RUNNING, lastSeq = 0,
                snippet = "", createdAt = event.ts.toEpochMilli(), lastActivityTS = null,
                muted = false, hidden = false, readUpToSeq = 0, unreadCount = 0, parentConvoID = null,
            )

            convo = convo.copy(lastSeq = max(convo.lastSeq, event.seq))
            // Only real message traffic counts as "activity" for the chat
            // list's timestamp; bookkeeping frames (read_marker, session_status,
            // convo_meta) must not fake aliveness. lastSeq still tracks every
            // frame (mirrors the server's last_seq for snapshot ordering).
            if (event.type in JournalEventType.MESSAGE_TYPES) {
                convo = convo.copy(lastActivityTS = event.ts.toEpochMilli())
            }

            val payload = event.payload
            when {
                event.type == JournalEventType.CONVO_META -> {
                    payload.stringOrNull("title")?.takeIf { it.isNotEmpty() }?.let {
                        convo = convo.copy(title = it)
                    }
                    // Learn the parent linkage once; immutable, never cleared by
                    // a later meta that omits it.
                    if (convo.parentConvoID == null) {
                        payload.stringOrNull("parent_convo_id")?.takeIf { it.isNotEmpty() }?.let {
                            convo = convo.copy(parentConvoID = it)
                        }
                    }
                }
                event.type == JournalEventType.SESSION_STATUS -> {
                    payload.stringOrNull("state")?.let { convo = convo.copy(sessionState = it) }
                }
                event.type == JournalEventType.READ_MARKER -> {
                    // All read_markers are the user's own (other devices included).
                    val upTo = payload.longOrNull("up_to_seq") ?: 0
                    val newRead = max(convo.readUpToSeq, upTo)
                    convo = convo.copy(
                        readUpToSeq = newRead,
                        unreadCount = eventDao.countUnread(
                            convo.id, newRead, JournalEventType.MESSAGE_TYPES, ownSender,
                        ),
                    )
                }
                event.type in JournalEventType.MESSAGE_TYPES -> {
                    convo = convo.copy(snippet = snippet(event.type, payload))
                    if (event.sender != ownSender && event.seq > convo.readUpToSeq) {
                        convo = convo.copy(unreadCount = convo.unreadCount + 1)
                    }
                }
            }
            conversationDao.upsert(convo)
            metaDao.upsert(MetaEntity(CURSOR_KEY, event.seq.toString()))
            true
        }
    }

    // MARK: History

    suspend fun insertHistory(events: List<JournalEvent>) {
        db.withTransaction {
            for (e in events) eventDao.insertIgnore(EventEntity.from(e))
            // Paginated rows can include unread messages (e.g. the refill after
            // a snapshot_required wipe). Live applyJournal counts unread
            // incrementally; recount here so the list doesn't under-report.
            for (convoID in events.map { it.convoID }.toSet()) {
                val convo = conversationDao.byId(convoID) ?: continue
                conversationDao.upsert(
                    convo.copy(
                        unreadCount = eventDao.countUnread(
                            convoID, convo.readUpToSeq, JournalEventType.MESSAGE_TYPES, ownSender,
                        ),
                    )
                )
            }
        }
    }

    // MARK: Reads

    /// `now` (epoch ms) is injectable for tests; production callers take the
    /// wall clock so every read reflects the current time (read-time TTL).
    suspend fun conversations(now: Long = System.currentTimeMillis()): List<ConversationEntity> =
        conversationDao.visibleTopLevel().map { applyReadTimeSnippetTTL(it, now) }

    suspend fun children(parentConvoID: String): List<ConversationEntity> =
        conversationDao.children(parentConvoID)

    suspend fun parentConvoID(convoID: String): String? = conversationDao.parentConvoID(convoID)

    /// Live parent linkage: emits again when convo_meta / a snapshot upsert
    /// teaches the mirror that [convoID] is a subagent child. Immutable once
    /// set, so consumers only ever see null → parent, never a repoint.
    fun parentConvoIDFlow(convoID: String): Flow<String?> =
        conversationDao.parentConvoIDFlow(convoID).distinctUntilChanged()

    suspend fun events(convoID: String): List<JournalEvent> =
        eventDao.forConversation(convoID).map { it.toJournalEvent() }

    suspend fun conversationExists(convoID: String): Boolean = conversationDao.exists(convoID)

    suspend fun minSeq(convoID: String): Long? = eventDao.minSeq(convoID)

    suspend fun maxSeq(convoID: String): Long? = eventDao.maxSeq(convoID)

    suspend fun setMuted(muted: Boolean, convoID: String) = conversationDao.setMuted(muted, convoID)

    suspend fun setHidden(hidden: Boolean, convoID: String) = conversationDao.setHidden(hidden, convoID)

    /// Inserts a placeholder conversation row for a convo id learned
    /// out-of-band (a `start` RPC answer that beat the convo's first journal
    /// frame). Never touches an existing row.
    suspend fun ensureConversation(id: String, title: String, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            if (conversationDao.byId(id) != null) return@withTransaction
            conversationDao.upsert(
                ConversationEntity(
                    id = id, title = title, sessionState = SessionState.RUNNING, lastSeq = 0,
                    snippet = "", createdAt = now, lastActivityTS = now, muted = false,
                    hidden = false, readUpToSeq = 0, unreadCount = 0, parentConvoID = null,
                )
            )
        }
    }

    suspend fun wipe() {
        db.withTransaction {
            eventDao.deleteAll()
            conversationDao.deleteAll()
            metaDao.deleteAll()
        }
    }

    // MARK: Observation
    //
    // Room's invalidation-tracker Flows are the ValueObservation analog. Unlike
    // GRDB's ValueObservation, a Room Flow can't error out (no observation
    // failure to self-heal from), so the Apple original's re-subscribe-on-error
    // wrapper is intentionally omitted.

    fun conversationsFlow(now: () -> Long = { System.currentTimeMillis() }): Flow<List<ConversationEntity>> =
        // Fresh now() per emission: a subscriber open a while still gets TTL
        // re-evaluated against current wall time. Re-fires on conversation-table
        // changes (which every meaningful event write also triggers via the
        // summary upsert).
        conversationDao.visibleTopLevelFlow().map { list -> list.map { applyReadTimeSnippetTTL(it, now()) } }

    fun childrenFlow(parentConvoID: String): Flow<List<ConversationEntity>> =
        conversationDao.childrenFlow(parentConvoID)

    fun eventsFlow(convoID: String): Flow<List<JournalEvent>> =
        eventDao.forConversationFlow(convoID).map { list -> list.map { it.toJournalEvent() } }

    // MARK: Tool-output TTL

    /// Rewrites every `tool_output` event payload with `live_log: true` older
    /// than 24h to the server's tombstone shape — snippet removed,
    /// `expired: true`, `blob_ref: null` — and, when the purged event is still
    /// the newest message-type event in its conversation, rewrites the
    /// conversation-list preview to `$ <command>`. Idempotent. `now` (epoch ms)
    /// is injectable for tests.
    ///
    /// Boot-time invocation is the composition root's responsibility (a Kotlin
    /// constructor can't suspend, unlike the Apple original's `init`).
    suspend fun purgeExpiredToolOutputSnippets(now: Long = System.currentTimeMillis()) {
        val cutoff = now - TTL_MS
        db.withTransaction {
            val rows = eventDao.ofTypeAtOrBefore(JournalEventType.TOOL_OUTPUT, cutoff)
            for (row in rows) {
                val payload = parseJsonObjectOrNull(row.payload) ?: continue
                if (payload.boolOrNull("live_log") != true) continue
                if (payload.boolOrNull("expired") == true) continue
                val tombstone = buildJsonObject {
                    for ((k, v) in payload) {
                        if (k != "snippet" && k != "expired" && k != "blob_ref") put(k, v)
                    }
                    put("expired", true)
                    put("blob_ref", JsonNull)
                }
                eventDao.updatePayload(row.seq, tombstone.toString())

                val command = payload.stringOrNull("command")
                if (command.isNullOrEmpty()) continue
                val convo = conversationDao.byId(row.convoID) ?: continue
                val newestMessageSeq = eventDao.newestMessageSeq(row.convoID, JournalEventType.MESSAGE_TYPES)
                if (newestMessageSeq == row.seq) {
                    conversationDao.upsert(convo.copy(snippet = "$ $command".take(120)))
                }
            }
        }
    }

    /// Read-time mirror of the tombstone rewrite, applied WITHOUT a write. An
    /// app left running past the 24h TTL must stop surfacing an expired
    /// `live_log` snippet in the list the next time it's read, even though the
    /// boot-time sweep only runs at startup. Only touches the in-memory record.
    private suspend fun applyReadTimeSnippetTTL(record: ConversationEntity, now: Long): ConversationEntity {
        val activityTS = record.lastActivityTS ?: return record
        val cutoff = now - TTL_MS
        if (activityTS > cutoff) return record
        val seq = eventDao.newestMessageSeq(record.id, JournalEventType.MESSAGE_TYPES) ?: return record
        val event = eventDao.byId(seq) ?: return record
        if (event.type != JournalEventType.TOOL_OUTPUT) return record
        val payload = parseJsonObjectOrNull(event.payload) ?: return record
        if (payload.boolOrNull("live_log") != true) return record
        if (payload.boolOrNull("expired") == true) return record
        val command = payload.stringOrNull("command")
        if (command.isNullOrEmpty()) return record
        return record.copy(snippet = "$ $command".take(120))
    }

    /// Mirrors the server's snippetOf (matron-journal src/journal.js).
    private fun snippet(type: String, payload: JsonObject): String = when (type) {
        JournalEventType.TEXT -> (payload.stringOrNull("body") ?: "").take(120)
        JournalEventType.PROMPT -> "? " + (payload.stringOrNull("question") ?: "").take(110)
        JournalEventType.PERMISSION_REQUEST ->
            "permission: " + (payload.stringOrNull("description") ?: "").take(100)
        else -> payload.stringOrNull("snippet")?.take(120) ?: "[$type]"
    }

    private companion object {
        const val CURSOR_KEY = "cursor"
        const val TTL_MS = 24L * 3600 * 1000
    }
}
