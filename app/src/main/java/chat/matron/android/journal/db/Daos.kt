package chat.matron.android.journal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(convo: ConversationEntity)

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun byId(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversation WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT parent_convo_id FROM conversation WHERE id = :id")
    suspend fun parentConvoID(id: String): String?

    @Query("SELECT parent_convo_id FROM conversation WHERE id = :id")
    fun parentConvoIDFlow(id: String): Flow<String?>

    @Query("SELECT session_state FROM conversation WHERE id = :id")
    fun sessionStateFlow(id: String): Flow<String?>

    /// Chat-list query: visible, top-level (no parent), newest first.
    /// Ordered by `last_activity_ts` (bumped only for MESSAGE_TYPES, see
    /// JournalStore.applyJournal) rather than `last_seq` (bumped for every
    /// frame incl. read_marker/session_status) so a bookkeeping frame from
    /// another device can't float a stale chat to the top. `last_seq` is only
    /// a tiebreak (e.g. rows sharing a null last_activity_ts). This is a
    /// deliberate divergence from matron-apple's GRDB query, which orders by
    /// `last_seq` alone (same latent flaw, left unfixed there) — don't "fix"
    /// this back to match it during a future parity audit.
    @Query(
        "SELECT * FROM conversation WHERE hidden = 0 AND parent_convo_id IS NULL " +
            "ORDER BY last_activity_ts DESC, last_seq DESC"
    )
    suspend fun visibleTopLevel(): List<ConversationEntity>

    @Query(
        "SELECT * FROM conversation WHERE hidden = 0 AND parent_convo_id IS NULL " +
            "ORDER BY last_activity_ts DESC, last_seq DESC"
    )
    fun visibleTopLevelFlow(): Flow<List<ConversationEntity>>

    /// EVERY conversation id — hidden rows and subagent children included
    /// (their history is searchable, so the backfill sweep must cover them).
    /// Activity ordering indexes the conversations the user is most likely to
    /// search before the long tail. Ported from matron-apple's
    /// `JournalStore.allConversationIDs`.
    @Query("SELECT id FROM conversation ORDER BY last_activity_ts DESC, last_seq DESC")
    suspend fun allConversationIDs(): List<String>

    @Query("SELECT * FROM conversation WHERE parent_convo_id = :parentConvoID ORDER BY created_at ASC, id ASC")
    suspend fun children(parentConvoID: String): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE parent_convo_id = :parentConvoID ORDER BY created_at ASC, id ASC")
    fun childrenFlow(parentConvoID: String): Flow<List<ConversationEntity>>

    @Query("UPDATE conversation SET muted = :muted WHERE id = :convoID")
    suspend fun setMuted(muted: Boolean, convoID: String)

    @Query("UPDATE conversation SET hidden = :hidden WHERE id = :convoID")
    suspend fun setHidden(hidden: Boolean, convoID: String)

    @Query("DELETE FROM conversation")
    suspend fun deleteAll()

    /// Every conversation's title beside its box name (a LEFT JOIN against
    /// `agent`), for the tracker's "All" scope origin labels.
    @Query(
        "SELECT conversation.id AS id, conversation.title AS title, agent.name AS agentName " +
            "FROM conversation LEFT JOIN agent ON agent.id = conversation.agent_device_id"
    )
    suspend fun originLabelRows(): List<OriginLabelRow>

    @Query(
        "SELECT conversation.id AS id, conversation.title AS title, agent.name AS agentName " +
            "FROM conversation LEFT JOIN agent ON agent.id = conversation.agent_device_id WHERE conversation.id = :id"
    )
    suspend fun originLabelRow(id: String): OriginLabelRow?
}

/// Projection of [ConversationDao.originLabelRows].
data class OriginLabelRow(val id: String, val title: String, val agentName: String?)

