package chat.matron.android.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.events.MilestoneMarkerEvent
import chat.matron.android.events.MissionMarker
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.Milestone
import chat.matron.android.models.MilestoneKind
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionConversation
import chat.matron.android.models.MissionState
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.models.TrackerItem
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Scriptable [MissionsProviding] — the port of the Apple suite's
/// `FakeMissions`: gates hold one call open so a test can prove coalescing
/// and stale-response semantics against a genuinely in-flight request.
private class FakeMissions : MissionsProviding {
    private val lock = Any()
    private var _list: List<Mission> = emptyList()
    private var _listDroppedIDs: List<String> = emptyList()
    private var _listCalls = 0
    private val _details = mutableMapOf<String, MissionDetail>()
    private val _detailCalls = mutableListOf<String>()
    private val _closed = mutableListOf<Pair<String, String>>()
    private var _detailGate: CompletableDeferred<Unit>? = null
    private var _listGate: CompletableDeferred<Unit>? = null

    @Volatile var listError: Throwable? = null
    @Volatile var blockNextDetail = false
    @Volatile var blockNextList = false

    var list: List<Mission>
        get() = synchronized(lock) { _list }
        set(v) = synchronized(lock) { _list = v }
    var listDroppedIDs: List<String>
        get() = synchronized(lock) { _listDroppedIDs }
        set(v) = synchronized(lock) { _listDroppedIDs = v }
    val listCalls: Int get() = synchronized(lock) { _listCalls }
    val detailCalls: List<String> get() = synchronized(lock) { _detailCalls.toList() }
    val closed: List<Pair<String, String>> get() = synchronized(lock) { _closed.toList() }
    val isDetailGated: Boolean get() = synchronized(lock) { _detailGate != null }
    val isListGated: Boolean get() = synchronized(lock) { _listGate != null }

    fun detail(id: String, d: MissionDetail) = synchronized(lock) { _details[id] = d }
    fun releaseDetailGate() = synchronized(lock) { val g = _detailGate; _detailGate = null; g }?.complete(Unit)
    fun releaseListGate() = synchronized(lock) { val g = _listGate; _listGate = null; g }?.complete(Unit)

    override suspend fun listMissions(query: MissionsListQuery): MissionsListDecode {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val gate = synchronized(lock) {
            _listCalls += 1
            if (blockNextList) { blockNextList = false; CompletableDeferred<Unit>().also { _listGate = it } } else null
        }
        gate?.await()
        listError?.let { throw it }
        return MissionsListDecode(list, listDroppedIDs)
    }

    override suspend fun mission(id: String): MissionDetail {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val gate = synchronized(lock) {
            _detailCalls.add(id)
            if (blockNextDetail) { blockNextDetail = false; CompletableDeferred<Unit>().also { _detailGate = it } } else null
        }
        gate?.await()
        return synchronized(lock) { _details[id] } ?: throw JournalApiError.NotFound
    }

    override suspend fun milestones(convoID: String): List<Milestone> = emptyList()

    override suspend fun closeMission(id: String, summary: String): Mission {
        synchronized(lock) { _closed.add(id to summary) }
        val m = synchronized(lock) { _details[id]?.mission } ?: throw JournalApiError.NotFound
        return m.copy(state = MissionState.CLOSED, closeSummary = summary, closedBy = ItemAuthor.USER, closedOverOpenItems = 1, closedAt = Instant.ofEpochSecond(99))
    }
}

