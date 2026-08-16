package chat.matron.android.journal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
@Database(
    entities = [
        ConversationEntity::class, EventEntity::class, MetaEntity::class, OutboxEntity::class,
        SummaryEntryEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class MatronDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun eventDao(): EventDao
    abstract fun metaDao(): MetaDao
    abstract fun outboxDao(): OutboxDao
    abstract fun summaryEntryDao(): SummaryEntryDao

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
            }
        }

        /// Production, file-backed at the given path.
        fun open(context: Context, file: File): MatronDatabase =
            Room.databaseBuilder(context.applicationContext, MatronDatabase::class.java, file.absolutePath)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        /// Test/ephemeral, memory-backed. Cleared when the last connection closes.
        fun inMemory(context: Context): MatronDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, MatronDatabase::class.java)
                .build()
    }
}