@Dao
interface AgentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: AgentEntity)

    /// Update-only (no insert): the live `device_meta` path must not create
    /// roster rows — see `JournalStore.renameAgent`. A no-op for unknown ids.
    @Query("UPDATE agent SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    /// Applies a `device_meta` frame: the name always, the tag only when the
    /// frame carried the key (`known`) — a server predating tags omits it,
    /// and that must not wipe a standing letter on a plain rename.
    @Query("UPDATE agent SET name = :name, tag_char = CASE WHEN :known THEN :tagChar ELSE tag_char END WHERE id = :id")
    suspend fun applyMeta(id: Long, name: String, tagChar: String?, known: Boolean)

    @Query("SELECT * FROM agent")
    suspend fun all(): List<AgentEntity>

    @Query("SELECT * FROM agent")
    fun allFlow(): Flow<List<AgentEntity>>

    @Query("DELETE FROM agent")
    suspend fun deleteAll()
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplace(event: EventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: EventEntity)

    @Query("SELECT * FROM event WHERE seq = :seq")
    suspend fun byId(seq: Long): EventEntity?

    @Query("SELECT * FROM event WHERE convo_id = :convoID ORDER BY seq")
    suspend fun forConversation(convoID: String): List<EventEntity>

    @Query("SELECT * FROM event WHERE convo_id = :convoID ORDER BY seq")
    fun forConversationFlow(convoID: String): Flow<List<EventEntity>>

    /// The tail window: rows at or after [sinceSeq]. Observed instead of the
    /// whole conversation so a store-wide commit re-reads a bounded page,
    /// not the entire history (apple #171).
    @Query("SELECT * FROM event WHERE convo_id = :convoID AND seq >= :sinceSeq ORDER BY seq")
    fun forConversationSinceFlow(convoID: String, sinceSeq: Long): Flow<List<EventEntity>>

    /// The seq of the [offset]-th newest row (0-based), or null when the
    /// conversation holds fewer rows — the anchor a tail window starts at.
    @Query("SELECT seq FROM event WHERE convo_id = :convoID ORDER BY seq DESC LIMIT 1 OFFSET :offset")
    suspend fun seqAtNewestOffset(convoID: String, offset: Int): Long?

    /// A page of rows strictly older than [beforeSeq], newest first, for the
    /// local reveal of history the tail window doesn't observe.
    @Query("SELECT * FROM event WHERE convo_id = :convoID AND seq < :beforeSeq ORDER BY seq DESC LIMIT :limit")
    suspend fun beforeSeqNewestFirst(convoID: String, beforeSeq: Long, limit: Int): List<EventEntity>

    @Query("SELECT MIN(seq) FROM event WHERE convo_id = :convoID")
    suspend fun minSeq(convoID: String): Long?

    @Query("SELECT MAX(seq) FROM event WHERE convo_id = :convoID")
    suspend fun maxSeq(convoID: String): Long?

    @Query("SELECT MAX(seq) FROM event WHERE convo_id = :convoID AND type IN (:messageTypes)")
    suspend fun newestMessageSeq(convoID: String, messageTypes: Collection<String>): Long?

    @Query(
        "SELECT COUNT(*) FROM event WHERE convo_id = :convoID AND seq > :afterSeq " +
            "AND type IN (:messageTypes) AND sender != :ownSender"
    )
    suspend fun countUnread(convoID: String, afterSeq: Long, messageTypes: Collection<String>, ownSender: String): Int

    @Query("SELECT * FROM event WHERE type = :type AND ts <= :cutoff")
    suspend fun ofTypeAtOrBefore(type: String, cutoff: Long): List<EventEntity>

    /// Events of the given [types] for one conversation, newest first — the
    /// media & links browser's Media and Files tabs (port of apple #142's
    /// `attachmentEvents` GRDB query).
    @Query("SELECT * FROM event WHERE convo_id = :convoID AND type IN (:types) ORDER BY seq DESC")
    suspend fun ofTypesNewestFirst(convoID: String, types: Collection<String>): List<EventEntity>

    /// `text` events whose payload contains [needle], newest first — the
    /// browser's cheap SQL prefilter for link candidates; precise extraction
    /// happens in Kotlin (`LinkExtractor`). The Apple original CASTs its BLOB
    /// payload to TEXT before LIKE; this schema stores payload as TEXT
    /// already, so a plain LIKE suffices (port of apple #142's
    /// `linkCandidateEvents` query).
    @Query(
        "SELECT * FROM event WHERE convo_id = :convoID AND type = 'text' " +
            "AND payload LIKE '%' || :needle || '%' ORDER BY seq DESC"
    )
    suspend fun textEventsContainingNewestFirst(convoID: String, needle: String): List<EventEntity>

    @Query("UPDATE event SET payload = :payload WHERE seq = :seq")
    suspend fun updatePayload(seq: Long, payload: String)

    @Query("DELETE FROM event")
    suspend fun deleteAll()
}

