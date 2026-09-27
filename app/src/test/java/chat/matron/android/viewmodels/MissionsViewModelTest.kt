package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.MissionsRefreshOutcome
import chat.matron.android.journal.MissionsStoreReading
import chat.matron.android.journal.MissionsSyncing
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.Milestone
import chat.matron.android.models.MilestoneKind
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionConversation
import chat.matron.android.models.MissionState
import chat.matron.android.models.SessionTagInputs
import chat.matron.android.models.TrackerItem
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Fake store whose flows a test drives by hand (the Apple suite's
/// `FakeMissionsStore` continuations → `MutableSharedFlow`s).
private class FakeMissionsStore : MissionsStoreReading {
    val missions = MutableSharedFlow<List<Mission>>(replay = 1)
    val mission = MutableSharedFlow<Mission?>(replay = 1)
    val milestones = MutableSharedFlow<List<Milestone>>(replay = 1)
    val items = MutableSharedFlow<List<TrackerItem>>(replay = 1)
    val conversations = MutableSharedFlow<List<MissionConversation>>(replay = 1)
    /// The cached `A:bc` tags, by conversation id. A conversation missing
    /// from this map is one this device never synced.
    var tags: Map<String, SessionTagInputs> = emptyMap()
    var tagRequests = mutableListOf<Set<String>>()

    override fun missionsFlow(state: MissionState?): Flow<List<Mission>> = missions
    override fun missionFlow(id: String): Flow<Mission?> = mission
    override fun milestonesFlow(missionID: String): Flow<List<Milestone>> = milestones
    override fun missionItemsFlow(missionID: String): Flow<List<TrackerItem>> = items
    override fun missionConversationsFlow(missionID: String): Flow<List<MissionConversation>> = conversations
    override suspend fun sessionTags(convoIDs: Set<String>): Map<String, SessionTagInputs> {
        tagRequests += convoIDs
        return tags.filterKeys { it in convoIDs }
    }
}

private class FakeMissionsSync : MissionsSyncing {
    var refreshes = 0
    val refetches = mutableListOf<String>()
    val closes = mutableListOf<Pair<String, String>>()
    var closeError: Throwable? = null
    var refreshOutcome: MissionsRefreshOutcome = MissionsRefreshOutcome.Succeeded
    var refreshMissionOutcome: MissionsRefreshOutcome = MissionsRefreshOutcome.Succeeded
    override val isSupported = MutableStateFlow<Boolean?>(null)

    override suspend fun refresh(): MissionsRefreshOutcome { refreshes += 1; return refreshOutcome }
    override suspend fun refreshMission(id: String): MissionsRefreshOutcome { refetches += id; return refreshMissionOutcome }
    override suspend fun closeMission(id: String, summary: String): Mission {
        closes += id to summary
        closeError?.let { throw it }
        return Mission(id = id, num = 61, state = MissionState.CLOSED, title = "M61", closeSummary = summary, originConvoID = "c1")
    }
}

/// Ported from matron-apple's `MissionsViewModelTests`. The VMs' jobs run
/// on a real dispatcher; the tests poll.
class MissionsViewModelTest {
    private fun mission(id: String, num: Int, state: MissionState = MissionState.OPEN, lastMilestoneAt: Long?, needsYou: Int = 0, closedAt: Long? = null) = Mission(
        id = id, num = num, state = state, title = "M$num", originConvoID = "c1",
        createdAt = Instant.ofEpochSecond(num.toLong()), updatedAt = Instant.ofEpochSecond(2),
        lastMilestoneAt = lastMilestoneAt?.let(Instant::ofEpochSecond), closedAt = closedAt?.let(Instant::ofEpochSecond),
        openItems = needsYou, needsYou = needsYou,
    )

    private fun milestone(id: String, num: Int, kind: MilestoneKind, title: String, convo: String = "c1", seq: Long) =
        Milestone(id = id, missionID = "ms_1", num = num, kind = kind, title = title, convoID = convo, seq = seq)

    private suspend fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue("condition not met before timeout", cond())
    }

    private fun <T> vmTest(block: suspend (CoroutineScope) -> T) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try { block(scope) } finally { scope.cancel() }
    }

    @Test
    fun sectionsSortOpenByActivityAndClosedByCloseTime() {
        val (open, closed) = MissionsListViewModel.sections(
            listOf(
                mission("ms_1", 61, lastMilestoneAt = 10),
                mission("ms_2", 62, lastMilestoneAt = 30),
                mission("ms_3", 63, lastMilestoneAt = null),
                mission("ms_4", 64, state = MissionState.CLOSED, lastMilestoneAt = 20, closedAt = 40),
                mission("ms_5", 65, state = MissionState.CLOSED, lastMilestoneAt = 5, closedAt = 50),
            ),
        )
        assertEquals("newest milestone first, never-checkpointed last", listOf("ms_2", "ms_1", "ms_3"), open.map { it.id })
        assertEquals("newest close first", listOf("ms_5", "ms_4"), closed.map { it.id })
    }

    @Test
    fun listPublishesSectionsBadgeAndSupport() = vmTest { scope ->
        val store = FakeMissionsStore(); val sync = FakeMissionsSync()
        val vm = MissionsListViewModel(store, sync, scope)
        assertNull("support starts unknown", vm.isSupported.value)
        vm.start()
        sync.isSupported.value = true
        store.missions.emit(listOf(mission("ms_1", 61, lastMilestoneAt = 10, needsYou = 2), mission("ms_2", 62, state = MissionState.CLOSED, lastMilestoneAt = 5, closedAt = 9)))
        waitUntil { vm.open.value.isNotEmpty() && vm.isSupported.value == true }
        assertEquals(listOf("ms_1"), vm.open.value.map { it.id })
        assertEquals(listOf("ms_2"), vm.closed.value.map { it.id })
        assertEquals(2, vm.needsYouTotal.value)
        waitUntil { sync.refreshes == 1 }
        vm.stop()
    }

    @Test
    fun unsupportedJournalFlipsTheFlagThatHidesTheTab() = vmTest { scope ->
        val store = FakeMissionsStore(); val sync = FakeMissionsSync()
        val vm = MissionsListViewModel(store, sync, scope)
        vm.start()
        sync.isSupported.value = false
        waitUntil { vm.isSupported.value == false }
        vm.stop()
    }

    @Test
    fun detailFiltersMilestonesToUserInputOnly() {
        val all = listOf(milestone("ml_1", 62, MilestoneKind.PROGRESS, "landed", seq = 10), milestone("ml_2", 63, MilestoneKind.USER_INPUT, "Dan said", seq = 20))
        assertEquals(listOf("ml_1", "ml_2"), MissionDetailViewModel.filtered(all, false).map { it.id })
        assertEquals(listOf("ml_2"), MissionDetailViewModel.filtered(all, true).map { it.id })
    }

    @Test
    fun detailRefetchesOnStartAndPublishesEveryStream() = vmTest { scope ->
        val store = FakeMissionsStore(); val sync = FakeMissionsSync()
        // `c9` is deliberately absent: a milestone posted in a conversation
        // this device never synced must still render, just without a tag.
        store.tags = mapOf("c1" to SessionTagInputs(boxLetter = "D", boxName = "dev-2", sessionShort = "bc"))
        val vm = MissionDetailViewModel("ms_1", store, sync, scope)
        vm.start()
        store.mission.emit(mission("ms_1", 61, lastMilestoneAt = 10))
        store.milestones.emit(listOf(milestone("ml_2", 63, MilestoneKind.USER_INPUT, "Dan said", seq = 20), milestone("ml_1", 62, MilestoneKind.PROGRESS, "landed", convo = "c9", seq = 10)))
        store.items.emit(listOf(TrackerItem(id = "it_1", num = 64, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, title = "needs you", originConvoID = "c1")))
        store.conversations.emit(listOf(MissionConversation("c1", "Session", "dev-2", "running")))
        waitUntil { vm.mission.value != null && vm.milestones.value.size == 2 && vm.openItems.value.size == 1 && vm.conversations.value.size == 1 && vm.sessionTags.value.isNotEmpty() }
        assertEquals("ms_1", vm.mission.value?.id)
        assertEquals(listOf("ml_2", "ml_1"), vm.milestones.value.map { it.id })
        assertEquals(listOf("it_1"), vm.openItems.value.map { it.id })
        assertEquals(listOf("c1"), vm.conversations.value.map { it.id })
        waitUntil { sync.refetches == listOf("ms_1") }
        assertEquals("D", vm.sessionTags.value["c1"]?.boxLetter)
        assertEquals("bc", vm.sessionTags.value["c1"]?.sessionShort)
        assertNull("an unsynced conversation carries no tag rather than an empty one", vm.sessionTags.value["c9"])
        assertEquals("one batch read per emission, every distinct conversation", setOf("c1", "c9"), store.tagRequests.last())
        vm.setShowOnlyUserInput(true)
        assertEquals(listOf("ml_2"), vm.milestones.value.map { it.id })
        vm.stop()
    }

    /// The user's close is always allowed; the summary is trimmed before it
    /// is sent, and the draft clears on success.
    @Test
    fun closeSendsTheTrimmedSummary() = vmTest { scope ->
        val store = FakeMissionsStore(); val sync = FakeMissionsSync()
        val vm = MissionDetailViewModel("ms_1", store, sync, scope)
        vm.start()
        store.mission.emit(mission("ms_1", 61, lastMilestoneAt = 10, needsYou = 2))
        store.items.emit(listOf(
            TrackerItem(id = "it_1", num = 64, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, title = "a", originConvoID = "c1"),
            TrackerItem(id = "it_2", num = 65, kind = ItemKind.TASK, awaiting = ItemAwaiting.AGENT, title = "b", originConvoID = "c1"),
        ))
        waitUntil { vm.openItems.value.size == 2 }
        vm.setCloseSummaryDraft("  Shipped.  ")
        vm.close()
        assertEquals(listOf("ms_1"), sync.closes.map { it.first })
        assertEquals("the summary is trimmed before it is sent", listOf("Shipped."), sync.closes.map { it.second })
        assertNull(vm.error.value)
        assertFalse(vm.isBusy.value)
        assertEquals("", vm.closeSummaryDraft.value)
        vm.stop()
    }

    @Test
    fun closeRefusesAnEmptySummaryAndSurfacesAServerFailure() = vmTest { scope ->
        val store = FakeMissionsStore(); val sync = FakeMissionsSync()
        val vm = MissionDetailViewModel("ms_1", store, sync, scope)
        vm.setCloseSummaryDraft("   ")
        vm.close()
        assertTrue(sync.closes.isEmpty())
        assertEquals("Write a short summary before closing the mission.", vm.error.value)

        vm.dismissError()
        vm.setCloseSummaryDraft("Done.")
        sync.closeError = JournalApiError.Transport("offline")
        vm.close()
        assertEquals(1, sync.closes.size)
        assertNotNull(vm.error.value)
        assertFalse(vm.isBusy.value)
        assertEquals("a failed close keeps the draft", "Done.", vm.closeSummaryDraft.value)
    }

    /// A failed detail refresh must surface rather than leave the page's
    /// "not on this device yet" placeholder permanent and un-retryable.
    @Test
    fun failedDetailRefreshSetsError() = vmTest { scope ->
        val store = FakeMissionsStore(); val sync = FakeMissionsSync()
        sync.refreshMissionOutcome = MissionsRefreshOutcome.Failed("offline")
        val vm = MissionDetailViewModel("ms_1", store, sync, scope)
        vm.start()
        waitUntil { vm.error.value != null }
        assertNull("still nothing cached — the placeholder stays, now with a real error to retry against", vm.mission.value)
        vm.stop()
    }

    /// A refresh that succeeds after an earlier failure must drop the stale
    /// banner, on the list and on the page alike.
    @Test
    fun refreshClearsStaleErrorOnSuccess() = vmTest { scope ->
        val store = FakeMissionsStore(); val sync = FakeMissionsSync()
        val list = MissionsListViewModel(store, sync, scope)
        sync.refreshOutcome = MissionsRefreshOutcome.Failed("offline")
        list.refresh()
        assertEquals("offline", list.error.value)
        sync.refreshOutcome = MissionsRefreshOutcome.Succeeded
        list.refresh()
        assertNull(list.error.value)
        assertFalse(list.isRefreshing.value)

        val detail = MissionDetailViewModel("ms_1", store, sync, scope)
        sync.refreshMissionOutcome = MissionsRefreshOutcome.Failed("offline")
        detail.refresh()
        assertEquals("offline", detail.error.value)
        sync.refreshMissionOutcome = MissionsRefreshOutcome.Succeeded
        detail.refresh()
        assertNull(detail.error.value)
    }
}
