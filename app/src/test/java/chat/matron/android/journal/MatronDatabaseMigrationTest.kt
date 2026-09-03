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

    /// v2 plus the exact v3 summary_entry schema (MIGRATION_2_3's SQL is the
    /// Room-generated shape), with one pre-existing conversation row, stamped
    /// user_version = 3.
    private fun buildV3(file: File) {
        buildV2(file)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE `summary_entry` (`convo_id` TEXT NOT NULL, `seq` INTEGER NOT NULL, " +
                    "`toc` TEXT NOT NULL, `detail` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`convo_id`, `seq`))"
            )
            db.execSQL("CREATE INDEX `index_summary_entry_convo_id` ON `summary_entry` (`convo_id`)")
            db.execSQL(
                "INSERT INTO conversation VALUES ('c1', 'Fix the parser', 'running', 3, 's', 1, NULL, 0, 0, 0, 0, NULL)"
            )
            db.version = 3
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

    /// MIGRATION_2_3's backfill (bugbot "Migration skips existing summaries"):
    /// `summary` events already in the mirror sit at/below the sync cursor, so
    /// no ingest path ever re-processes them — the migration itself must
    /// project them into `summary_entry`, applying the live path's accept/skip
    /// contract (only `summary` frames with a non-empty `toc`).
    @Test
    fun migrationBackfillsSummaryEntriesFromStoredEvents() = runBlocking {
        val file = File.createTempFile("migration-test", ".sqlite").also { it.delete() }
        buildV2(file)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Valid summary → backfilled.
            db.execSQL(
                "INSERT INTO event VALUES (3, 'c1', 3000, 'agent:a', 'summary', " +
                    "'{\"toc\": \"Fixed the build\", \"detail\": \"Pinned the toolchain\"}')"
            )
            // Empty toc → skipped, exactly like the live ingest path.
            db.execSQL("INSERT INTO event VALUES (4, 'c1', 4000, 'agent:a', 'summary', '{\"toc\": \"\"}')")
            // Non-summary → skipped.
            db.execSQL("INSERT INTO event VALUES (5, 'c1', 5000, 'user:dan', 'text', '{\"body\": \"hi\"}')")
        }

        val database = MatronDatabase.open(context, file)
        try {
            val store = JournalStore(database, ownSender = "user:dan")
            val entries = store.summaryEntries("c1")
            assertEquals(listOf(3L), entries.map { it.seq })
            assertEquals("Fixed the build", entries.single().toc)
            assertEquals("Pinned the toolchain", entries.single().detail)
            assertEquals(3000L, entries.single().createdAt)
        } finally {
            database.close()
            file.delete()
        }
    }

    /// MIGRATION_3_4 (agent-box attribution, port of matron-apple's GRDB v5):
    /// a hand-built v3 file gains `conversation.agent_device_id` and the
    /// `agent` table, existing rows keep NULL (no chip until the next
    /// snapshot), and both new surfaces are fully usable after open.
    @Test
    fun migratesV3FileToV4AndAgentRosterWorks() = runBlocking {
        val file = File.createTempFile("migration-test-v3", ".sqlite").also { it.delete() }
        buildV3(file)

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

    /// MIGRATION_4_5 (multi-agent room membership, port of matron-apple's
    /// GRDB v6): a hand-built v4 file gains `conversation.participants`,
    /// existing rows keep NULL (no room tags until the next snapshot /
    /// membership convo_meta), and the new column is fully usable after open.
    @Test
    fun migratesV4FileToV5AndParticipantsWork() = runBlocking {
        val file = File.createTempFile("migration-test-v4", ".sqlite").also { it.delete() }
        // The exact v4 schema Room generated (v3 tables + agent table +
        // agent_device_id, no participants column), stamped user_version = 4.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE `conversation` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                    "`session_state` TEXT NOT NULL, `last_seq` INTEGER NOT NULL, `snippet` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `last_activity_ts` INTEGER, `muted` INTEGER NOT NULL, " +
                    "`hidden` INTEGER NOT NULL, `read_up_to_seq` INTEGER NOT NULL, `unread_count` INTEGER NOT NULL, " +
                    "`parent_convo_id` TEXT, `agent_device_id` INTEGER, PRIMARY KEY(`id`))"
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
            db.execSQL(
                "CREATE TABLE `summary_entry` (`convo_id` TEXT NOT NULL, `seq` INTEGER NOT NULL, " +
                    "`toc` TEXT NOT NULL, `detail` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`convo_id`, `seq`))"
            )
            db.execSQL("CREATE INDEX `index_summary_entry_convo_id` ON `summary_entry` (`convo_id`)")
            db.execSQL("CREATE TABLE `agent` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            db.execSQL(
                "INSERT INTO conversation VALUES ('room', 'mac ↔ dev-z', 'waiting', 3, 's', 1, NULL, 0, 0, 0, 0, NULL, 7)"
            )
            db.version = 4
        }

        val database = MatronDatabase.open(context, file)
        try {
            val store = JournalStore(database, ownSender = "user:dan")
            // The pre-migration row survives with no membership (no room tags).
            assertEquals(emptyList<Long>(), store.conversation("room")?.participantIDs)
            // The new column is writable and round-trips.
            store.refreshSummaries(
                listOf(
                    ConvoSummaryDTO(
                        "room", "mac ↔ dev-z", "waiting", 4, "s", 1,
                        agentDeviceID = 7, participants = listOf(7, 9),
                    ),
                ),
            )
            assertEquals(listOf(7L, 9L), store.conversation("room")?.participantIDs)
        } finally {
            database.close()
            file.delete()
        }
    }
}
