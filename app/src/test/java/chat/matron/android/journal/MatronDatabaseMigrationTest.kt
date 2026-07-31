package chat.matron.android.journal

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Exercises MIGRATION_1_2 against a hand-built v1 database file. The app
/// ships with no destructive-migration fallback (deliberately — silently
/// wiping the mirror on a schema mismatch would eat local state), so a wrong
/// hand-written migration means every existing install crashes on upgrade.
/// Room validates the post-migration schema against the entities at open;
/// this test fails if the CREATE TABLE here and OutboxEntity ever drift.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MatronDatabaseMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun migratesV1FileToV2AndOutboxWorks() = runBlocking {
        val file = File.createTempFile("migration-test", ".sqlite").also { it.delete() }
        // Build the exact v1 schema Room generated for version 1 (conversation
        // + event + meta, no outbox), stamped user_version = 1.
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
}