/// Ported from matron-apple's `MissionsSyncTests`. The store is a real
/// in-memory Room database (under Robolectric); the sync runs on
/// `Dispatchers.Default` like production, and the tests poll.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MissionsSyncTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class Rig(
        val sync: MissionsSync,
        val store: JournalStore,
        val markers: MutableSharedFlow<Pair<String, MissionMarker>>,
        val states: MutableStateFlow<SyncConnectionState>,
    )

    private fun make(api: FakeMissions): Rig {
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")
        val markers = MutableSharedFlow<Pair<String, MissionMarker>>(extraBufferCapacity = 64)
        val states = MutableStateFlow<SyncConnectionState>(SyncConnectionState.Connecting)
        return Rig(MissionsSync(api, store, markers = { markers }, connectionStates = { states }), store, markers, states)
    }

    private fun mission(id: String, num: Int) = Mission(
        id = id, num = num, title = "M$num", originConvoID = "c1",
        createdAt = Instant.ofEpochSecond(1), updatedAt = Instant.ofEpochSecond(2), lastMilestoneAt = Instant.ofEpochSecond(3),
    )

    private fun detail(m: Mission, milestones: List<Milestone> = emptyList(), conversations: List<MissionConversation> = emptyList()) =
        MissionDetail(m, milestones, emptyList(), conversations)

    private suspend fun waitUntil(timeoutMs: Long = 3_000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue("condition not met before timeout", cond())
    }

    @Test
    fun reconnectFetchesTheWholeListIntoTheStore() = runBlocking {
        val api = FakeMissions()
        api.list = listOf(mission("ms_1", 61), mission("ms_2", 62))
        val rig = make(api)
        assertNull("support unknown before the first probe", rig.sync.isSupported.value)
        rig.sync.start()
        rig.states.value = SyncConnectionState.Running
        waitUntil { rig.store.missions(null).size == 2 }
        assertEquals(listOf("ms_1", "ms_2"), rig.store.missions(null).map { it.id }.sorted())
        assertEquals(true, rig.sync.isSupported.value)
        rig.sync.stop()
    }

    /// The full-list refresh IS authoritative (CodeRabbit apple #209): a
    /// mission the server stops returning must not linger, and neither
    /// must its cached milestones/conversations, and an item pointed at it
    /// must stop naming it.
    @Test
    fun reconnectDropsAMissionTheServerNoLongerReturns() = runBlocking {
        val api = FakeMissions()
        api.list = listOf(mission("ms_1", 61), mission("ms_2", 62))
        val rig = make(api)
        assertEquals(MissionsRefreshOutcome.Succeeded, rig.sync.refresh())
        rig.store.replaceMilestones("ms_1", listOf(Milestone("ml_1", "ms_1", 1, MilestoneKind.USER_INPUT, "step", convoID = "c1", seq = 400)))
        rig.store.replaceMissionConversations("ms_1", listOf(MissionConversation("c1", "Session", "dev-2", "running")))
        rig.store.upsertItems(listOf(TrackerItem(id = "it_1", num = 900, kind = ItemKind.TASK, title = "carry", originConvoID = "c1", missionID = "ms_1", missionNum = 61)))
        api.list = listOf(mission("ms_2", 62))
        assertEquals(MissionsRefreshOutcome.Succeeded, rig.sync.refresh())
        assertEquals(listOf("ms_2"), rig.store.missions(null).map { it.id })
        assertTrue(rig.store.milestones("ms_1").isEmpty())
        assertTrue(rig.store.missionConversations("ms_1").isEmpty())
        val survivor = rig.store.item("it_1")
        assertNull("an item pointed at a deleted mission must be cleared, not left dangling", survivor?.missionID)
        assertNull(survivor?.missionNum)
        rig.sync.stop()
    }

    /// A list GET issued before a mission existed can still be in flight
    /// when a marker-driven detail refresh for that NEW mission completes
    /// FIRST — the (now stale) list response must neither delete the
    /// mission the detail refresh just wrote nor revert its fields.
    @Test
    fun aConcurrentDetailRefreshSurvivesAStaleInFlightListRefresh() = runBlocking {
        val api = FakeMissions()
        api.list = listOf(mission("ms_1", 61), Mission(id = "ms_2", num = 62, title = "stale title from an earlier snapshot", originConvoID = "c1"))
        api.detail("ms_2", detail(mission("ms_2", 62)))
        val rig = make(api)
        api.blockNextList = true
        val listTask = async(Dispatchers.Default) { rig.sync.refresh() }
        waitUntil { api.isListGated }
        assertEquals(MissionsRefreshOutcome.Succeeded, rig.sync.refreshMission("ms_2"))
        assertEquals("the detail refresh lands before the stale list response is even released", "M62", rig.store.mission("ms_2")?.title)
        api.releaseListGate()
        assertEquals(MissionsRefreshOutcome.Succeeded, listTask.await())
        assertEquals("the concurrent detail refresh's fields survive the now-stale list row, not just the row's existence", "M62", rig.store.mission("ms_2")?.title)
        rig.sync.stop()
    }

    /// A mission this device merely failed to DECODE on the next list
    /// response — as opposed to one the server actually stopped returning —
    /// must survive the authoritative replace.
    @Test
    fun aMissionDroppedByLocalDecodeFailureSurvivesTheReplace() = runBlocking {
        val api = FakeMissions()
        api.list = listOf(mission("ms_1", 61))
        val rig = make(api)
        assertEquals(MissionsRefreshOutcome.Succeeded, rig.sync.refresh())
        rig.store.upsertMissions(listOf(mission("ms_2", 62)))
        api.listDroppedIDs = listOf("ms_2")
        assertEquals(MissionsRefreshOutcome.Succeeded, rig.sync.refresh())
        assertEquals(listOf("ms_1", "ms_2"), rig.store.missions(null).map { it.id }.sorted())
        rig.sync.stop()
    }

    @Test
    fun markerForAMissionRefetchesThatMissionOnly() = runBlocking {
        val api = FakeMissions()
        api.detail(
            "ms_1",
            detail(
                mission("ms_1", 61),
                milestones = listOf(Milestone("ml_1", "ms_1", 62, MilestoneKind.USER_INPUT, "step", convoID = "c1", seq = 400, createdAt = Instant.ofEpochSecond(4))),
                conversations = listOf(MissionConversation("c1", "Session", "dev-2", "running")),
            ),
        )
        val rig = make(api)
        rig.sync.start()
        waitUntil { rig.markers.subscriptionCount.value > 0 }
        rig.markers.emit(
            "c1" to MissionMarker.Milestone(MilestoneMarkerEvent("ml_1", 62, MilestoneKind.USER_INPUT, "step", missionID = "ms_1", missionNum = 61, missionTitle = null)),
        )
        waitUntil { rig.store.mission("ms_1") != null }
        assertEquals(listOf("ms_1"), api.detailCalls)
        assertEquals(listOf(400L), rig.store.milestones("ms_1").map { it.seq })
        assertEquals(listOf("c1"), rig.store.missionConversations("ms_1").map { it.id })
        // The marker carried NO mission_title — the store still learned the
        // real title, because it came from the fetch, not the marker.
        assertEquals("M61", rig.store.mission("ms_1")?.title)
        rig.sync.stop()
    }

    /// Mirrors `ItemsSyncTest.coalescedRefreshItemAwaitsTheInFlightRun`: a
    /// joiner does not issue its own concurrent GET, but the in-flight run
    /// repeats once more for it before returning. The joiner barrier is the
    /// sync's own `refetchJoins` counter, not a timed sleep.
    @Test
    fun concurrentRefetchesForOneMissionCoalesceIntoOneRepeatedRequest() = runBlocking {
        val api = FakeMissions()
        api.detail("ms_1", detail(mission("ms_1", 61)))
        val rig = make(api)
        api.blockNextDetail = true
        val first = async(Dispatchers.Default) { rig.sync.refreshMission("ms_1") }
        waitUntil { api.isDetailGated }
        val joinsBefore = rig.sync.refetchJoins
        var secondFinished = false
        val second = async(Dispatchers.Default) { rig.sync.refreshMission("ms_1").also { secondFinished = true } }
        waitUntil { rig.sync.refetchJoins > joinsBefore }
        assertFalse("the joiner must await the run already in flight", secondFinished)
        assertEquals("only one request may be active before the gate releases", 1, api.detailCalls.count { it == "ms_1" })
        api.releaseDetailGate()
        first.await(); second.await()
        assertEquals("the in-flight run repeats once for the coalesced joiner rather than issuing a separate concurrent GET", 2, api.detailCalls.count { it == "ms_1" })
        rig.sync.stop()
    }

    @Test
    fun concurrentListRefreshesShareOneFetch() = runBlocking {
        val api = FakeMissions()
        api.blockNextList = true
        val rig = make(api)
        val first = async(Dispatchers.Default) { rig.sync.refresh() }
        waitUntil { api.isListGated }
        val second = async(Dispatchers.Default) { rig.sync.refresh() }
        delay(100)
        api.releaseListGate()
        first.await(); second.await()
        assertEquals("two concurrent refreshes hit the API once", 1, api.listCalls)
        rig.sync.refresh()
        assertEquals("coalescing is per-run, not a permanent latch", 2, api.listCalls)
        rig.sync.stop()
    }

    @Test
    fun a404MarksTheJournalUnsupportedAndPublishesIt() = runBlocking {
        val api = FakeMissions(); api.listError = JournalApiError.NotFound
        val rig = make(api)
        assertNull(rig.sync.isSupported.value)
        assertEquals(MissionsRefreshOutcome.Unsupported, rig.sync.refresh())
        assertEquals(false, rig.sync.isSupported.value)
        rig.sync.stop()
    }

    /// A failed refresh must leave the cached tables exactly as they were,
    /// and a transport error is not a support signal — only a 404 may flip
    /// `isSupported`.
    @Test
    fun aFailedRefreshKeepsTheCacheAndReportsTheFailure() = runBlocking {
        val api = FakeMissions()
        api.list = listOf(mission("ms_1", 61))
        val rig = make(api)
        assertEquals(MissionsRefreshOutcome.Succeeded, rig.sync.refresh())
        api.listError = JournalApiError.Transport("offline")
        assertEquals(MissionsRefreshOutcome.Failed(JournalApiError.Transport("offline").message!!), rig.sync.refresh())
        assertEquals(listOf("ms_1"), rig.store.missions(null).map { it.id })
        assertEquals(true, rig.sync.isSupported.value)
        rig.sync.stop()
    }

    @Test
    fun aMissingMissionOnRefetchIsNotAFailure() = runBlocking {
        val api = FakeMissions()
        val rig = make(api)
        assertEquals(MissionsRefreshOutcome.Succeeded, rig.sync.refreshMission("ms_gone"))
        assertNull("not found or invisible: nothing written, support untouched", rig.sync.isSupported.value)
        rig.sync.stop()
    }

    @Test
    fun stopCancelsAnInFlightRefreshSoItsDataNeverLands() = runBlocking {
        val api = FakeMissions()
        api.list = listOf(mission("ms_1", 61))
        api.blockNextList = true
        val rig = make(api)
        rig.sync.start()
        val refresh = async(Dispatchers.Default) { rig.sync.refresh() }
        waitUntil { api.isListGated }
        rig.sync.stop()
        api.releaseListGate()
        assertEquals(MissionsRefreshOutcome.Stopped, withTimeout(2_000) { refresh.await() })
        assertTrue("a stopped refresh writes nothing", rig.store.missions(null).isEmpty())
    }

    @Test
    fun closeWritesTheReturnedMissionStraightIntoTheStore() = runBlocking {
        val api = FakeMissions()
        api.detail("ms_1", detail(mission("ms_1", 61)))
        val rig = make(api)
        rig.store.upsertMissions(listOf(mission("ms_1", 61)))
        val closed = rig.sync.closeMission("ms_1", "Done.")
        assertEquals(MissionState.CLOSED, closed.state)
        assertEquals(listOf("Done."), api.closed.map { it.second })
        assertEquals(MissionState.CLOSED, rig.store.mission("ms_1")?.state)
        assertEquals(1, rig.store.mission("ms_1")?.closedOverOpenItems)
        rig.sync.stop()
    }

    /// An in-flight list GET issued before the close landed can still be
    /// holding the OLDER, still-open snapshot when it returns; the close's
    /// id is protected so that stale row cannot revert it.
    @Test
    fun closeSurvivesAStaleInFlightListRefresh() = runBlocking {
        val api = FakeMissions()
        api.detail("ms_1", detail(mission("ms_1", 61)))
        api.list = listOf(mission("ms_1", 61))
        val rig = make(api)
        api.blockNextList = true
        val listTask = async(Dispatchers.Default) { rig.sync.refresh() }
        waitUntil { api.isListGated }
        assertEquals(MissionState.CLOSED, rig.sync.closeMission("ms_1", "Done.").state)
        assertEquals(MissionState.CLOSED, rig.store.mission("ms_1")?.state)
        api.releaseListGate()
        assertEquals(MissionsRefreshOutcome.Succeeded, listTask.await())
        assertEquals("the closed mission survives the now-stale, still-open list response", MissionState.CLOSED, rig.store.mission("ms_1")?.state)
        rig.sync.stop()
    }
}