@Dao
interface SummaryEntryDao {
    /// Idempotent on the (convo_id, seq) key: replay/pagination can hand the
    /// same summary event to the store more than once.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: SummaryEntryEntity)

    /// TOC entries for one conversation, newest first — the summaries sheet's
    /// display order.
    @Query("SELECT * FROM summary_entry WHERE convo_id = :convoID ORDER BY seq DESC")
    suspend fun forConversation(convoID: String): List<SummaryEntryEntity>

    @Query("SELECT * FROM summary_entry WHERE convo_id = :convoID ORDER BY seq DESC")
    fun forConversationFlow(convoID: String): Flow<List<SummaryEntryEntity>>

    @Query("DELETE FROM summary_entry")
    suspend fun deleteAll()
}

@Dao
interface OutboxDao {
    /// Idempotent on `local_id` so a retry racing the original insert can't
    /// duplicate the row.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: OutboxEntity)

    /// Every queued row across all conversations, oldest first — the flush
    /// order. Failed rows are excluded: they only move again via an explicit
    /// user retry ([requeue]).
    @Query("SELECT * FROM outbox WHERE state = 'queued' ORDER BY created_at ASC, local_id ASC")
    suspend fun pending(): List<OutboxEntity>

    /// All outbox rows for one conversation (queued AND failed), oldest first —
    /// what the timeline renders as pending/failed echoes.
    @Query("SELECT * FROM outbox WHERE convo_id = :convoID ORDER BY created_at ASC, local_id ASC")
    suspend fun forConversation(convoID: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE convo_id = :convoID ORDER BY created_at ASC, local_id ASC")
    fun forConversationFlow(convoID: String): Flow<List<OutboxEntity>>

    /// Delivery-confirmation candidates: rows in [convoID] with [body] that
    /// have been attempted at least once, oldest first. See
    /// `JournalStore.outboxDeleteFirstMatching` for why never-attempted rows
    /// are excluded.
    @Query(
        "SELECT * FROM outbox WHERE convo_id = :convoID AND body = :body AND attempts > 0 " +
            "ORDER BY created_at ASC, local_id ASC"
    )
    suspend fun matching(convoID: String, body: String): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE local_id = :localID")
    suspend fun row(localID: String): OutboxEntity?

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE local_id = :localID")
    suspend fun markAttempt(localID: String)

    @Query("UPDATE outbox SET state = 'failed', last_error = :error WHERE local_id = :localID")
    suspend fun markFailed(localID: String, error: String?)

    /// Puts a failed row back in the flush set (tap-to-retry).
    @Query("UPDATE outbox SET state = 'queued', last_error = NULL WHERE local_id = :localID")
    suspend fun requeue(localID: String)

    @Query("DELETE FROM outbox WHERE local_id = :localID")
    suspend fun delete(localID: String)

    @Query("DELETE FROM outbox")
    suspend fun deleteAll()
}

@Dao
interface MetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: MetaEntity)

    @Query("SELECT value FROM meta WHERE key = :key")
    suspend fun value(key: String): String?

    /// Drops every key starting with [prefix] (the tracker's per-scope
    /// watermarks at `wipeItems`). `%`/`_` never occur in the prefixes used.
    @Query("DELETE FROM meta WHERE key LIKE :prefix || '%'")
    suspend fun deleteWithPrefix(prefix: String)

    @Query("DELETE FROM meta")
    suspend fun deleteAll()
}

// MARK: Task & decision tracker

