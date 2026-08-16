package chat.matron.android.journal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Exercises the hand-written migrations against hand-built old-version
/// database files. The app
/// ships with no destructive-migration fallback (deliberately — silently
/// wiping the mirror on a schema mismatch would eat local state), so a wrong
/// hand-written migration means every existing install crashes on upgrade.
/// Room validates the post-migration schema against the entities at open;
/// this test fails if the CREATE TABLE here and OutboxEntity ever drift.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MatronDatabaseMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /// Builds the exact v1 schema Room generated for version 1 (conversation
    /// + event + meta, no outbox), stamped user_version = 1.
    private fun buildV1(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE `conversation` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`session_state` TEXT NOT NULL, `last_seq` INTEGER NOT NULL, `snippet` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `last_activity_ts` INTEGER, `muted` INTEGER NOT NULL, " +
                    "`hidden` INTEGER NOT NULL, `read_up_to_seq` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, " +
                    "`parent_convo_id` TEXT, PRIMARY KEY(`id`))"
            )
            db.execSQL("CREATE INDEX `index_conversation_parent_convo_id` ON `conversation` (`parent_convo_id`)")
            db.execSQL(
                "CREATE TABLE `event` (`seq` INTEGER NOT NULL, `convo_id` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                    "`sender` TEXT NOT NULL, `type` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`seq`))"
            )
            db.execSQL("CREATE INDEX `index_event_convo_id` ON `event` (`convo_id`)")
            db.execSQL("CREATE TABLE `meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            db.execSQL(
                "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"
            )
            db.execSQL("INSERT INTO meta VALUES ('cursor', '7')")
            db.version = 1
        }
    }

    /// v1 plus the exact v2 outbox schema (MIGRATION_1_2's own SQL is the
    /// Room-generated shape, so reusing it here builds a faithful v2 file),
    /// stamped user_version = 2.
    private fun buildV2(file: File) {
        buildV1(file)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE `outbox` (`local_id` TEXT NOT NULL, `convo_id` TEXT NOT NULL, " +
                    "`body` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                    "`attempts` INTEGER NOT NULL, `last_error` TEXT, PRIMARY KEY(`local_id`))"
            )
            db.execSQL("CREATE INDEX `index_outbox_convo_id` ON `outbox` (`convo_id`)")
            db.version = 2
        }
    }

    @Test
    fun migratesV1FileToCurrentAndOutboxWorks() = runBlocking {
        val file = File.createTempFile("migration-test", ".sqlite").also { it.delete() }
        buildV1(file)

        val database = MatronDatabase.open(context, file)
        try {
            val store = JournalStore(database, ownSender = "user:dan")
            // Pre-migration data survives…
            assertEquals(7L, store.cursor())
            // …and the migrated outbox table is fully usable.
            store.outboxInsert("a", "c1", "hello")
            assertEquals(listOf("a"), store.outboxPending().map { it.localID })
        } finally {
            database.close()
            file.delete()
        }
    }

    /// MIGRATION_2_3 (summaries TOC, apple #124 port): a v2 install upgrades
    /// in place, keeps its data, and the new `summary_entry` table is fully
    /// usable — a `summary` frame applied post-upgrade lands a TOC row.
    @Test
    fun migratesV2FileToV3AndSummaryEntriesWork() = runBlocking {
        val file = File.createTempFile("migration-test", ".sqlite").also { it.delete() }
        buildV2(file)

        val database = MatronDatabase.open(context, file)
        try {
            val store = JournalStore(database, ownSender = "user:dan")
            // Pre-migration data survives…
            assertEquals(7L, store.cursor())
            // …and the migrated summary_entry table is fully usable.
            store.applyJournal(
                JournalEvent(
                    seq = 8, convoID = "c1", ts = Instant.ofEpochMilli(8_000),
                    sender = "agent:a", type = "summary",
                    payload = buildJsonObject { put("toc", "Did the thing") },
                )
            )
            assertEquals(listOf(8L), store.summaryEntries("c1").map { it.seq })
        } finally {
            database.close()
            file.delete()
        }
    }
}
