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
}

@Dao
interface AgentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agent: AgentEntity)

    /// Update-only (no insert): the live `device_meta` path must not create
    /// roster rows — see `JournalStore.renameAgent`. A no-op for unknown ids.
    @Query("UPDATE agent SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

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

    @Query("DELETE FROM meta")
    suspend fun deleteAll()
}