@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ItemEntity>)

    @Query("SELECT * FROM item WHERE id = :id")
    suspend fun byId(id: String): ItemEntity?

    @Query("SELECT * FROM item WHERE id = :id")
    fun byIdFlow(id: String): Flow<ItemEntity?>

    /// Lookup by the human-facing `#num`. Numbers are unique per journal, so
    /// at most one row matches; the `id` ordering only makes a theoretical
    /// duplicate resolve to the same row every time.
    @Query("SELECT * FROM item WHERE num = :num ORDER BY id LIMIT 1")
    suspend fun byNum(num: Int): ItemEntity?

    @Query("SELECT * FROM item ORDER BY rank, num")
    suspend fun all(): List<ItemEntity>

    @Query("SELECT * FROM item ORDER BY rank, num")
    fun allFlow(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM item WHERE origin_convo_id = :convoID ORDER BY rank, num")
    suspend fun forConversation(convoID: String): List<ItemEntity>

    @Query("SELECT * FROM item WHERE origin_convo_id = :convoID ORDER BY rank, num")
    fun forConversationFlow(convoID: String): Flow<List<ItemEntity>>

    @Query("SELECT MAX(updated_at) FROM item")
    suspend fun maxUpdatedAt(): Long?

    /// Open items awaiting the user, counted per origin conversation — the
    /// chat-list rows' needs-you badge (apple #187). ONE grouped query for
    /// the whole list, live through Room's invalidation tracker, rather
    /// than a per-row subscription.
    @Query(
        "SELECT origin_convo_id AS convoID, COUNT(*) AS count FROM item " +
            "WHERE state = 'open' AND awaiting = 'user' GROUP BY origin_convo_id"
    )
    fun needsUserCountsFlow(): Flow<List<NeedsUserCountRow>>

    /// The mission page's open items: awaiting-you first (that is the
    /// section the page leads with), then newest activity. Closed items are
    /// excluded — the page shows what is still outstanding.
    @Query(
        "SELECT * FROM item WHERE mission_id = :missionID AND state = 'open' " +
            "ORDER BY (awaiting = 'user') DESC, updated_at DESC, num DESC"
    )
    suspend fun forMission(missionID: String): List<ItemEntity>

    @Query(
        "SELECT * FROM item WHERE mission_id = :missionID AND state = 'open' " +
            "ORDER BY (awaiting = 'user') DESC, updated_at DESC, num DESC"
    )
    fun forMissionFlow(missionID: String): Flow<List<ItemEntity>>

    /// Tracker rows must stop pointing at a mission that no longer exists
    /// in the cache (`JournalStore.replaceMissions`).
    @Query("UPDATE item SET mission_id = NULL, mission_num = NULL WHERE mission_id IN (:missionIDs)")
    suspend fun clearMission(missionIDs: List<String>)

    @Query("DELETE FROM item")
    suspend fun deleteAll()
}

/// Projection of [ItemDao.needsUserCountsFlow].
data class NeedsUserCountRow(val convoID: String, val count: Int)

@Dao
interface ItemCommentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(comments: List<ItemCommentEntity>)

    @Query("SELECT * FROM item_comment WHERE item_id = :itemID ORDER BY created_at, id")
    suspend fun forItem(itemID: String): List<ItemCommentEntity>

    @Query("SELECT * FROM item_comment WHERE item_id = :itemID ORDER BY created_at, id")
    fun forItemFlow(itemID: String): Flow<List<ItemCommentEntity>>

    @Query("SELECT COUNT(*) FROM item_comment")
    suspend fun count(): Int

    @Query("DELETE FROM item_comment WHERE item_id = :itemID")
    suspend fun deleteForItem(itemID: String)

    @Query("DELETE FROM item_comment")
    suspend fun deleteAll()
}

@Dao
interface ItemOutboxDao {
    /// Idempotent on `local_id`, like the text outbox: a duplicate insert of
    /// an already-queued local id (a retried UI action) is a silent no-op.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: ItemOutboxEntity)

