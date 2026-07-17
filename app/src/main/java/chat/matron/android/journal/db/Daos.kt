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

    /// Chat-list query: visible, top-level (no parent), newest first.
    @Query("SELECT * FROM conversation WHERE hidden = 0 AND parent_convo_id IS NULL ORDER BY last_seq DESC")
    suspend fun visibleTopLevel(): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE hidden = 0 AND parent_convo_id IS NULL ORDER BY last_seq DESC")
    fun visibleTopLevelFlow(): Flow<List<ConversationEntity>>

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
interface MetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: MetaEntity)

    @Query("SELECT value FROM meta WHERE key = :key")
    suspend fun value(key: String): String?

    @Query("DELETE FROM meta")
    suspend fun deleteAll()
}
