package chat.matron.android.search

import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import chat.matron.android.journal.previewText
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `SearchBackfillCoordinatorTests.swift`
/// (including Apple PR #130's batch assertions). Pure JVM: the coordinator
/// takes a scripted pager lambda and an in-memory [SearchService], so no
/// Robolectric/Room is needed.

/// In-memory [SearchService] mirroring `SearchServiceLive`'s bookkeeping
/// semantics closely enough for coordinator tests: indexed events keyed by
/// event id, per-room backfill progress rows. Port of the Apple test file's
/// `InMemorySearchService`.
private class InMemorySearchService : SearchService {
    data class Indexed(val roomID: String, val eventID: String, val sender: String, val body: String)
    data class Progress(var indexedCount: Int, var oldestEventID: String?, var complete: Boolean)

    val indexed = mutableMapOf<String, Indexed>()
    val progress = mutableMapOf<String, Progress>()

    /// Size of every [indexBatch] call, in order — the coordinator must index
    /// one BATCH per fetched page (one write transaction), never one call per
    /// event (Apple's 2026-08-10 8.6 GB disk-write exception).
    val batchSizes = mutableListOf<Int>()

    fun seedProgress(roomID: String, oldestEventID: String?, complete: Boolean) {
        progress[roomID] = Progress(indexedCount = 0, oldestEventID = oldestEventID, complete = complete)
    }

    override suspend fun index(roomID: String, eventID: String, sender: String, timestamp: Instant, body: String) {
        indexed[eventID] = Indexed(roomID, eventID, sender, body)
    }

    override suspend fun indexBatch(entries: List<SearchIndexEntry>) {
        batchSizes += entries.size
        for (entry in entries) {
            index(entry.roomID, entry.eventID, entry.sender, entry.timestamp, entry.body)
        }
    }

    override suspend fun remove(eventID: String) { indexed.remove(eventID) }
    override suspend fun query(text: String, limit: Int): List<SearchHit> = emptyList()
    override suspend fun wipe() { indexed.clear(); progress.clear() }

    override suspend fun recordBackfillProgress(roomID: String, indexedCount: Int, oldestEventID: String?, complete: Boolean) {
        progress[roomID] = Progress(indexedCount, oldestEventID, complete)
    }

    override suspend fun backfillComplete(roomID: String): Boolean = progress[roomID]?.complete ?: false
    override suspend fun backfillOldestEventID(roomID: String): String? = progress[roomID]?.oldestEventID

    /// Mirrors [SearchServiceLive.resetBackfill]: bump the generation, then
    /// clear the bookkeeping (messages stay).
    var generation = 0L
    override suspend fun resetBackfill() { generation += 1; progress.clear() }
    override suspend fun backfillGeneration(): Long = generation
    override suspend fun eventCount(roomID: String): Int = indexed.values.count { it.roomID == roomID }
    override suspend fun contains(eventID: String): Boolean = indexed.containsKey(eventID)
}

/// Scripted page server: serves [events] the way the journal server does —
/// `beforeSeq == null` returns the newest `limit` events, otherwise the newest
/// `limit` events with `seq < beforeSeq`, ascending. Records every call. Port
/// of the Apple test file's `ScriptedPager`.
private class ScriptedPager(events: List<JournalEvent>, private var failOnCall: Int? = null) {
    data class Call(val convoID: String, val beforeSeq: Long?)

    private val events = events.sortedBy { it.seq }
    val calls = mutableListOf<Call>()

    fun stopFailing() { failOnCall = null }

    fun page(convoID: String, beforeSeq: Long?, limit: Int): List<JournalEvent> {
        calls += Call(convoID, beforeSeq)
        if (calls.size == failOnCall) throw IOException("not connected to the internet")
        val eligible = events.filter { it.convoID == convoID && (beforeSeq == null || it.seq < beforeSeq) }
        return eligible.takeLast(limit)
    }
}