    @Query("SELECT * FROM item_outbox ORDER BY created_at, local_id")
    suspend fun pending(): List<ItemOutboxEntity>

    @Query("SELECT * FROM item_outbox WHERE item_id = :itemID ORDER BY created_at, local_id")
    suspend fun forItem(itemID: String): List<ItemOutboxEntity>

    @Query("SELECT * FROM item_outbox WHERE item_id = :itemID ORDER BY created_at, local_id")
    fun forItemFlow(itemID: String): Flow<List<ItemOutboxEntity>>

    /// Every queued "create" row (an item that only exists locally, still
    /// waiting on the drain) — feeds the panel's Pending section.
    @Query("SELECT * FROM item_outbox WHERE op = 'create' ORDER BY created_at, local_id")
    fun createsFlow(): Flow<List<ItemOutboxEntity>>

    @Query("UPDATE item_outbox SET attempts = attempts + 1, last_error = :error WHERE local_id = :localID")
    suspend fun markAttempt(localID: String, error: String?)

    @Query("DELETE FROM item_outbox WHERE local_id = :localID")
    suspend fun delete(localID: String)

    @Query("DELETE FROM item_outbox")
    suspend fun deleteAll()
}

// MARK: Missions & milestones

@Dao
interface MissionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(missions: List<MissionEntity>)

    @Query("SELECT * FROM mission WHERE id = :id")
    suspend fun byId(id: String): MissionEntity?

    @Query("SELECT * FROM mission WHERE id = :id")
    fun byIdFlow(id: String): Flow<MissionEntity?>

    /// Lookup by the human-facing `#num`. Numbers are unique across items,
    /// missions and milestones, so at most one row can match.
    @Query("SELECT * FROM mission WHERE num = :num ORDER BY id LIMIT 1")
    suspend fun byNum(num: Int): MissionEntity?

    /// Open missions sort newest-activity first with never-checkpointed
    /// missions last (`last_milestone_at DESC NULLS LAST, created_at DESC`,
    /// the journal's own order). SQLite has no NULLS LAST, so the `IS NULL`
    /// term does it.
    @Query("SELECT * FROM mission WHERE state = 'open' ORDER BY last_milestone_at IS NULL, last_milestone_at DESC, created_at DESC")
    suspend fun open(): List<MissionEntity>

    @Query("SELECT * FROM mission WHERE state = 'open' ORDER BY last_milestone_at IS NULL, last_milestone_at DESC, created_at DESC")
    fun openFlow(): Flow<List<MissionEntity>>

    /// Closed ones sort newest-closed first.
    @Query("SELECT * FROM mission WHERE state = 'closed' ORDER BY closed_at DESC, num DESC")
    suspend fun closed(): List<MissionEntity>

    @Query("SELECT * FROM mission WHERE state = 'closed' ORDER BY closed_at DESC, num DESC")
    fun closedFlow(): Flow<List<MissionEntity>>

    /// `state DESC` puts 'open' before 'closed' (SQLite: 'closed' < 'open'),
    /// so a direct reader never sees closed-first.
    @Query("SELECT * FROM mission ORDER BY state DESC, last_milestone_at IS NULL, last_milestone_at DESC, created_at DESC")
    suspend fun all(): List<MissionEntity>

    @Query("SELECT * FROM mission ORDER BY state DESC, last_milestone_at IS NULL, last_milestone_at DESC, created_at DESC")
    fun allFlow(): Flow<List<MissionEntity>>

    /// Every cached id outside [ids] — the authoritative replace's sweep.
    @Query("SELECT id FROM mission WHERE id NOT IN (:ids)")
    suspend fun idsNotIn(ids: List<String>): List<String>

    @Query("DELETE FROM mission WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /// The ids among [ids] whose cached row is already closed — see
    /// `JournalStore.upsertMissions`' "closed is terminal" guard.
    @Query("SELECT id FROM mission WHERE id IN (:ids) AND state = 'closed'")
    suspend fun closedAmong(ids: List<String>): List<String>

    /// Which mission a conversation belongs to, derived locally (the
    /// snapshot does not carry `conversations.mission_id`). Three lookups,
    /// in order: origin; `mission_conversation`, the authoritative
    /// membership list a detail fetch populates the moment a `join` marker
    /// or a server-side inheritance names this conversation; then any
    /// milestone posted in the conversation, which still matters as a
    /// fallback until the owning mission's own detail fetch has ever
    /// landed. One statement so Room's invalidation tracker re-fires it on
    /// a write to any of the three tables. The journal never lets a
    /// conversation originate or join a second mission (its `mission_id` is
    /// never cleared), but should the cache ever hold several, the OPEN one
    /// wins, then the newest — a title tap must never land on a closed
    /// mission while a live one exists (Bugbot, #79).
    @Query(
        "SELECT COALESCE(" +
            "(SELECT id FROM mission WHERE origin_convo_id = :convoID ORDER BY (state = 'open') DESC, created_at DESC, id LIMIT 1), " +
            "(SELECT mc.mission_id FROM mission_conversation mc LEFT JOIN mission m ON m.id = mc.mission_id WHERE mc.convo_id = :convoID ORDER BY (m.state = 'open') DESC, m.created_at DESC, mc.mission_id LIMIT 1), " +
            "(SELECT mission_id FROM milestone WHERE convo_id = :convoID ORDER BY seq DESC LIMIT 1))"
    )
    suspend fun missionIDForConversation(convoID: String): String?

    @Query(
        "SELECT COALESCE(" +
            "(SELECT id FROM mission WHERE origin_convo_id = :convoID ORDER BY (state = 'open') DESC, created_at DESC, id LIMIT 1), " +
            "(SELECT mc.mission_id FROM mission_conversation mc LEFT JOIN mission m ON m.id = mc.mission_id WHERE mc.convo_id = :convoID ORDER BY (m.state = 'open') DESC, m.created_at DESC, mc.mission_id LIMIT 1), " +
            "(SELECT mission_id FROM milestone WHERE convo_id = :convoID ORDER BY seq DESC LIMIT 1))"
    )
    fun missionIDForConversationFlow(convoID: String): Flow<String?>

    @Query("DELETE FROM mission")
    suspend fun deleteAll()
}

