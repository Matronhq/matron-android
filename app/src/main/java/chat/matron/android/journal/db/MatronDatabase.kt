package chat.matron.android.journal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import chat.matron.android.journal.JournalEventType
import chat.matron.android.journal.JournalStore
import chat.matron.android.journal.parseJsonObjectOrNull
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
///
/// v5 adds multi-agent room membership (matron-apple's v6): JSON `[Long]` of
/// the journal's owner + joined participant device ids, NULL for everything
/// that is not a room. Additive like v3: existing rows keep NULL and chip as
/// before until the next snapshot / membership convo_meta fills them in.
///
/// v7 is the launch-performance migration (matron-apple's v11, #212): the
/// `event(type, ts)` index the background sweeps range-scan, plus two derived
/// `conversation` columns (`last_message_type`, `expired_snippet`) backfilled
/// from the stored events so the chat list's first paint reads only the
/// `conversation` table. Additive and self-contained (one `Migration`).
@Database(
    entities = [
        ConversationEntity::class, EventEntity::class, MetaEntity::class, OutboxEntity::class,
        SummaryEntryEntity::class, AgentEntity::class,
    ],
    version = 7,
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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `conversation` ADD COLUMN `participants` TEXT")
            }
        }

        /// v6 (matron-apple's v7, #158): the journal-held box tag character.
        /// Additive; NULL rows fall back to the derived letter until a
        /// snapshot or the legacy-override migration fills them in.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `agent` ADD COLUMN `tag_char` TEXT")
            }
        }

        /// v7 (matron-apple's v11, #212): the `event(type, ts)` index plus
        /// `conversation.last_message_type` / `expired_snippet`, backfilled
        /// one conversation at a time over the existing `convo_id` index —
        /// the newest message-type row decides both columns, exactly as the
        /// live write path (`JournalStore.applyJournal`) computes them.
        /// Payloads are parsed in Kotlin rather than SQLite's json_extract
        /// (JSON1 availability varies by API level), like MIGRATION_2_3.
        /// This is the one-off cost of the migration: an index build over
        /// the whole `event` table plus one indexed point lookup per
        /// conversation; `LaunchTimeline` records how long it took.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `event_type_ts` ON `event` (`type`, `ts`)")
                db.execSQL("ALTER TABLE `conversation` ADD COLUMN `last_message_type` TEXT")
                db.execSQL("ALTER TABLE `conversation` ADD COLUMN `expired_snippet` TEXT")
                val messageTypes = JournalEventType.MESSAGE_TYPES.toList()
                val placeholders = messageTypes.joinToString(",") { "?" }
                val ids = db.query("SELECT `id` FROM `conversation`").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                for (id in ids) {
                    db.query(
                        "SELECT `type`, `payload` FROM `event` WHERE `convo_id` = ? AND `type` IN ($placeholders) " +
                            "ORDER BY `seq` DESC LIMIT 1",
                        arrayOf<Any>(id, *messageTypes.toTypedArray()),
                    ).use { cursor ->
                        if (!cursor.moveToFirst()) return@use
                        val type = cursor.getString(0)
                        val payload = parseJsonObjectOrNull(cursor.getString(1))
                        db.execSQL(
                            "UPDATE `conversation` SET `last_message_type` = ?, `expired_snippet` = ? WHERE `id` = ?",
                            arrayOf(type, JournalStore.expiredSnippet(type, payload), id),
                        )
                    }
                }
            }
        }

        /// Production, file-backed at the given path.
        fun open(context: Context, file: File): MatronDatabase =
            Room.databaseBuilder(context.applicationContext, MatronDatabase::class.java, file.absolutePath)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                )
                .build()

        /// Test/ephemeral, memory-backed. Cleared when the last connection closes.
        fun inMemory(context: Context): MatronDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, MatronDatabase::class.java)
                .build()
    }
}