private fun makeEvent(
    seq: Long,
    convoID: String = "c1",
    type: String = JournalEventType.TEXT,
    payload: JsonObject = buildJsonObject { put("body", "hello") },
): JournalEvent = JournalEvent(
    seq = seq, convoID = convoID, ts = Instant.ofEpochSecond(seq),
    sender = "user:dan", type = type, payload = payload,
)

class SearchBackfillCoordinatorTest {
    private fun makeCoordinator(
        search: InMemorySearchService,
        pager: ScriptedPager,
        pageSize: Int = 2,
    ): SearchBackfillCoordinator = SearchBackfillCoordinator(
        search = search, pageSize = pageSize, throttleMillis = 0,
    ) { convoID, beforeSeq, limit ->
        pager.page(convoID, beforeSeq, limit)
    }

    /// Port of Apple's `test_fullWalk_indexesAllPagesAndMarksComplete`,
    /// including PR #130's one-indexBatch-per-page assertion.
    @Test
    fun fullWalkIndexesAllPagesAndMarksComplete() = runTest {
        val search = InMemorySearchService()
        val events = (1L..5L).map { makeEvent(seq = it, payload = buildJsonObject { put("body", "msg $it") }) }
        val pager = ScriptedPager(events)
        val coordinator = makeCoordinator(search, pager)

        val allComplete = coordinator.run(listOf("c1"))

        assertTrue(allComplete)
        assertEquals(setOf("1", "2", "3", "4", "5"), search.indexed.keys)
        assertEquals("msg 3", search.indexed["3"]?.body)
        val progress = search.progress["c1"]
        assertEquals(true, progress?.complete)
        assertEquals("1", progress?.oldestEventID)
        assertEquals(5, progress?.indexedCount)
        // Walk order: head page first (null), then strictly descending.
        assertEquals(listOf<Long?>(null, 4, 2), pager.calls.map { it.beforeSeq })
        // One indexBatch (= one write transaction) per fetched page.
        assertEquals(listOf(2, 2, 1), search.batchSizes)
    }

    /// Port of Apple's `test_completedConversation_isSkippedWithoutFetching`.
    @Test
    fun completedConversationIsSkippedWithoutFetching() = runTest {
        val search = InMemorySearchService()
        search.seedProgress("c1", oldestEventID = "1", complete = true)
        val pager = ScriptedPager(listOf(makeEvent(seq = 1)))
        val coordinator = makeCoordinator(search, pager)

        val allComplete = coordinator.run(listOf("c1"))

        assertTrue(allComplete)
        assertTrue(pager.calls.isEmpty())
    }

    /// Port of Apple's
    /// `test_resetBackfill_makesACompletedConversationWalkFromItsHeadAgain`:
    /// both skip paths (the complete flag and the downward resume) are blind
    /// to a HEAD-side hole — events applied while the index wasn't fed sit at
    /// each conversation's head. Clearing the bookkeeping (what the engine's
    /// cold-start `resetBackfill` does) makes the next sweep re-walk from the
    /// newest page and pick them up.
    @Test
    fun resetBackfillMakesACompletedConversationWalkFromItsHeadAgain() = runTest {
        val search = InMemorySearchService()
        search.seedProgress("c1", oldestEventID = "1", complete = true)
        val events = (1L..3L).map { makeEvent(seq = it, payload = buildJsonObject { put("body", "msg $it") }) }
        val pager = ScriptedPager(events)
        val coordinator = makeCoordinator(search, pager, pageSize = 10)

        // Precondition: without the reset, the head-side hole stays a hole.
        coordinator.run(listOf("c1"))
        assertTrue("a complete room is skipped without fetching", pager.calls.isEmpty())

        search.resetBackfill()
        val allComplete = coordinator.run(listOf("c1"))

        assertTrue(allComplete)
        assertEquals("the re-walk must start at the newest page", listOf<Long?>(null), pager.calls.map { it.beforeSeq })
        assertEquals(
            "events applied while the index was shut must end up indexed",
            setOf("1", "2", "3"), search.indexed.keys,
        )
    }