@Dao
interface MilestoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(milestones: List<MilestoneEntity>)

    @Query("SELECT * FROM milestone WHERE mission_id = :missionID ORDER BY created_at DESC, num DESC")
    suspend fun forMission(missionID: String): List<MilestoneEntity>

    @Query("SELECT * FROM milestone WHERE mission_id = :missionID ORDER BY created_at DESC, num DESC")
    fun forMissionFlow(missionID: String): Flow<List<MilestoneEntity>>

    /// The per-conversation view (`GET /milestones?convo=`), newest first.
    @Query("SELECT * FROM milestone WHERE convo_id = :convoID ORDER BY seq DESC")
    suspend fun forConversation(convoID: String): List<MilestoneEntity>

    @Query("DELETE FROM milestone WHERE mission_id = :missionID")
    suspend fun deleteForMission(missionID: String)

    @Query("DELETE FROM milestone WHERE mission_id IN (:missionIDs)")
    suspend fun deleteForMissions(missionIDs: List<String>)

    @Query("DELETE FROM milestone")
    suspend fun deleteAll()
}

@Dao
interface MissionConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MissionConversationEntity>)

    @Query("SELECT * FROM mission_conversation WHERE mission_id = :missionID ORDER BY convo_id")
    suspend fun forMission(missionID: String): List<MissionConversationEntity>

    @Query("SELECT * FROM mission_conversation WHERE mission_id = :missionID ORDER BY convo_id")
    fun forMissionFlow(missionID: String): Flow<List<MissionConversationEntity>>

    @Query("DELETE FROM mission_conversation WHERE mission_id = :missionID")
    suspend fun deleteForMission(missionID: String)

    @Query("DELETE FROM mission_conversation WHERE mission_id IN (:missionIDs)")
    suspend fun deleteForMissions(missionIDs: List<String>)

    @Query("DELETE FROM mission_conversation")
    suspend fun deleteAll()
}
