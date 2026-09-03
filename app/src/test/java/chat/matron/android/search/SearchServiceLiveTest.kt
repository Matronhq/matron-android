package chat.matron.android.search

import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Ported from matron-apple's `SearchServiceLiveTests`. In-memory Room/FTS4
/// under Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SearchServiceLiveTest {
    private lateinit var db: SearchDatabase
    private lateinit var svc: SearchServiceLive

    @Before
    fun setUp() {
        db = SearchDatabase.inMemory(ApplicationProvider.getApplicationContext())
        svc = SearchServiceLive(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun indexAndQueryRoundTripPreservesAllFields() = runBlocking {
        val ts = Instant.ofEpochSecond(1_745_000_000)
        svc.index("!r:s", "e1", "@a:s", ts, "the auth bug is in src/auth.rs")
        val hits = svc.query("auth bug", 10)
        assertEquals(1, hits.size)
        assertEquals("e1", hits[0].id)
        assertEquals("!r:s", hits[0].roomID)
        assertEquals("@a:s", hits[0].sender)
        assertEquals(ts.epochSecond, hits[0].timestamp.epochSecond)
        assertTrue(hits[0].snippet, hits[0].snippet.contains("<mark>auth"))
    }

    @Test
    fun indexIsIdempotentReplaceUpdatesBody() = runBlocking {
        svc.index("!r:s", "e1", "@a:s", Instant.now(), "first")
        svc.index("!r:s", "e1", "@a:s", Instant.now(), "second")
        assertEquals(0, svc.query("first", 10).size)
        assertEquals(1, svc.query("second", 10).size)
    }

    @Test
    fun removeClearsFTSRow() = runBlocking {
        svc.index("!r:s", "e1", "@a:s", Instant.now(), "secret payload")
        svc.remove("e1")
        assertEquals(0, svc.query("secret", 10).size)
        assertFalse(svc.contains("e1"))
    }

    @Test
    fun eventCountPerRoom() = runBlocking {
        svc.index("!a:s", "e1", "@x:s", Instant.now(), "one")
        svc.index("!a:s", "e2", "@x:s", Instant.now(), "two")
        svc.index("!b:s", "e3", "@x:s", Instant.now(), "three")
        assertEquals(2, svc.eventCount("!a:s"))
        assertEquals(1, svc.eventCount("!b:s"))
    }

    @Test
    fun recordAndReadBackfill() = runBlocking {
        svc.recordBackfillProgress("!r:s", indexedCount = 100, oldestEventID = "old", complete = true)
        assertTrue(svc.backfillComplete("!r:s"))
        assertEquals("old", svc.backfillOldestEventID("!r:s"))
    }

    /// Apple PR #130: the backfill coordinator hands a whole fetched page to
    /// `indexBatch` so it lands in one write transaction. Rows must be
    /// queryable afterwards exactly as if indexed one by one.
    @Test
    fun indexBatchRoundTripsAllEntries() = runBlocking {
        val ts = Instant.ofEpochSecond(1_745_000_000)
        svc.indexBatch(
            listOf(
                SearchIndexEntry("!r:s", "e1", "@a:s", ts, "alpha message"),
                SearchIndexEntry("!r:s", "e2", "@b:s", ts, "beta message"),
                SearchIndexEntry("!other:s", "e3", "@c:s", ts, "gamma message"),
            )
        )
        assertEquals(2, svc.eventCount("!r:s"))
        assertEquals(1, svc.eventCount("!other:s"))
        val hits = svc.query("beta", 10)
        assertEquals(1, hits.size)
        assertEquals("e2", hits[0].id)
        assertEquals("@b:s", hits[0].sender)
    }

    /// Apple PR #130: batch indexing keeps [SearchServiceLive.index]'s
    /// idempotency — re-batching an already-indexed event replaces its row
    /// instead of duplicating it (backfill overlaps the live feeders).
    @Test
    fun indexBatchIsIdempotentOnEventId() = runBlocking {
        svc.index("!r:s", "e1", "@a:s", Instant.now(), "first")
        svc.indexBatch(listOf(SearchIndexEntry("!r:s", "e1", "@a:s", Instant.now(), "second")))
        assertEquals(1, svc.eventCount("!r:s"))
        assertEquals(0, svc.query("first", 10).size)
        assertEquals(1, svc.query("second", 10).size)
    }

    @Test
    fun indexBatchWithNoEntriesIsANoOp() = runBlocking {
        svc.indexBatch(emptyList())
        assertEquals(0, svc.eventCount("!r:s"))
    }

    /// resetBackfill clears the bookkeeping (so a sweep re-walks every room
    /// from its head) while leaving indexed messages searchable.
    @Test
    fun resetBackfillClearsBookkeepingButKeepsMessages() = runBlocking {
        svc.index("!r:s", "e1", "@a:s", Instant.now(), "durable needle")
        svc.recordBackfillProgress("!r:s", indexedCount = 1, oldestEventID = "e1", complete = true)

        svc.resetBackfill()

        assertFalse(svc.backfillComplete("!r:s"))
        assertEquals(null, svc.backfillOldestEventID("!r:s"))
        assertEquals(1, svc.query("needle", 10).size)
    }
}