    /// No Apple counterpart — regression test for bugbot "Backfill races
    /// cold-start reset" (Android-only guard; Apple has the same race
    /// unguarded). A cold-start `resetBackfill` lands mid-walk: the in-flight
    /// walk must DROP its bookkeeping writes (a post-reset resume point or
    /// `complete = true` would hide the head-side gap the reset exists to
    /// close, and later sweeps would skip the room), report the sweep
    /// incomplete, and the next pass must re-walk the room from its head.
    @Test
    fun resetDuringWalkDropsStaleProgressAndRoomIsResweptFromHead() = runTest {
        val search = InMemorySearchService()
        val events = (1L..5L).map { makeEvent(seq = it, payload = buildJsonObject { put("body", "msg $it") }) }
        val pager = ScriptedPager(events)
        val coordinator = SearchBackfillCoordinator(
            search = search, pageSize = 2, throttleMillis = 0,
        ) { convoID, beforeSeq, limit ->
            val page = pager.page(convoID, beforeSeq, limit)
            // The engine's cold-start reset fires while page 2 is in flight —
            // after the walk snapshotted its generation and already recorded
            // page 1's progress.
            if (pager.calls.size == 2) search.resetBackfill()
            page
        }

        val firstPass = coordinator.run(listOf("c1"))

        assertFalse("a reset-invalidated walk must report the sweep incomplete", firstPass)
        assertNull(
            "the stale walk must not resurrect bookkeeping the reset deleted",
            search.progress["c1"],
        )
        // The message rows themselves survive — reset only clears bookkeeping,
        // and indexing is idempotent.
        assertEquals(setOf("2", "3", "4", "5"), search.indexed.keys)

        val secondPass = coordinator.run(listOf("c1"))

        assertTrue(secondPass)
        assertEquals(true, search.progress["c1"]?.complete)
        assertEquals(setOf("1", "2", "3", "4", "5"), search.indexed.keys)
        // The re-sweep started over at the newest page (calls 3+), not at the
        // stale walk's resume point.
        assertEquals(listOf<Long?>(null, 4, null, 4, 2), pager.calls.map { it.beforeSeq })
    }

    /// Port of Apple's `test_resume_startsFromRecordedOldest`.
    @Test
    fun resumeStartsFromRecordedOldest() = runTest {
        val search = InMemorySearchService()
        search.seedProgress("c1", oldestEventID = "40", complete = false)
        val events = (38L..45L).map { makeEvent(seq = it) }
        val pager = ScriptedPager(events)
        val coordinator = makeCoordinator(search, pager, pageSize = 10)

        val allComplete = coordinator.run(listOf("c1"))

        assertTrue(allComplete)
        assertEquals(40L, pager.calls.first().beforeSeq)
        // Only the events below the resume point were (re-)indexed.
        assertEquals(setOf("38", "39"), search.indexed.keys)
    }

    /// Port of Apple's `test_nonSearchableEvents_areSkippedButWalkCompletes`.
    @Test
    fun nonSearchableEventsAreSkippedButWalkCompletes() = runTest {
        val search = InMemorySearchService()
        val events = listOf(
            makeEvent(seq = 1, payload = buildJsonObject { put("body", "real text") }),
            makeEvent(seq = 2, type = JournalEventType.SESSION_STATUS, payload = buildJsonObject { put("state", "running") }),
            makeEvent(seq = 3, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject { put("snippet", "tool says") }),
            makeEvent(seq = 4, type = JournalEventType.DIFF, payload = buildJsonObject { put("diff", "+ added line") }),
            makeEvent(seq = 5, type = JournalEventType.IMAGE, payload = buildJsonObject { put("blob_ref", "b1") }),
        )
        val pager = ScriptedPager(events)
        val coordinator = makeCoordinator(search, pager, pageSize = 10)

        val allComplete = coordinator.run(listOf("c1"))

        assertTrue(allComplete)
        assertEquals(setOf("1", "3", "4"), search.indexed.keys)
        assertEquals("tool says", search.indexed["3"]?.body)
        assertEquals("+ added line", search.indexed["4"]?.body)
        assertEquals(true, search.progress["c1"]?.complete)
    }

