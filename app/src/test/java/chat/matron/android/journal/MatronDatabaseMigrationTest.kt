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

    /// MIGRATION_2_3 (agent-box attribution, port of matron-apple's GRDB v5):
    /// a hand-built v2 file gains `conversation.agent_device_id` and the
    /// `agent` table, existing rows keep NULL (no chip until the next
    /// snapshot), and both new surfaces are fully usable after open.
    @Test
    fun migratesV2FileToV3AndAgentRosterWorks() = runBlocking {
        val file = File.createTempFile("migration-test-v2", ".sqlite").also { it.delete() }
        // The exact v2 schema Room generated (v1 tables + outbox, no agent
        // table, no agent_device_id column), stamped user_version = 2.
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
                "CREATE TABLE `outbox` (`local_id` TEXT NOT NULL, `convo_id` TEXT NOT NULL, " +
                    "`body` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                    "`attempts` INTEGER NOT NULL, `last_error` TEXT, PRIMARY KEY(`local_id`))"
            )
            db.execSQL("CREATE INDEX `index_outbox_convo_id` ON `outbox` (`convo_id`)")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL(
                "INSERT INTO conversation VALUES ('c1', 'Fix the parser', 'running', 3, 's', 1, NULL, 0, 0, 0, 0, NULL)"
            )
            db.version = 2
        }

        val database = MatronDatabase.open(context, file)
        try {
            val store = JournalStore(database, ownSender = "user:dan")
            // The pre-migration row survives with a NULL box (no chip).
            assertEquals(null, store.conversation("c1")?.agentDeviceID)
            // The new column is writable…
            store.refreshSummaries(
                listOf(ConvoSummaryDTO("c1", "Fix the parser", "running", 4, "s", 1, agentDeviceID = 7)),
            )
            assertEquals(7L, store.conversation("c1")?.agentDeviceID)
            // …and the migrated agent table is fully usable.
            store.replaceAgents(listOf(AgentDTO(7, "dev-y")))
            assertEquals(mapOf(7L to "dev-y"), store.agentNames())
        } finally {
            database.close()
            file.delete()
        }
    }
}
