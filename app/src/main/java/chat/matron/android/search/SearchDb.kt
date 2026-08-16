package chat.matron.android.search

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/// FTS4 index row. Single-table design: `body` is the only tokenized column,
/// the rest are `notIndexed` metadata that ride along for retrieval.
///
/// This deviates from the Apple `SearchSchema` (a `messages` content table +
/// `messages_fts` external-content mirror + three sync triggers). The Android
/// analogue the brief calls for is a Room FTS4 entity; a single self-contained
/// FTS4 table sidesteps the SQLite `INSERT OR REPLACE`-doesn't-fire-DELETE-
/// triggers subtlety (delete triggers only fire under `recursive_triggers`)
/// that the content-table design depends on. Idempotent re-index and redaction
/// are done explicitly by rowid in [SearchServiceLive] instead.
@Fts4(notIndexed = ["room_id", "event_id", "sender", "timestamp"], tokenizer = FtsOptions.TOKENIZER_PORTER)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long? = null,
    @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    val sender: String,
    val timestamp: Long,
    val body: String,
)

/// Per-room backfill bookkeeping (the Apple `indexed_rooms` table).
@Entity(tableName = "indexed_rooms")
data class IndexedRoomEntity(
    @PrimaryKey @ColumnInfo(name = "room_id") val roomId: String,
    @ColumnInfo(name = "backfill_complete") val backfillComplete: Boolean,
    @ColumnInfo(name = "backfill_oldest_event_id") val backfillOldestEventId: String?,
    @ColumnInfo(name = "backfill_event_count") val backfillEventCount: Int,
)

/// Projection for a search hit row (SELECT aliases map to these fields).
data class SearchHitRow(
    val id: String,
    @ColumnInfo(name = "roomID") val roomID: String,
    val sender: String,
    val timestamp: Long,
    val snippet: String,
)

@Dao
interface SearchDao {
    @Query("SELECT rowid FROM messages_fts WHERE event_id = :eventId LIMIT 1")
    suspend fun rowidFor(eventId: String): Long?

    @Query("DELETE FROM messages_fts WHERE rowid = :rowid")
    suspend fun deleteByRowid(rowid: Long)

    @Insert
    suspend fun insertMessage(row: MessageFtsEntity): Long

    @Query(
        "SELECT event_id AS id, room_id AS roomID, sender, timestamp, " +
            "snippet(messages_fts, '<mark>', '</mark>', '…', -1, 32) AS snippet " +
            "FROM messages_fts WHERE messages_fts MATCH :pattern " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun search(pattern: String, limit: Int): List<SearchHitRow>

    @Query("SELECT COUNT(*) FROM messages_fts WHERE room_id = :roomId")
    suspend fun countForRoom(roomId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM messages_fts WHERE event_id = :eventId)")
    suspend fun contains(eventId: String): Boolean

    @Query("DELETE FROM messages_fts")
    suspend fun deleteAllMessages()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoom(room: IndexedRoomEntity)

    @Query("SELECT backfill_complete FROM indexed_rooms WHERE room_id = :roomId")
    suspend fun backfillComplete(roomId: String): Boolean?

    @Query("SELECT backfill_oldest_event_id FROM indexed_rooms WHERE room_id = :roomId")
    suspend fun backfillOldestEventId(roomId: String): String?

    @Query("DELETE FROM indexed_rooms")
    suspend fun deleteAllRooms()
}

/// Room database backing the search index. Separate from the journal
/// [chat.matron.android.journal.db.MatronDatabase] so the two stores stay
/// independent (the Apple `SearchServiceLive` owns its own `DatabaseQueue`).
@Database(entities = [MessageFtsEntity::class, IndexedRoomEntity::class], version = 1, exportSchema = false)
abstract class SearchDatabase : RoomDatabase() {
    abstract fun searchDao(): SearchDao

    companion object {
        fun open(context: Context, file: File): SearchDatabase =
            Room.databaseBuilder(context.applicationContext, SearchDatabase::class.java, file.absolutePath)
                .build()

        fun inMemory(context: Context): SearchDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, SearchDatabase::class.java)
                .build()
    }
}
