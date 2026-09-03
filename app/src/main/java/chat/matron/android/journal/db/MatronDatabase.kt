package chat.matron.android.journal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import chat.matron.android.journal.JournalEventType
import java.io.File

/// Room database backing the journal mirror. Schema v1 already includes
/// `parent_convo_id` (the Apple original added it in a v2 migration; a fresh
/// Android app has no installed base, so it ships in v1 and no migration is
/// needed). `exportSchema = false`; migrations are hand-written below.
///
/// v2 adds the offline send outbox (matron-apple's v3): text sends that can't
/// reach the server yet persist here (surviving relaunch and the
/// `snapshot_required` mirror wipe — see `JournalStore.wipe`) and flush FIFO
/// on reconnect.
///
/// v3 adds the summaries-TOC table (matron-apple's v4): one row per bridge
/// `summary` journal event; the event's seq doubles as the transcript anchor.
/// Unlike the Apple migration, it also backfills the table from `summary`
/// events already stored in `event` (see MIGRATION_2_3).
///
/// v4 adds agent-box attribution (matron-apple's v5; spec: agent box rename).
/// `agent` is the id → name mirror of the server's `agents` snapshot list;
/// `conversation.agent_device_id` names which of those boxes owns the row.
/// Additive: existing rows keep NULL and simply render no chip until the next
/// snapshot fills them in.
@Database(
    entities = [
        ConversationEntity::class, EventEntity::class, MetaEntity::class, OutboxEntity::class,
        SummaryEntryEntity::class, AgentEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class MatronDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun eventDao(): EventDao
    abstract fun metaDao(): MetaDao
    abstract fun outboxDao(): OutboxDao
    abstract fun summaryEntryDao(): SummaryEntryDao
    abstract fun agentDao(): AgentDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox` (" +
                        "`local_id` TEXT NOT NULL, " +
                        "`convo_id` TEXT NOT NULL, " +
                        "`body` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`last_error` TEXT, " +
                        "PRIMARY KEY(`local_id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_convo_id` ON `outbox` (`convo_id`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `summary_entry` (" +
                        "`convo_id` TEXT NOT NULL, " +
                        "`seq` INTEGER NOT NULL, " +
                        "`toc` TEXT NOT NULL, " +
                        "`detail` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`convo_id`, `seq`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_summary_entry_convo_id` ON `summary_entry` (`convo_id`)"
                )
                // Backfill from `summary` events already in the mirror: they
                // sit at/below the sync cursor, so no ingest path will ever
                // re-process them — without this pass, summaries received
                // before the upgrade never appear in the TOC (bugbot
                // "Migration skips existing summaries"). Reuses the live
                // ingest path's accept/skip contract ([SummaryEntryEntity.from]:
                // only `summary` frames with a non-empty `toc`), parsing the
                // payload in Kotlin rather than SQLite's json_extract (JSON1
                // availability varies by API level). Diverges from
                // matron-apple's v4 migration, which creates the table empty
                // and has the same gap.
                db.query(
                    "SELECT `seq`, `convo_id`, `ts`, `sender`, `type`, `payload` FROM `event` WHERE `type` = ?",
                    arrayOf(JournalEventType.SUMMARY),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val event = EventEntity(
                            seq = cursor.getLong(0), convoID = cursor.getString(1), ts = cursor.getLong(2),
                            sender = cursor.getString(3), type = cursor.getString(4), payload = cursor.getString(5),
                        ).toJournalEvent()
                        val entry = SummaryEntryEntity.from(event) ?: continue
                        db.execSQL(
                            "INSERT OR IGNORE INTO `summary_entry` " +
                                "(`convo_id`, `seq`, `toc`, `detail`, `created_at`) VALUES (?, ?, ?, ?, ?)",
                            arrayOf(entry.convoID, entry.seq, entry.toc, entry.detail, entry.createdAt),
                        )
                    }
                }
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversation` ADD COLUMN `agent_device_id` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /// Production, file-backed at the given path.
        fun open(context: Context, file: File): MatronDatabase =
            Room.databaseBuilder(context.applicationContext, MatronDatabase::class.java, file.absolutePath)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        /// Test/ephemeral, memory-backed. Cleared when the last connection closes.
        fun inMemory(context: Context): MatronDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, MatronDatabase::class.java)
                .build()
    }
}
