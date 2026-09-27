package chat.matron.android.journal

import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Ported from matron-apple's `StoreDiagnosticsTests`.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StoreDiagnosticsTest {
    private val now = 1_700_000_000_000L

    @Test
    fun lastMaintenanceTextIsRelativeAndHandlesNever() {
        assertEquals("Never", StoreDiagnostics.lastMaintenanceText(null, now))
        val anHourAgo = StoreDiagnostics.lastMaintenanceText(now - 3600_000, now)
        val aWeekAgo = StoreDiagnostics.lastMaintenanceText(now - 7L * 24 * 3600_000, now)
        assertNotEquals("Never", anHourAgo)
        assertNotEquals("the row must actually vary with the age it is given", anHourAgo, aWeekAgo)
    }

    @Test
    fun sizesReadsBothFileGroupsAndBothCounts() = runBlocking {
        val dir = File.createTempFile("diag", "").also { it.delete(); it.mkdirs() }
        val journalFile = File(dir, "journal.sqlite")
        val searchFile = File(dir, "search.sqlite")
        val db = MatronDatabase.open(ApplicationProvider.getApplicationContext(), journalFile)
        try {
            val store = JournalStore(db, ownSender = "user:dan")
            for (seq in 1L..3L) {
                store.applyJournal(
                    JournalEvent(
                        seq = seq, convoID = if (seq == 3L) "c2" else "c1", ts = Instant.ofEpochMilli(seq * 1000),
                        sender = "agent:dev-2", type = JournalEventType.TEXT,
                        payload = buildJsonObject { put("body", "hi") },
                    ),
                    now = 10_000,
                )
            }
            store.recordMaintenanceRun(at = now)
            searchFile.writeBytes(ByteArray(2048) { 7 })
            File(searchFile.path + "-wal").writeBytes(ByteArray(100) { 1 })

            val sizes = StoreDiagnostics.sizes(store, journalFile, searchFile)
            assertTrue("the sqlite file (plus -wal/-shm) has a size", sizes.journalBytes > 0)
            assertEquals("main file plus its write-ahead log", 2148L, sizes.searchBytes)
            assertEquals(3, sizes.eventCount)
            assertEquals(2, sizes.conversationCount)
            assertEquals(now, sizes.lastMaintenance)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun sizesReportsZeroForAnInMemoryStoreAndAMissingIndex() = runBlocking {
        val store = JournalStore(MatronDatabase.inMemory(ApplicationProvider.getApplicationContext()), ownSender = "user:dan")
        val sizes = StoreDiagnostics.sizes(store, journalFile = null, searchFile = File("/tmp/does-not-exist-${System.nanoTime()}.sqlite"))
        assertEquals("an in-memory store has no file", 0L, sizes.journalBytes)
        assertEquals(0L, sizes.searchBytes)
        assertEquals(0, sizes.eventCount)
        assertNull(sizes.lastMaintenance)
    }
}
