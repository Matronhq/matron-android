package chat.matron.android.journal

import androidx.room.withTransaction
import chat.matron.android.journal.db.AgentEntity
import chat.matron.android.events.SpawnOutcome
import chat.matron.android.journal.db.ConversationEntity
import chat.matron.android.journal.db.EventEntity
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.journal.db.MetaEntity
import chat.matron.android.journal.db.OutboxEntity
import chat.matron.android.journal.db.SummaryEntryEntity
import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonObject

/// Thrown by the [JournalStore.failApplyForTesting] injection hook to simulate
/// a disk-full / SQLite I/O error without a real failing backend.
class JournalStoreWriteException : Exception("simulated write failure")

/// The two [JournalStore] reads the per-chat media & links browser needs, as
/// an interface so tests fake the store. Port of apple #142's
/// `MediaBrowserStoreReading` (a retroactive protocol conformance there;
/// Kotlin has no retroactive conformance, so [JournalStore] implements it
/// directly).
interface MediaBrowserStoreReading {
    suspend fun attachmentEvents(convoID: String): List<JournalEvent>
    suspend fun linkCandidateEvents(convoID: String): List<JournalEvent>
}

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
) : MediaBrowserStoreReading {
    private val conversationDao = db.conversationDao()
    private val eventDao = db.eventDao()
    private val metaDao = db.metaDao()
    private val outboxDao = db.outboxDao()
    private val agentDao = db.agentDao()
    private val summaryEntryDao = db.summaryEntryDao()

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
            // Absent means "this server/row doesn't say", never "clear it" —
            // same discipline as parent_convo_id. Unlike parent, a PRESENT
            // value always wins: ownership legitimately moves between boxes.
            if (c.agentDeviceID != null) {
                updated = updated.copy(agentDeviceID = c.agentDeviceID)
            }
            // Same absent-never-clears rule: only a present membership array
            // replaces the stored one (a dissolved room's snapshot omits the
            // key, and the last-known chips are still the right tags).
            if (c.participants != null) {
                updated = updated.copy(participants = ConversationEntity.encodeParticipants(c.participants))
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
                    agentDeviceID = c.agentDeviceID,
                    participants = c.participants?.let(ConversationEntity::encodeParticipants),
                )
            )
        }
    }

    // MARK: Journal apply

    /// Applies one journal frame inside a single transaction. Returns `false`
    /// (a no-op) when `seq <= cursor` (a duplicate/replayed frame); otherwise
    /// inserts the event, updates the conversation summary, advances the cursor,
    /// and returns `true`. `now` (epoch ms) is the clock the insert-time
    /// tombstone rules run against — injectable for tests only.
    suspend fun applyJournal(event: JournalEvent, now: Long = System.currentTimeMillis()): Boolean {
        if (failApplyForTesting?.invoke(event.seq) == true) throw JournalStoreWriteException()
        return db.withTransaction { applyOneInTransaction(event, now) }
    }

    /// Applies a reconnect-replay batch inside ONE transaction (port of
    /// matron-apple #85): a catch-up burst previously committed — and
    /// re-triggered every Room observer — once per frame, making history
    /// load O(backlog) transactions. Returns the events actually applied
    /// (duplicates with `seq <= cursor` are skipped, same as [applyJournal])
    /// so the caller can index exactly those for search. A write failure
    /// mid-batch rolls the WHOLE batch back, leaving the cursor untouched —
    /// the same reconnect-from-cursor recovery shape as the per-frame path.
    suspend fun applyJournalBatch(
        events: List<JournalEvent>,
        now: Long = System.currentTimeMillis(),
    ): List<JournalEvent> {
        if (events.isEmpty()) return emptyList()
        return db.withTransaction {
            events.filter { event ->
                if (failApplyForTesting?.invoke(event.seq) == true) throw JournalStoreWriteException()
                applyOneInTransaction(event, now)
            }
        }
    }

    /// Per-event apply body shared by [applyJournal] and [applyJournalBatch].
    /// MUST be called inside an open transaction.
    private suspend fun applyOneInTransaction(event: JournalEvent, now: Long): Boolean {
        val current = metaDao.value(CURSOR_KEY)?.toLongOrNull() ?: 0
        if (event.seq <= current) return false
        // Stored tombstoned when it is already past a cutoff — see
        // [tombstonedForStorage]. The derived columns below read `stored`
        // so they describe what is on disk.
        val stored = tombstonedForStorage(event, now)
        eventDao.insertReplace(EventEntity.from(stored))
        SummaryEntryEntity.from(event)?.let { summaryEntryDao.insertIgnore(it) }

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

        val payload = stored.payload
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
                // Which box owns this conversation, learned live so a
                // brand-new convo chips immediately. Re-pointed freely: a
                // session resumed on another box changes owner.
                payload.longOrNull("agent_device_id")?.let {
                    convo = convo.copy(agentDeviceID = it)
                }
                // Room membership, learned live so a room re-chips the
                // moment an agent joins or leaves (the journal fans a
                // membership-only convo_meta). Present replaces wholesale;
                // absent (a plain rename meta) leaves the stored set alone.
                payload.longArrayOrNull("participants")?.let {
                    convo = convo.copy(participants = ConversationEntity.encodeParticipants(it))
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
                // `snippet` is computed from the ORIGINAL wire payload, never
                // the stored (possibly tombstoned) one: a message that expires
                // later keeps its `conversation.snippet` exactly as written —
                // the purge no longer rewrites it — and relies on
                // [applyReadTimeSnippetTTL] to hide it at read time for the
                // one type that TTL covers (`tool_output`). A message that
                // arrives ALREADY past its cutoff must behave identically
                // (in-place-expiry parity), not freeze the `[diff]`
                // placeholder [snippet]'s default case produces for a type it
                // has no case for. The two columns DO come from the stored
                // payload — they describe what's actually on disk, which is
                // what the read-time TTL substitutes in.
                convo = convo.copy(
                    snippet = snippet(event),
                    lastMessageType = event.type,
                    expiredSnippet = expiredSnippet(event.type, payload),
                )
                if (event.sender != ownSender && event.seq > convo.readUpToSeq) {
                    convo = convo.copy(unreadCount = convo.unreadCount + 1)
                }
            }
        }
        conversationDao.upsert(convo)
        metaDao.upsert(MetaEntity(CURSOR_KEY, event.seq.toString()))
        // Delivery confirmation for the offline outbox, in the SAME
        // transaction as the row insert: an own-text frame is a queued send
        // landing (body-match is the only signal — the server strips
        // idem_key from broadcast rows). Doing it here rather than as a
        // follow-up write means the confirming row and its outbox delete
        // commit or fail together, so a relaunch can never show a durable
        // duplicate echo beside the delivered message.
        if (event.sender == ownSender && event.type == JournalEventType.TEXT) {
            event.body()?.let { deleteFirstMatchingInTransaction(event.convoID, it) }
        }
        return true
    }

    // MARK: History

    /// `now` (epoch ms) is the clock the insert-time tombstone rules run
    /// against — injectable for tests only.
    suspend fun insertHistory(events: List<JournalEvent>, now: Long = System.currentTimeMillis()) {
        db.withTransaction {
            for (e in events) {
                eventDao.insertIgnore(EventEntity.from(tombstonedForStorage(e, now)))
                SummaryEntryEntity.from(e)?.let { summaryEntryDao.insertIgnore(it) }
            }
            // A post-snapshot refill can contain the frames that confirm
            // pre-wipe outbox sends: applyColdSnapshot jumps the cursor past
            // them, so applyJournal will never see them again and the rows
            // would re-flush (idem-dedup'd, so invisibly) on every reconnect
            // forever (bugbot "Post-snapshot outbox never confirms"). Run the
            // same confirmation-delete here, timestamp-guarded so genuinely
            // old history can't eat a fresh queued send.
            for (e in events) {
                if (e.sender != ownSender || e.type != JournalEventType.TEXT) continue
                e.body()?.let { deleteFirstMatchingInTransaction(e.convoID, it, journaledAtMs = e.ts.toEpochMilli()) }
            }
            // Paginated rows can include unread messages (e.g. the refill after
            // a snapshot_required wipe). Live applyJournal counts unread
            // incrementally; recount here so the list doesn't under-report.
            //
            // Backfilled rows can also become a conversation's newest
            // message-type event without moving `last_seq`, so the two TTL
            // columns are recomputed in the same pass — one indexed lookup
            // per touched conversation, exactly like the recount.
            for (convoID in events.map { it.convoID }.toSet()) {
                val convo = conversationDao.byId(convoID) ?: continue
                val columns = newestMessageColumns(convoID)
                conversationDao.upsert(
                    convo.copy(
                        unreadCount = eventDao.countUnread(
                            convoID, convo.readUpToSeq, JournalEventType.MESSAGE_TYPES, ownSender,
                        ),
                        lastMessageType = columns.first,
                        expiredSnippet = columns.second,
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

    /// Every conversation id, most recent activity first — the search
    /// backfill sweep's walk order (see [chat.matron.android.search.SearchBackfillCoordinator]).
    suspend fun allConversationIDs(): List<String> = conversationDao.allConversationIDs()

    suspend fun children(parentConvoID: String): List<ConversationEntity> =
        conversationDao.children(parentConvoID)

    suspend fun parentConvoID(convoID: String): String? = conversationDao.parentConvoID(convoID)

    /// Live parent linkage: emits again when convo_meta / a snapshot upsert
    /// teaches the mirror that [convoID] is a subagent child. Immutable once
    /// set, so consumers only ever see null → parent, never a repoint.
    fun parentConvoIDFlow(convoID: String): Flow<String?> =
        conversationDao.parentConvoIDFlow(convoID).distinctUntilChanged()

    /// Live durable turn state for one conversation — "running" flipped at turn
    /// start, "waiting"/"done" at turn end, via the `session_status` frames the
    /// mirror already applies. Unlike the ephemeral activity indicator (deduped
    /// by the bridge, swept after 30s quiet) this covers the WHOLE turn, so
    /// it can carry always-on affordances like the floating stop button. A row
    /// not yet mirrored reads as [SessionState.DONE] (nothing running).
    fun sessionStateFlow(convoID: String): Flow<String> =
        conversationDao.sessionStateFlow(convoID)
            .map { it ?: SessionState.DONE }
            .distinctUntilChanged()

    suspend fun events(convoID: String): List<JournalEvent> =
        eventDao.forConversation(convoID).map { it.toJournalEvent() }

    /// One conversation by id, or null when this device has never seen it.
    /// (Port of matron-apple's `conversation(id:)`, added for the box-name
    /// resolution tests — the list reads go through [conversations].)
    suspend fun conversation(id: String): ConversationEntity? = conversationDao.byId(id)
    /// `image`/`file` events for one conversation, newest first — the
    /// media & links browser's Media and Files tabs. Reads the full local
    /// history: the timeline's 120-row window cannot see older attachments.
    /// Port of apple #142's `attachmentEvents`.
    override suspend fun attachmentEvents(convoID: String): List<JournalEvent> =
        eventDao.ofTypesNewestFirst(convoID, listOf(JournalEventType.IMAGE, JournalEventType.FILE))
            .map { it.toJournalEvent() }

    /// `text` events that plausibly contain a URL, newest first — a cheap
    /// SQL prefilter; precise extraction happens in Kotlin (`LinkExtractor`).
    /// Port of apple #142's `linkCandidateEvents`.
    override suspend fun linkCandidateEvents(convoID: String): List<JournalEvent> =
        eventDao.textEventsContainingNewestFirst(convoID, "http").map { it.toJournalEvent() }

    suspend fun conversationExists(convoID: String): Boolean = conversationDao.exists(convoID)

    /// TOC entries for one conversation, newest first — the summaries sheet's
    /// one-shot read. Port of the Apple `summaryEntries(convoID:)`.
    suspend fun summaryEntries(convoID: String): List<SummaryEntryEntity> =
        summaryEntryDao.forConversation(convoID)

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

    /// Clears the journal mirror (events, conversations, cursor) but NOT the
    /// outbox: this runs on `snapshot_required` (replay gap too large), and a
    /// mirror wipe must not eat the user's unsent messages. Sign-out calls
    /// [wipeOutbox] separately.
    suspend fun wipe() {
        db.withTransaction {
            eventDao.deleteAll()
            conversationDao.deleteAll()
            metaDao.deleteAll()
            summaryEntryDao.deleteAll()
            // The roster goes too: a `snapshot_required` re-snapshot refills it
            // in the same pass, and a server that predates `agents` must not
            // keep stale box names holding chips (and the ≥2-boxes gate) open
            // against an otherwise-empty mirror (Bugbot, #38).
            agentDao.deleteAll()
        }
    }

    // MARK: Outbox

    /// Enqueues one unsent text message. Idempotent on [localID] so a retry
    /// racing the original insert can't duplicate the row.
    suspend fun outboxInsert(localID: String, convoID: String, body: String, now: Long = System.currentTimeMillis()) {
        outboxDao.insertIgnore(
            OutboxEntity(
                localID = localID, convoID = convoID, body = body, createdAt = now,
                state = OutboxEntity.STATE_QUEUED, attempts = 0, lastError = null,
            )
        )
    }

    /// Every queued row across all conversations, oldest first — the flush
    /// order. Failed rows are excluded: they only move again via an explicit
    /// user retry ([outboxRequeue]).
    suspend fun outboxPending(): List<OutboxEntity> = outboxDao.pending()

    /// All outbox rows for one conversation (queued AND failed), oldest first —
    /// what the timeline renders as pending/failed echoes.
    suspend fun outboxRows(convoID: String): List<OutboxEntity> = outboxDao.forConversation(convoID)

    suspend fun outboxRow(localID: String): OutboxEntity? = outboxDao.row(localID)

    suspend fun outboxMarkAttempt(localID: String) = outboxDao.markAttempt(localID)

    suspend fun outboxMarkFailed(localID: String, error: String?) = outboxDao.markFailed(localID, error)

    /// Puts a failed row back in the flush set (tap-to-retry).
    suspend fun outboxRequeue(localID: String) = outboxDao.requeue(localID)

    suspend fun outboxDelete(localID: String) = outboxDao.delete(localID)

    /// Delivery confirmation: an own-text journal frame with [body] landed for
    /// [convoID] — delete the OLDEST attempted row with that body and return
    /// its `localID` (null when nothing matches). The server strips the
    /// idem_key from broadcast rows, so body-match is the only signal (mirrors
    /// the echo-suppression heuristic in `JournalTimelineService`). Only rows
    /// with `attempts > 0` qualify: a never-sent row can't be the one the frame
    /// confirms — deleting it would silently eat a message that never went out
    /// (e.g. the same text sent from another device). Queued rows are preferred
    /// over failed ones ("prefer a pending echo so a delivered copy's ack can't
    /// retire an undelivered one — but when only a failed copy matches, this
    /// own-row IS its successful retry landing").
    ///
    /// [applyJournal] runs the same deletion INSIDE its own transaction so a
    /// confirming row and its outbox delete commit atomically. This public
    /// wrapper remains for tests and non-transactional callers.
    suspend fun outboxDeleteFirstMatching(convoID: String, body: String): String? =
        db.withTransaction { deleteFirstMatchingInTransaction(convoID, body) }

    /// [journaledAtMs] (the confirming event's server timestamp), when given,
    /// restricts candidates to rows created at or before it: a history event
    /// can only confirm a send that already existed when the server journaled
    /// it, so an OLD identical message replayed by pagination can't delete a
    /// fresh queued send.
    private suspend fun deleteFirstMatchingInTransaction(
        convoID: String,
        body: String,
        journaledAtMs: Long? = null,
    ): String? {
        val candidates = outboxDao.matching(convoID, body)
            .filter { journaledAtMs == null || it.createdAt <= journaledAtMs }
        val row = candidates.firstOrNull { it.state == OutboxEntity.STATE_QUEUED }
            ?: candidates.firstOrNull()
            ?: return null
        outboxDao.delete(row.localID)
        return row.localID
    }

    /// Sign-out hygiene: the next account on this database file must not
    /// inherit (or send) the previous user's queued messages.
    suspend fun wipeOutbox() = outboxDao.deleteAll()

    // MARK: Agent roster

    /// Mirrors `GET /snapshot`'s `agents` list. Wholesale replace so a box
    /// revoked server-side stops resolving here too. Presence-aware
    /// (diverging from the Swift original, which ignores an empty list):
    /// `null` means the server predates the field — keep what we have,
    /// wiping would silently drop every chip; an EMPTY list is the server
    /// saying the user has no boxes — clear the roster, or revoking the
    /// last box would leave stale chips forever.
    suspend fun replaceAgents(agents: List<AgentDTO>?) {
        if (agents == null) return
        db.withTransaction {
            // A snapshot from a server predating tags says nothing about
            // them: keep each row's standing letter rather than wiping a
            // migration-seeded one on every snapshot (apple #158).
            val standing = agentDao.all().associate { it.id to it.tagChar }
            agentDao.deleteAll()
            for (a in agents) {
                val tag = if (a.tagCharKnown) a.tagChar else standing[a.id]
                agentDao.upsert(AgentEntity(id = a.id, name = a.name, tagChar = tag))
            }
        }
    }

    /// Applies one live `device_meta` frame: the name always, the tag only
    /// when the frame carried the key. Update-only like [renameAgent].
    suspend fun applyDeviceMeta(id: Long, name: String, tagChar: String?, tagCharKnown: Boolean) =
        agentDao.applyMeta(id, name, tagChar, tagCharKnown)

    /// Fills tag letters for known ids whose row has none — the legacy local
    /// override migration's seed, so the letter shows before the push's
    /// `device_meta` echo lands. Only NULL rows are touched.
    suspend fun seedAgentTagChars(tags: Map<Long, String>) {
        db.withTransaction {
            for (row in agentDao.all()) {
                if (row.tagChar != null) continue
                val tag = tags[row.id] ?: continue
                agentDao.upsert(row.copy(tagChar = tag))
            }
        }
    }

    /// id → journal-held tag character for every box that has one.
    suspend fun agentTags(): Map<Long, String> =
        agentDao.all().mapNotNull { row -> row.tagChar?.let { row.id to it } }.toMap()

    /// Live roster (names and tags together, in lockstep) — the chat list
    /// derives box letters from both.
    fun agentRosterFlow(): Flow<List<AgentEntity>> = agentDao.allFlow()

    /// Applies one live `device_meta` rename. Update-only, NOT an upsert
    /// (deliberate divergence from matron-apple, which upserts): the frame
    /// carries only `device_id` + `name` and the server fans it out for ANY
    /// device kind, so renaming a phone would otherwise insert a client into
    /// the agent roster and flip the ≥2-boxes chip gate for a single-box
    /// user. An id not in the table is ignored — a genuinely new box gets
    /// its name from the next `agents` snapshot instead.
    suspend fun renameAgent(id: Long, name: String) = agentDao.rename(id, name)

    /// id → name for every known box. The chat list joins against this to
    /// label rows, and its COUNT is the "does this user have ≥2 boxes" gate.
    suspend fun agentNames(): Map<Long, String> = agentDao.all().associate { it.id to it.name }

    /// Live id → name map of the user's agent boxes. Deliberately separate
    /// from [conversationsFlow]: Room's invalidation tracker only re-fires a
    /// Flow for the tables its query reads, and the conversations query never
    /// touches `agent` — so a `device_meta` rename landing mid-session would
    /// otherwise leave every open chip on the old label until some unrelated
    /// conversation write happened to re-fire the list.
    fun agentNamesFlow(): Flow<Map<Long, String>> =
        agentDao.allFlow().map { list -> list.associate { it.id to it.name } }

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
        // The query reads ONLY the `conversation` table: the TTL is pure
        // column logic (see applyReadTimeSnippetTTL), so Room's invalidation
        // tracker re-fires the list for conversation-row writes alone.
        conversationDao.visibleTopLevelFlow().map { list -> list.map { applyReadTimeSnippetTTL(it, now()) } }

    fun childrenFlow(parentConvoID: String): Flow<List<ConversationEntity>> =
        conversationDao.childrenFlow(parentConvoID)

    fun eventsFlow(convoID: String): Flow<List<JournalEvent>> =
        eventDao.forConversationFlow(convoID).map { list -> list.map { it.toJournalEvent() } }

    /// The conversation's rows at or after [sinceSeq], observed. Deduplicated:
    /// Room re-fires on every commit touching the table, and most of those
    /// change nothing in this window (apple #171).
    fun eventsFlow(convoID: String, sinceSeq: Long): Flow<List<JournalEvent>> =
        eventDao.forConversationSinceFlow(convoID, sinceSeq)
            .map { list -> list.map { it.toJournalEvent() } }
            .distinctUntilChanged()

    /// Where a tail window of [limit] rows starts: the seq of the [limit]-th
    /// newest row, or 0 when the conversation holds fewer (so the window is
    /// the whole history).
    suspend fun tailWindowStart(convoID: String, limit: Int): Long =
        eventDao.seqAtNewestOffset(convoID, (limit - 1).coerceAtLeast(0)) ?: 0L

    /// Up to [limit] rows strictly older than [beforeSeq], ascending — the
    /// local page a backward paginate reveals before it reaches for the
    /// network.
    suspend fun eventsBefore(convoID: String, beforeSeq: Long, limit: Int): List<JournalEvent> =
        eventDao.beforeSeqNewestFirst(convoID, beforeSeq, limit).asReversed().map { it.toJournalEvent() }

    /// Live stream of one conversation's outbox rows (queued + failed, oldest
    /// first). The timeline renders these as pending/failed echoes; re-fires on
    /// enqueue, state change, and delivery-confirmed delete.
    fun outboxFlow(convoID: String): Flow<List<OutboxEntity>> = outboxDao.forConversationFlow(convoID)

    /// Live stream of one conversation's TOC entries, newest first. Port of the
    /// Apple `summaryEntriesStream(convoID:)` (ValueObservation → Room Flow).
    fun summaryEntriesFlow(convoID: String): Flow<List<SummaryEntryEntity>> =
        summaryEntryDao.forConversationFlow(convoID)

    // MARK: Background maintenance sweeps

    /// Rewrites aged-out `tool_output` payloads to the tombstone shape,
    /// incrementally: everything at or below `meta.snippet_ttl_ts` was
    /// covered by an earlier sweep and is skipped, and the range scan uses
    /// the `event_type_ts` index rather than reading the whole table.
    ///
    /// Same name and signature as the boot-time sweep it replaces — the
    /// difference is that nothing calls it from the composition root at
    /// store creation any more (`JournalMaintenance` owns it, off the launch
    /// path). The first run after the update has no watermark and therefore
    /// scans every tool-output row older than 24 h once, in the background.
    /// `now` (epoch ms) is injectable for tests.
    suspend fun purgeExpiredToolOutputSnippets(now: Long = System.currentTimeMillis()) {
        sweepTombstones(
            types = listOf(JournalEventType.TOOL_OUTPUT),
            watermarkKey = SNIPPET_TTL_WATERMARK_KEY,
            cutoffMs = now - EventTombstone.TOOL_LOG_TTL_MS,
            now = now,
        )
    }

    /// Local retention (spec §3.4 / §4 decision 1): tool-output and diff
    /// BODIES older than 30 days are tombstoned on this device. The server
    /// still has them; recovering them locally means a wipe + re-sync, which
    /// is the existing `snapshot_required` path.
    ///
    /// Returns every `tool_output`/`diff` seq this pass VISITED inside the
    /// retention range — not just the ones it rewrote. A row the 24 h sweep
    /// already tombstoned is typically a no-op for the 30-day rule, so it
    /// would never appear in a rewrite-only list — but the watermark
    /// guarantees exactly one visit, so this is the caller's one chance to
    /// learn about it. Search retirement runs off its own watermark
    /// ([pendingSearchRetirements]); this return value is reported, not
    /// anyone's only path to the index.
    suspend fun applyRetention(now: Long = System.currentTimeMillis()): List<Long> =
        sweepTombstones(
            types = listOf(JournalEventType.TOOL_OUTPUT, JournalEventType.DIFF),
            watermarkKey = RETENTION_WATERMARK_KEY,
            cutoffMs = now - EventTombstone.RETENTION_WINDOW_MS,
            now = now,
            returnAllVisited = true,
        )

    /// The shared sweep engine: walk `(type, ts)` forward from the watermark
    /// to [cutoffMs] in chunks of [SWEEP_CHUNK_SIZE] rows per write
    /// transaction (so UI reads interleave), rewrite what [EventTombstone]
    /// changes, then move the watermark to the cutoff.
    ///
    /// Cancellation is observed at chunk boundaries: a cancelled sweep
    /// leaves the chunks already committed (idempotent, durable) and skips
    /// the watermark write, so the next call resumes over the same range.
    /// `JournalMaintenance.stop()` relies on this to await an in-flight pass
    /// instead of waiting one out.
    private suspend fun sweepTombstones(
        types: List<String>,
        watermarkKey: String,
        cutoffMs: Long,
        now: Long,
        returnAllVisited: Boolean = false,
    ): List<Long> {
        val tombstoned = mutableListOf<Long>()
        val visited = mutableListOf<Long>()
        var afterTS = metaDao.value(watermarkKey)?.toLongOrNull() ?: 0L
        // A persisted watermark can sit ABOVE this call's own cutoff (an
        // injected or stepped clock in tests, or a caller stepping `now`
        // backwards). A watermark only certifies the range it was computed
        // against, so treat "past our cutoff" as no coverage for THIS range
        // rather than letting it blind the scan.
        if (afterTS > cutoffMs) afterTS = 0L
        // `Long.MAX_VALUE` on the first page makes the seed behave as
        // `ts > watermark`, so a row exactly at the watermark is not re-swept.
        var afterSeq = Long.MAX_VALUE
        while (true) {
            if (!currentCoroutineContext().isActive) return if (returnAllVisited) visited else tombstoned
            val chunk = db.withTransaction {
                val rows = eventDao.sweepPage(types, cutoffMs, afterTS, afterSeq, SWEEP_CHUNK_SIZE)
                val touched = mutableSetOf<String>()
                for (row in rows) {
                    visited += row.seq
                    val payload = parseJsonObjectOrNull(row.payload) ?: continue
                    val rewritten = EventTombstone.apply(payload, row.type, row.ts, now) ?: continue
                    eventDao.updatePayload(row.seq, rewritten.toString())
                    tombstoned += row.seq
                    touched += row.convoID
                }
                // A tombstoned row can be its conversation's newest message —
                // and a payload that was never a live log had no
                // `expired_snippet` at insert time, so the list would keep
                // showing a body that is no longer on disk. One indexed
                // lookup per touched conversation, and no write at all when
                // the columns already agree (so the chat-list observation
                // does not re-fire for a sweep that changed nothing it shows).
                for (convoID in touched) refreshLastMessageColumns(convoID)
                rows
            }
            val last = chunk.lastOrNull() ?: break
            afterTS = last.ts
            afterSeq = last.seq
        }
        metaDao.upsert(MetaEntity(watermarkKey, cutoffMs.toString()))
        return if (returnAllVisited) visited else tombstoned
    }

    /// Recomputes `last_message_type` / `expired_snippet` for one
    /// conversation, writing only when a value actually changed.
    private suspend fun refreshLastMessageColumns(convoID: String) {
        val convo = conversationDao.byId(convoID) ?: return
        val (type, expiredSnippet) = newestMessageColumns(convoID)
        if (convo.lastMessageType == type && convo.expiredSnippet == expiredSnippet) return
        conversationDao.setLastMessageColumns(convoID, type, expiredSnippet)
    }

    /// When the maintenance sweeps last completed a full pass (epoch ms), or
    /// `null` when none has — the Settings › Storage "Last maintenance" row,
    /// and the scheduler's due-check.
    suspend fun maintenanceLastRun(): Long? = metaDao.value(MAINTENANCE_LAST_RUN_KEY)?.toLongOrNull()

    suspend fun recordMaintenanceRun(at: Long) = metaDao.upsert(MetaEntity(MAINTENANCE_LAST_RUN_KEY, at.toString()))

    /// `tool_output`/`diff` seqs whose bodies have aged past the retention
    /// window and have not yet been retired from the search index, plus the
    /// cutoff this call actually finished scanning up to.
    ///
    /// A read-only sibling of the retention sweep over the same
    /// `event_type_ts` range and the same 30-day cutoff, but gated on its
    /// own `search_retention_ts` watermark rather than `retention_ts`: the
    /// tombstone sweep runs whether or not a search index is attached, and
    /// sharing one watermark would let a pass with no search silently skip
    /// rows past that nothing ever removed from the index. Paged the same
    /// way so a large backlog doesn't hold one long read.
    ///
    /// On cancellation the returned cutoff is the watermark the scan started
    /// from, so a caller persisting it via [recordSearchRetirement] writes
    /// back exactly what was already there and the next call re-scans the
    /// same, still-outstanding range. (A cutoff derived from the last row
    /// seen is unsafe: a full chunk never proves every same-millisecond
    /// sibling was fetched.)
    suspend fun pendingSearchRetirements(now: Long = System.currentTimeMillis()): SearchRetirements {
        val cutoffMs = now - EventTombstone.RETENTION_WINDOW_MS
        val types = listOf(JournalEventType.TOOL_OUTPUT, JournalEventType.DIFF)
        var afterTS = metaDao.value(SEARCH_RETENTION_WATERMARK_KEY)?.toLongOrNull() ?: 0L
        if (afterTS > cutoffMs) afterTS = 0L
        val startTS = afterTS
        var afterSeq = Long.MAX_VALUE
        val seqs = mutableListOf<Long>()
        while (true) {
            if (!currentCoroutineContext().isActive) return SearchRetirements(seqs, startTS)
            val chunk = eventDao.sweepPageKeys(types, cutoffMs, afterTS, afterSeq, SWEEP_CHUNK_SIZE)
            val last = chunk.lastOrNull() ?: break
            seqs += chunk.map { it.seq }
            afterTS = last.ts
            afterSeq = last.seq
        }
        return SearchRetirements(seqs, cutoffMs)
    }

    /// Advances the search-retention watermark. Callers must only invoke
    /// this after `SearchService.removeAll` has actually succeeded for the
    /// seqs that came with this cutoff from [pendingSearchRetirements].
    suspend fun recordSearchRetirement(upTo: Long) =
        metaDao.upsert(MetaEntity(SEARCH_RETENTION_WATERMARK_KEY, upTo.toString()))

    /// Row counts for the Settings › Storage section. On demand only, never
    /// on the launch path; SQLite answers `COUNT(*)` from the smallest
    /// covering index, so this is an index-only scan.
    suspend fun rowCounts(): RowCounts = RowCounts(events = eventDao.count(), conversations = conversationDao.count())

    /// The form of [event] that actually goes to disk: a `tool_output` or
    /// `diff` that is ALREADY past one of [EventTombstone]'s cutoffs when it
    /// arrives is stored tombstoned, never in full.
    ///
    /// This is what makes the sweeps' watermarks complete. A sweep skips
    /// everything at or below its watermark, so a row older than that can
    /// only be correct if the two insert paths applied the identical rule on
    /// the way in — which is why both of them, and both sweeps, call
    /// [EventTombstone.apply] and nothing else.
    private fun tombstonedForStorage(event: JournalEvent, now: Long): JournalEvent {
        val rewritten = EventTombstone.apply(event.payload, event.type, event.ts.toEpochMilli(), now) ?: return event
        return event.copy(payload = rewritten)
    }

    /// The newest message-type event's derived facts for [convoID] as
    /// `(lastMessageType, expiredSnippet)`, or `(null, null)` when the
    /// conversation has no message-type event. One indexed lookup on
    /// `convo_id`; called only from write paths, never from a read.
    private suspend fun newestMessageColumns(convoID: String): Pair<String?, String?> {
        val row = eventDao.newestMessageEvent(convoID, JournalEventType.MESSAGE_TYPES) ?: return null to null
        return row.type to expiredSnippet(row.type, parseJsonObjectOrNull(row.payload))
    }

    /// Read-time mirror of the tool-output tombstone, applied WITHOUT a
    /// write and WITHOUT reading `event`.
    ///
    /// An app left running past the 24 h tool-output TTL (docs/protocol.md
    /// Retention) must stop surfacing an expired `live_log` snippet in the
    /// conversation list the next time it is read, exactly as
    /// `JournalTimelineMapper` already hides it in the open thread. Before
    /// v7 that answer came from a `MAX(seq)` sub-query plus an event fetch
    /// per stale conversation — two `event` reads per stale row on every
    /// list read. Both facts now live on the conversation row, maintained on
    /// write ([applyJournal], [insertHistory], and the sweeps), so the list
    /// read is one `conversation` query regardless of how stale it is.
    private fun applyReadTimeSnippetTTL(record: ConversationEntity, now: Long): ConversationEntity {
        if (record.lastMessageType != JournalEventType.TOOL_OUTPUT) return record
        val expiredSnippet = record.expiredSnippet ?: return record
        val activityTS = record.lastActivityTS ?: return record
        if (activityTS > now - TTL_MS) return record
        return record.copy(snippet = expiredSnippet)
    }

    /// Mirrors the server's snippetOf (matron-journal src/journal.js).
    private fun snippet(event: JournalEvent): String = when (event.type) {
        JournalEventType.TEXT -> (event.body() ?: "").take(120)
        JournalEventType.PROMPT -> "? " + (event.payload.stringOrNull("question") ?: "").take(110)
        JournalEventType.PERMISSION_REQUEST ->
            // The agent-chat/agent-spawn consent cards carry no `description`,
            // so the generic branch produced a bare "permission: " in the chat
            // list — and disagreed with the server, whose snippetOf returns
            // these strings for the same events. A snapshot and a live frame
            // must not render the same row two different ways.
            when (event.payload.stringOrNull("kind")) {
                "agent_chat" -> "🤝 Agent chat request"
                "agent_spawn" -> "🤝 Agent spawn request"
                else -> "permission: " + (event.payload.stringOrNull("description") ?: "").take(100)
            }
        // baseSnippet, NOT displayLine: this path also renders snapshot rows
        // whose snippet the server itself minted via its byte-exact
        // snippetOf (bare "❌ Spawn failed", "[spawn_outcome]" for an
        // unrecognised outcome) — displayLine's errorCode suffix and neutral
        // "resolved" copy are for the live-mapped timeline row only, and
        // would flip-flop this row between renders if used here.
        JournalEventType.SPAWN_OUTCOME ->
            SpawnOutcome.parse(event.payload)?.let { SpawnOutcome.baseSnippet(it.outcome) }
                ?: (event.snippet()?.take(120) ?: "[${event.type}]")
        else -> event.snippet()?.take(120) ?: "[${event.type}]"
    }

    /// Result of [pendingSearchRetirements]: the seqs to retire from the
    /// search index and the cutoff (epoch ms) to record once that succeeded.
    data class SearchRetirements(val seqs: List<Long>, val cutoffMs: Long)

    data class RowCounts(val events: Int, val conversations: Int)

    companion object {
        private const val CURSOR_KEY = "cursor"
        private const val TTL_MS = EventTombstone.TOOL_LOG_TTL_MS

        /// `meta` keys written by the sweeps. None is written by a migration;
        /// [wipe]'s `DELETE FROM meta` resets all four, which is exactly
        /// right — a re-bootstrapped mirror must re-sweep from scratch.
        internal const val SNIPPET_TTL_WATERMARK_KEY = "snippet_ttl_ts"
        internal const val RETENTION_WATERMARK_KEY = "retention_ts"
        internal const val SEARCH_RETENTION_WATERMARK_KEY = "search_retention_ts"
        internal const val MAINTENANCE_LAST_RUN_KEY = "maintenance_last_run"

        /// Rows per sweep write transaction. A sweep that took one
        /// transaction for the whole range would hold Room's single write
        /// connection for its duration; 500 keeps each transaction short
        /// enough for UI reads to interleave.
        internal const val SWEEP_CHUNK_SIZE = 500

        /// The chat-list preview a tool_output falls back to once its output
        /// is gone — the server's own `"$ <command>"` shape, capped at the
        /// same 120 characters as [snippet].
        ///
        /// Returns `null` unless the payload is a tool_output that is either
        /// a live log (the only shape the 24 h TTL applies to) or already
        /// tombstoned (`expired: true`, server-side or by the retention
        /// sweep). A legacy/offloaded tool_output with a durable snippet and
        /// no `live_log` keeps showing that snippet forever, which is the
        /// behaviour `purgeLeavesYoungAndNonLiveLogRows` pins. Shared by the
        /// v7 migration backfill and the write path.
        internal fun expiredSnippet(type: String, payload: JsonObject?): String? {
            if (type != JournalEventType.TOOL_OUTPUT || payload == null) return null
            if (payload.boolOrNull("live_log") != true && payload.boolOrNull("expired") != true) return null
            val command = payload.stringOrNull("command")?.takeIf { it.isNotEmpty() } ?: return null
            return "$ $command".take(120)
        }
    }
}