    /// Port of Apple's `test_midWalkFailure_leavesResumableProgress_thenRetrySucceeds`.
    @Test
    fun midWalkFailureLeavesResumableProgressThenRetrySucceeds() = runTest {
        val search = InMemorySearchService()
        val events = (1L..5L).map { makeEvent(seq = it) }
        val pager = ScriptedPager(events, failOnCall = 2)
        val coordinator = makeCoordinator(search, pager)

        val firstPass = coordinator.run(listOf("c1"))

        assertFalse(firstPass)
        assertEquals(false, search.progress["c1"]?.complete)
        assertEquals("4", search.progress["c1"]?.oldestEventID) // page 1 (seqs 4,5) landed
        assertEquals(setOf("4", "5"), search.indexed.keys)

        pager.stopFailing()
        val secondPass = coordinator.run(listOf("c1"))

        assertTrue(secondPass)
        assertEquals(true, search.progress["c1"]?.complete)
        assertEquals(setOf("1", "2", "3", "4", "5"), search.indexed.keys)
        // The retry resumed below seq 4 instead of re-walking from the head.
        assertEquals(listOf<Long?>(null, 4, 4, 2), pager.calls.map { it.beforeSeq })
    }

    /// Port of Apple's `test_oneFailingConversation_doesNotBlockTheRest`.
    @Test
    fun oneFailingConversationDoesNotBlockTheRest() = runTest {
        val search = InMemorySearchService()
        val events = listOf(makeEvent(seq = 1, convoID = "bad"), makeEvent(seq = 2, convoID = "good"))
        val pager = ScriptedPager(events, failOnCall = 1) // first call (convo "bad") fails
        val coordinator = makeCoordinator(search, pager, pageSize = 10)

        val allComplete = coordinator.run(listOf("bad", "good"))

        assertFalse(allComplete)
        assertEquals(true, search.progress["good"]?.complete)
        assertEquals(setOf("2"), search.indexed.keys)
    }

    /// Port of Apple's `test_emptyConversation_marksCompleteImmediately`.
    @Test
    fun emptyConversationMarksCompleteImmediately() = runTest {
        val search = InMemorySearchService()
        val pager = ScriptedPager(emptyList())
        val coordinator = makeCoordinator(search, pager)

        val allComplete = coordinator.run(listOf("c1"))

        assertTrue(allComplete)
        assertEquals(true, search.progress["c1"]?.complete)
        assertNull(search.progress["c1"]?.oldestEventID)
    }

    /// Port of Apple's `test_searchableBody_mapsEventTypesLikeTheTimelineMapper`.
    /// Android's analogue of `searchableBody` is the pre-existing
    /// [previewText] (the shared source of truth for all three index feeders);
    /// unlike Apple's it can return "" for an empty body, which every feeder —
    /// including the coordinator's `isNullOrEmpty` filter — treats as
    /// unsearchable.
    @Test
    fun previewTextMapsEventTypesLikeTheTimelineMapper() {
        assertEquals("hi", makeEvent(seq = 1, payload = buildJsonObject { put("body", "hi") }).previewText())
        assertEquals(
            "out",
            makeEvent(seq = 2, type = JournalEventType.TOOL_OUTPUT, payload = buildJsonObject { put("snippet", "out") }).previewText(),
        )
        // diff precedence: `diff` wins over `snippet`, snippet is the fallback.
        assertEquals(
            "+ d",
            makeEvent(
                seq = 3, type = JournalEventType.DIFF,
                payload = buildJsonObject { put("diff", "+ d"); put("snippet", "s") },
            ).previewText(),
        )
        assertEquals(
            "s",
            makeEvent(seq = 4, type = JournalEventType.DIFF, payload = buildJsonObject { put("snippet", "s") }).previewText(),
        )
        assertNull(makeEvent(seq = 5, type = JournalEventType.IMAGE, payload = buildJsonObject { put("blob_ref", "b") }).previewText())
        assertTrue(makeEvent(seq = 6, payload = buildJsonObject { put("body", "") }).previewText().isNullOrEmpty())
    }
}
