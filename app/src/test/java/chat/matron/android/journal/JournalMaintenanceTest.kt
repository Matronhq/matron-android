package chat.matron.android.journal

import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.search.SearchHit
import chat.matron.android.search.SearchIndexEntry
import chat.matron.android.search.SearchService
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Scheduling and sequencing of the background sweeper. The cadence is
/// driven entirely through `runIfDue(now)` with an injected clock — the
/// 10 s / 60 min timers in `start()` are a thin wrapper around it, so no
/// test has to sleep. Ported from matron-apple's `JournalMaintenanceTests`.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalMaintenanceTest {
    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /// Plain recorder for the scheduler tests; every call lands serially.
    private class SpyStore(lastRun: Long? = null) : MaintenanceSweeping {
        class Boom : IOException("boom")
        val purgeCalls = mutableListOf<Long>()
        val retentionCalls = mutableListOf<Long>()
        val callOrder = mutableListOf<String>()
        val searchRetirementCutoffs = mutableListOf<Long>()
        var lastRunStamp: Long? = lastRun
        var purgeError: Throwable? = null
        var pendingSearchResult = JournalStore.SearchRetirements(emptyList(), 0)

        override suspend fun purgeExpiredToolOutputSnippets(now: Long) {
            purgeError?.let { throw it }
            purgeCalls += now; callOrder += "purge"
        }
        override suspend fun applyRetention(now: Long): List<Long> {
            retentionCalls += now; callOrder += "retention"
            return emptyList()
        }
        override suspend fun maintenanceLastRun(): Long? = lastRunStamp
        override suspend fun recordMaintenanceRun(at: Long) { lastRunStamp = at }
        override suspend fun pendingSearchRetirements(now: Long): JournalStore.SearchRetirements {
            callOrder += "pendingSearchRetirements"
            return pendingSearchResult
        }
        override suspend fun recordSearchRetirement(upTo: Long) {
            searchRetirementCutoffs += upTo; callOrder += "recordSearchRetirement"
        }
    }

    /// Records removals and actually tracks the indexed set, so `contains`
    /// can prove a removal happened rather than just that it was requested.
    private class RecordingSearch : SearchService {
        val removed = mutableListOf<List<String>>()
        private val indexed = mutableSetOf<String>()
        /// Awaited inside [removeAll] — the suspension point the `stop()`
        /// test needs in order to hold a sweep open.
        var beforeRemoveAll: (suspend () -> Unit)? = null
        var removeAllError: Throwable? = null

        override suspend fun index(roomID: String, eventID: String, sender: String, timestamp: Instant, body: String) {
            indexed += eventID
        }
        override suspend fun indexBatch(entries: List<SearchIndexEntry>) { entries.forEach { indexed += it.eventID } }
        override suspend fun remove(eventID: String) { indexed -= eventID }
        override suspend fun removeAll(eventIDs: List<String>) {
            beforeRemoveAll?.invoke()
            removeAllError?.let { throw it }
            removed += eventIDs
            indexed -= eventIDs.toSet()
        }
        override suspend fun query(text: String, limit: Int): List<SearchHit> = emptyList()
        override suspend fun wipe() { indexed.clear() }
        override suspend fun recordBackfillProgress(roomID: String, indexedCount: Int, oldestEventID: String?, complete: Boolean) {}
        override suspend fun backfillComplete(roomID: String): Boolean = true
        override suspend fun backfillOldestEventID(roomID: String): String? = null
        override suspend fun eventCount(roomID: String): Int = indexed.size
        override suspend fun contains(eventID: String): Boolean = eventID in indexed
    }

    private fun maintenance(store: MaintenanceSweeping, search: SearchService? = null, now: () -> Long = { t0 }) =
        JournalMaintenance(store = store, search = search, now = now)

    // Every JournalMaintenance arms a launch hold at construction, so most
    // tests call `runAfterCatchUp()` rather than a bare `runIfDue()` — a bare
    // call would land inside that hold and no-op for a reason unrelated to
    // what the test claims to check.

    @Test
    fun firstRunSweepsWhenNothingHasEverRun() = runBlocking {
        val store = SpyStore()
        maintenance(store).runAfterCatchUp()
        assertEquals(listOf(t0), store.purgeCalls)
        assertEquals(listOf(t0), store.retentionCalls)
        assertEquals("retention sweeps first", listOf("retention", "purge"), store.callOrder)
        assertEquals("a completed sweep stamps maintenance_last_run", t0, store.lastRunStamp)
    }

    @Test
    fun aSecondRunInsideTheHourDoesNothing() = runBlocking {
        val store = SpyStore()
        val m = maintenance(store)
        m.runAfterCatchUp()
        m.runIfDue(now = t0 + 59 * minute)
        assertEquals("the hourly cadence is the whole point of the watermark", 1, store.purgeCalls.size)
    }

    @Test
    fun aRunPastTheHourSweepsAgain() = runBlocking {
        val store = SpyStore()
        val m = maintenance(store)
        m.runAfterCatchUp()
        val later = t0 + 61 * minute
        m.runIfDue(now = later)
        assertEquals(listOf(t0, later), store.purgeCalls)
    }

    /// The foreground path: a process that starts with a stamp older than
    /// an hour sweeps on the first check once the launch hold has passed.
    @Test
    fun aStaleStoredStampSweepsOnTheFirstForegroundCheck() = runBlocking {
        val store = SpyStore(lastRun = t0 - 2 * hour)
        val afterHold = t0 + JournalMaintenance.FIRST_RUN_DELAY_MS + 1
        maintenance(store).runIfDue(now = afterHold)
        assertEquals(listOf(afterHold), store.purgeCalls)
    }

    @Test
    fun aFreshStoredStampSkipsTheFirstRun() = runBlocking {
        val store = SpyStore(lastRun = t0 - 10 * minute)
        maintenance(store).runAfterCatchUp()
        assertTrue("a launch ten minutes after the last sweep must not re-sweep", store.purgeCalls.isEmpty())
    }

    // MARK: Launch hold

    @Test
    fun runIfDueImmediatelyAfterConstructionDoesNotRunAPass() = runBlocking {
        val store = SpyStore() // no lastRun: due immediately
        maintenance(store).runIfDue(now = t0)
        assertNull("a foreground hook landing right at construction must not run a pass", store.lastRunStamp)
    }

    @Test
    fun startDoesNotDisturbTheHold() = runBlocking {
        val store = SpyStore()
        val m = maintenance(store)
        m.start()
        m.runIfDue(now = t0)
        assertNull(store.lastRunStamp)
        m.stop()
    }

    @Test
    fun runAfterCatchUpRunsDuringTheHold() = runBlocking {
        val store = SpyStore()
        maintenance(store).runAfterCatchUp()
        assertEquals("catch-up completing lets a due pass run early", t0, store.lastRunStamp)
    }

    @Test
    fun runIfDueRunsOnceTheHoldExpires() = runBlocking {
        val store = SpyStore()
        val afterHold = t0 + JournalMaintenance.FIRST_RUN_DELAY_MS + 1
        maintenance(store).runIfDue(now = afterHold)
        assertEquals(afterHold, store.lastRunStamp)
    }

    /// A WorkManager-started process has no launch path to protect.
    @Test
    fun ignoreLaunchHoldRunsADuePassInsideTheHold() = runBlocking {
        val store = SpyStore()
        maintenance(store).runIfDue(now = t0, ignoreLaunchHold = true)
        assertEquals(t0, store.lastRunStamp)
    }

    // MARK: Search retirement

    @Test
    fun retiredSeqsAreRemovedFromTheSearchIndexInOneBatch() = runBlocking {
        val store = SpyStore()
        store.pendingSearchResult = JournalStore.SearchRetirements(listOf(11, 12, 13), t0)
        val search = RecordingSearch()
        maintenance(store, search).runAfterCatchUp()
        assertEquals("search rows are keyed by seq.toString()", listOf(listOf("11", "12", "13")), search.removed)
        assertEquals("a successful removal must advance the search-retention watermark",
            listOf(t0), store.searchRetirementCutoffs)
    }

    @Test
    fun nothingRetiredMeansNoSearchWriteButTheWatermarkStillMoves() = runBlocking {
        val store = SpyStore()
        store.pendingSearchResult = JournalStore.SearchRetirements(emptyList(), t0)
        val search = RecordingSearch()
        maintenance(store, search).runAfterCatchUp()
        assertTrue(search.removed.isEmpty())
        assertEquals("an empty pass still records the watermark so it isn't rescanned every tick",
            listOf(t0), store.searchRetirementCutoffs)
    }

    @Test
    fun aFailedSweepIsNotStampedAndIsRetriedNextTick() = runBlocking {
        val store = SpyStore()
        store.purgeError = SpyStore.Boom()
        val m = maintenance(store)
        m.runAfterCatchUp()
        assertNull(store.lastRunStamp)

        store.purgeError = null
        val later = t0 + minute
        m.runIfDue(now = later)
        assertEquals("the retry does not wait out the hour", later, store.lastRunStamp)
    }

    /// `stop()` cancels the in-flight pass and waits for it, so a pass
    /// interrupted while suspended in `removeAll` neither stamps
    /// `maintenance_last_run` nor advances the search watermark. The gate is
    /// parked under `NonCancellable` — the shape of a Room transaction that
    /// is mid-commit when sign-out arrives — because a plain `await()` would
    /// unwind at the cancel and prove nothing about the wait.
    @Test
    fun stopAwaitsTheInFlightSweepAndThePassRecordsNothing() = runBlocking {
        val store = SpyStore()
        store.pendingSearchResult = JournalStore.SearchRetirements(listOf(7), t0)
        val search = RecordingSearch()
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        search.beforeRemoveAll = { reached.complete(Unit); withContext(NonCancellable) { gate.await() } }
        val m = maintenance(store, search)

        val pass = launch { m.runAfterCatchUp() }
        withTimeout(2_000) { reached.await() }
        assertNull("precondition: the pass has not finished", store.lastRunStamp)

        val stopped = CompletableDeferred<Unit>()
        val stopping = launch { m.stop(); stopped.complete(Unit) }
        delay(100)
        assertFalse("stop() returned while a sweep was still suspended — sign-out would wipe under it",
            stopped.isCompleted)

        gate.complete(Unit)
        withTimeout(2_000) { stopping.join(); pass.join() }
        assertNull("the pass was cancelled — it must not buy itself a quiet interval it did not earn",
            store.lastRunStamp)
        assertTrue("the pass was cancelled before recordSearchRetirement — the watermark must not advance",
            store.searchRetirementCutoffs.isEmpty())
    }

    /// Once `stop()` has returned, `runIfDue` and `start()` must be
    /// permanent no-ops, and `stop()` itself must be idempotent.
    @Test
    fun runIfDueAndStartAfterStopPerformNoSweepAndStopIsIdempotent() = runBlocking {
        val store = SpyStore()
        val search = RecordingSearch()
        val m = maintenance(store, search)
        m.stop()
        m.stop()
        m.runIfDue(now = t0 + day, ignoreLaunchHold = true)
        assertTrue("no sweep may run after stop()", store.purgeCalls.isEmpty())
        assertTrue(store.retentionCalls.isEmpty())
        assertTrue(search.removed.isEmpty())
        assertNull(store.lastRunStamp)
        m.start()
        delay(50)
        assertTrue("start() after stop() must not arm a new schedule", store.purgeCalls.isEmpty())
    }

    // MARK: Real store wiring

    private fun realStore(): JournalStore =
        JournalStore(MatronDatabase.inMemory(ApplicationProvider.getApplicationContext()), ownSender = "user:dan")

    private fun liveLogToolOutput(seq: Long) = JournalEvent(
        seq = seq, convoID = "c1", ts = Instant.ofEpochMilli(1000), sender = "agent:dev-2",
        type = JournalEventType.TOOL_OUTPUT,
        payload = buildJsonObject { put("command", "make test"); put("live_log", true); put("snippet", "out") },
    )

    /// End-to-end: a >30-day live-log row is found by the pending scan and
    /// reaches `search.removeAll` in the very first pass.
    @Test
    fun aRetentionAgedRowReachesTheSearchIndexOnTheFirstPass() = runBlocking {
        val store = realStore()
        store.insertHistory(listOf(liveLogToolOutput(1)), now = 2000)
        val search = RecordingSearch()
        search.index("c1", "1", "agent:dev-2", Instant.ofEpochMilli(1000), "out")
        maintenance(store, search, now = { 1000 + 31 * day }).runAfterCatchUp()
        assertEquals(listOf(listOf("1")), search.removed)
        assertFalse("removeAll must have actually removed it from the index", search.contains("1"))
        assertTrue("the watermark must have advanced — a further pass has nothing pending",
            store.pendingSearchRetirements(now = 1000 + 31 * day).seqs.isEmpty())
    }

    /// With no search attached, a pass must skip search retirement ENTIRELY
    /// — leaving `search_retention_ts` untouched — rather than advancing the
    /// watermark past rows nothing ever removed from an index.
    @Test
    fun withNoSearchAttachedAPassLeavesTheSearchWatermarkUntouched() = runBlocking {
        val store = realStore()
        store.insertHistory(listOf(liveLogToolOutput(1)), now = 2000)
        val laterNow = 1000 + 31 * day
        maintenance(store, search = null, now = { laterNow }).runAfterCatchUp()
        assertEquals("nothing removed it from an index this pass never had a reference to",
            listOf(1L), store.pendingSearchRetirements(now = laterNow).seqs)
    }

    /// A search whose `removeAll` throws must leave the search-retention
    /// watermark untouched, and the SAME seq must come back and succeed on
    /// the next pass once the failure clears.
    @Test
    fun aSearchRemovalFailureLeavesTheWatermarkUntouchedAndRetriesNextPass() = runBlocking {
        val store = realStore()
        store.insertHistory(listOf(liveLogToolOutput(1)), now = 2000)
        val laterNow = 1000 + 31 * day
        val search = RecordingSearch()
        search.removeAllError = SpyStore.Boom()
        val m = maintenance(store, search, now = { laterNow })
        m.runAfterCatchUp()
        assertTrue("the throwing removal must not have recorded a batch", search.removed.isEmpty())
        assertEquals("a failed pass must not advance the watermark", listOf(1L), store.pendingSearchRetirements(laterNow).seqs)
        assertNull("a failed pass must not stamp maintenance_last_run", store.maintenanceLastRun())

        search.removeAllError = null
        m.runIfDue(now = laterNow + minute)
        assertEquals("the retry must succeed against the same seq", listOf(listOf("1")), search.removed)
        assertTrue(store.pendingSearchRetirements(laterNow + minute).seqs.isEmpty())
    }
}
