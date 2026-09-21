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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /// The exact v5 schema (v4 + `conversation.participants`), stamped
    /// user_version = 5, with one agent row carrying no tag.
    private fun buildV5(file: File) {
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
            db.execSQL("ALTER TABLE `conversation` ADD COLUMN `participants` TEXT")
            db.version = 5
        }
    }

    /// MIGRATION_5_6 (journal-held tag characters, apple #158): a v5 file
    /// gains `agent.tag_char`, existing rows read as automatic (NULL), and
    /// the column is fully usable after open.
    @Test
    fun migratesV5FileToV6AndTagCharsWork() = runBlocking {
        val file = File.createTempFile("migration-test-v5", ".sqlite").also { it.delete() }
        buildV5(file)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("INSERT INTO agent VALUES (7, 'dev-y')")
        }
        val database = MatronDatabase.open(context, file)
        try {
            val store = JournalStore(database, ownSender = "user:dan")
            assertEquals(mapOf(7L to "dev-y"), store.agentNames())
            assertEquals(emptyMap<Long, String>(), store.agentTags())
            store.applyDeviceMeta(7, "dev-y", tagChar = "Q", tagCharKnown = true)
            assertEquals(mapOf(7L to "Q"), store.agentTags())
        } finally {
            database.close()
            file.delete()
        }
    }

    /// The exact v6 schema (v5 + `agent.tag_char`), stamped user_version = 6.
    private fun buildV6(file: File) {
        buildV5(file)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("ALTER TABLE `agent` ADD COLUMN `tag_char` TEXT")
            db.version = 6
        }
    }

    private fun indexNames(database: MatronDatabase, table: String): List<String> =
        database.openHelper.readableDatabase.query("PRAGMA index_list('$table')").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameColumn)) }
        }

    private fun indexColumns(database: MatronDatabase, index: String): List<String> =
        database.openHelper.readableDatabase.query("PRAGMA index_info('$index')").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            buildList { while (cursor.moveToNext()) add(cursor.getString(nameColumn)) }
        }

    /// MIGRATION_6_7 (launch performance, port of matron-apple's GRDB v11 /
    /// #212): the `event(type, ts)` index the sweeps range-scan exists with
    /// its columns in the order the range scan needs.
    @Test
    fun migratesV6FileToV7AndCreatesTheTypeTsIndex() = runBlocking {
        val file = File.createTempFile("migration-test-v6", ".sqlite").also { it.delete() }
        buildV6(file)
        val database = MatronDatabase.open(context, file)
        try {
            val names = indexNames(database, "event")
            assertTrue("the sweep's covering index is missing: $names", "event_type_ts" in names)
            assertEquals(
                "column order decides whether the range scan works",
                listOf("type", "ts"), indexColumns(database, "event_type_ts"),
            )
        } finally {
            database.close()
            file.delete()
        }
    }

    /// The v7 backfill: `last_message_type` / `expired_snippet` are derived
    /// from the NEWEST message-type event of each conversation already in
    /// the mirror — a bookkeeping frame after it must not win, a text
    /// newest gets no command stub, and a conversation with no message-type
    /// event at all stays NULL/NULL.
    @Test
    fun migrationBackfillsLastMessageTypeAndExpiredSnippetFromStoredEvents() = runBlocking {
        val file = File.createTempFile("migration-test-v6", ".sqlite").also { it.delete() }
        buildV6(file)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (id in listOf("c1", "c2", "c3", "c4", "c5")) {
                db.execSQL(
                    "INSERT INTO conversation VALUES ('$id', 'T-$id', 'running', 0, '', 0, NULL, 0, 0, 0, 0, NULL, NULL, NULL)"
                )
            }
            // c1: newest message-type row is a live-log tool_output; the
            // read_marker after it is not a message.
            db.execSQL("INSERT INTO event VALUES (1, 'c1', 1000, 'agent:a', 'text', '{\"body\": \"hi\"}')")
            db.execSQL(
                "INSERT INTO event VALUES (2, 'c1', 2000, 'agent:a', 'tool_output', " +
                    "'{\"command\": \"make test\", \"live_log\": true, \"snippet\": \"out\"}')"
            )
            db.execSQL("INSERT INTO event VALUES (3, 'c1', 3000, 'user:dan', 'read_marker', '{\"up_to_seq\": 2}')")
            // c2: newest message-type row is plain text.
            db.execSQL(
                "INSERT INTO event VALUES (4, 'c2', 4000, 'agent:a', 'tool_output', " +
                    "'{\"command\": \"ls\", \"live_log\": true}')"
            )
            db.execSQL("INSERT INTO event VALUES (5, 'c2', 5000, 'agent:a', 'text', '{\"body\": \"after\"}')")
            // c3: no message-type event at all.
            db.execSQL("INSERT INTO event VALUES (6, 'c3', 6000, 'agent:a', 'session_status', '{\"state\": \"idle\"}')")
            // c4: a legacy tool_output (no live_log, not expired) keeps its
            // durable snippet — no stub, or the read path would hide it.
            db.execSQL(
                "INSERT INTO event VALUES (7, 'c4', 7000, 'agent:a', 'tool_output', " +
                    "'{\"command\": \"legacy\", \"snippet\": \"kept\"}')"
            )
            // c5: a server-tombstoned row — the list has only the command to show.
            db.execSQL(
                "INSERT INTO event VALUES (8, 'c5', 8000, 'agent:a', 'tool_output', " +
                    "'{\"command\": \"make build\", \"expired\": true}')"
            )
        }

        val database = MatronDatabase.open(context, file)
        try {
            val store = JournalStore(database, ownSender = "user:dan")
            assertEquals("tool_output", store.conversation("c1")?.lastMessageType)
            assertEquals("$ make test", store.conversation("c1")?.expiredSnippet)
            assertEquals("text", store.conversation("c2")?.lastMessageType)
            assertNull("only tool_output gets a command stub", store.conversation("c2")?.expiredSnippet)
            assertNull("no message-type event means no last message type", store.conversation("c3")?.lastMessageType)
            assertNull(store.conversation("c3")?.expiredSnippet)
            assertEquals("tool_output", store.conversation("c4")?.lastMessageType)
            assertNull("a legacy payload keeps its real snippet", store.conversation("c4")?.expiredSnippet)
            assertEquals("$ make build", store.conversation("c5")?.expiredSnippet)
        } finally {
            database.close()
            file.delete()
        }
    }
}
