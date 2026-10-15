package chat.matron.android.viewmodels

import chat.matron.android.chat.FakeMediaService
import chat.matron.android.chat.FakeTimelineService
import chat.matron.android.chat.JournalTimelineMapper
import chat.matron.android.chat.TimelineItem
import chat.matron.android.chat.TimelineService
import chat.matron.android.events.AgentChatCardState
import chat.matron.android.events.AgentSpawnCardState
import chat.matron.android.events.ConsentDecisionEvent
import chat.matron.android.events.ItemMarkerEvent
import chat.matron.android.events.SessionStopState
import chat.matron.android.journal.AgentChatDecision
import chat.matron.android.journal.AgentSpawnAnswering
import chat.matron.android.journal.AgentSpawnDecision
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.SessionStopping
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises wire parsing through the view model's actual render rows. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatConsentTimelineTest {
    private fun event(seq: Long, type: String, payload: kotlinx.serialization.json.JsonObject) =
        JournalTimelineMapper.timelineItem(
            JournalEvent(seq, "room-1", Instant.ofEpochSecond(seq), "journal", type, payload),
            "user:alice", "https://example.com".toHttpUrl(),
        )!!

    private fun card(seq: Long = 10, target: Long = 7) = event(seq, "permission_request", buildJsonObject {
        put("kind", "agent_chat"); put("request", "invite"); put("room_id", "room-1")
        put("from_device_id", 4); put("target_device_id", target)
        put("from_name", "box-a"); put("to_name", "box-b")
    })

    private fun marker(
        seq: Long, action: String, item: String = "it_1", ask: String? = "room-1/7",
        outcome: String? = null, note: String? = null, awaiting: String? = null,
        resolution: String? = null, decidedBy: String? = null, consent: String? = "chat",
        title: String = "Chat request",
    ) = event(seq, "item", buildJsonObject {
        put("item_id", item); put("num", 1); put("kind", "question"); put("title", title)
        put("by", "agent"); put("action", action)
        ask?.let { put("consent_ask", it) }; outcome?.let { put("consent_outcome", it) }
        awaiting?.let { put("awaiting", it) }; resolution?.let { put("resolution", it) }
        consent?.let { put("consent", it) }; decidedBy?.let { put("decided_by", it) }
        note?.let { putJsonObject("comment") { put("id", "c$seq"); put("body", it) } }
    })

    private class Answerer : AgentChatAnswering {
        var calls = 0
        override suspend fun answerAgentChat(roomID: String, targetDeviceID: Long, decision: AgentChatDecision): Boolean {
            calls++
            return true
        }
    }

    private suspend fun TestScope.startVM(
        items: List<TimelineItem>, answerer: Answerer = Answerer(),
        store: KeyValueStore = InMemoryKeyValueStore(),
    ): Pair<ChatViewModel, MutableStateFlow<List<TimelineItem>>> {
        val snapshots = MutableStateFlow(items)
        val timeline = object : TimelineService by FakeTimelineService() {
            override fun items() = snapshots
        }
        val vm = ChatViewModel("room-1", timeline, FakeMediaService(), backgroundScope, store, agentChat = answerer)
        vm.start()
        runCurrent()
        return vm to snapshots
    }

    private fun ChatViewModel.itemRows() = rows.value.filterIsInstance<TimelineRow.Message>()
        .map { it.item }.filter { it.kind is TimelineItem.Kind.ItemMarker }

    @Test fun aDecisionOnAnotherDeviceSettlesTheChatCard() = runTest {
        val pending = listOf(card(), marker(11, "created", awaiting = "user"))
        val (vm, snapshots) = startVM(pending)
        assertEquals(AgentChatCardState.Idle, vm.agentChatState("10"))
        snapshots.value = pending + marker(12, "closed", outcome = "approved", resolution = "decided")
        runCurrent()
        assertEquals(AgentChatCardState.Answered(true), vm.agentChatState("10"))
    }

    @Test fun anItemHasOneCardInItsLatestState() = runTest {
        val (vm) = startVM(listOf(
            marker(11, "created", awaiting = "user"),
            marker(12, "closed", outcome = "approved", resolution = "decided"),
        ))
        val rows = vm.itemRows()
        assertEquals(listOf("12"), rows.map { it.id })
        assertEquals(ItemMarkerEvent.Action.CLOSED, (rows.single().kind as TimelineItem.Kind.ItemMarker).marker.action)
    }
    private fun coordinatorDecision(seq: Long, target: Long? = 7, approve: Boolean = true, kind: String = "chat") =
        event(seq, "consent_decision", buildJsonObject {
            put("kind", kind); put("decision", if (approve) "approve" else "decline")
            put("room_id", "room-1"); target?.let { put("target_device_id", it) }
            put("reason", "Matches the request")
        })

    @Test fun remoteDecisionsSurviveAFreshViewModelAndHistoryLoadedLater() = runTest {
        for (approved in listOf(true, false)) {
            val closed = marker(12, "closed", outcome = if (approved) "approved" else "denied", resolution = "decided")
            val history = listOf(card(), marker(11, "created", awaiting = "user"), closed)
            val (fresh) = startVM(history)
            assertEquals(AgentChatCardState.Answered(approved), fresh.agentChatState("10"))
            val (paged, snapshots) = startVM(listOf(closed))
            snapshots.value = history
            runCurrent()
            assertEquals(AgentChatCardState.Answered(approved), paged.agentChatState("10"))
            assertEquals(listOf("12"), paged.itemRows().map { it.id })
        }
    }

    @Test fun coordinatorDecisionWithoutAnItemSettlesOnlyTheNamedCard() = runTest {
        val (vm, snapshots) = startVM(listOf(card(), card(20, target = 8)))
        val resolutions = mutableListOf<Map<String, AgentChatCardState>>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vm.agentChatResolutions.collect { resolutions.add(it) }
        }
        snapshots.value += coordinatorDecision(21, approve = false)
        runCurrent()
        assertEquals(AgentChatCardState.Answered(false, byCoordinator = true), vm.agentChatState("10"))
        assertEquals(AgentChatCardState.Idle, vm.agentChatState("20"))
        assertEquals(vm.agentChatState("10"), resolutions.last()["10"])
    }

    @Test fun structuredOutcomeAndCoordinatorAttributionWinOverClosingCopy() = runTest {
        val (vm) = startVM(listOf(card(), marker(11, "created"), marker(
            12, "closed", outcome = "denied", decidedBy = "coordinator", note = "Approved — old copy",
        )))
        assertEquals(AgentChatCardState.Answered(false, byCoordinator = true), vm.agentChatState("10"))
    }

    @Test fun legacyClosingNotesStillResolveCards() = runTest {
        val cases = mapOf(
            "Approved — the invitation is on its way." to AgentChatCardState.Answered(true),
            "Declined." to AgentChatCardState.Answered(false),
            "Approved by the Coordinator — Matches the request." to AgentChatCardState.Answered(true, true),
            "Declined by the Coordinator — Outside the request." to AgentChatCardState.Answered(false, true),
            "Expired — no answer within 24 h." to AgentChatCardState.Expired,
            "The room was closed." to AgentChatCardState.Expired,
            "Closed — the request is no longer waiting for an answer." to AgentChatCardState.Expired,
        )
        for ((note, expected) in cases) {
            val (vm) = startVM(listOf(card(), marker(11, "created", ask = null), marker(12, "closed", ask = null, note = note)))
            assertEquals(note, expected, vm.agentChatState("10"))
        }
    }

    @Test fun lapsedAsksBecomeReadOnly() = runTest {
        for (outcome in listOf("expired", "left", "gone")) {
            val (vm) = startVM(listOf(card(), marker(11, "created"), marker(12, "closed", outcome = outcome)))
            assertEquals(outcome, AgentChatCardState.Expired, vm.agentChatState("10"))
        }
    }

    @Test fun manuallyClosingTheMirrorDoesNotAnswerTheAsk() = runTest {
        for ((ask, note) in listOf("room-1/7" to "Declined for now, ask later", null to "Will look tomorrow", null to null)) {
            val (vm) = startVM(listOf(card(), marker(11, "created", ask = ask), marker(
                12, "closed", ask = null, note = note, resolution = "done",
            )))
            assertEquals(AgentChatCardState.Idle, vm.agentChatState("10"))
        }
    }

    @Test fun unaddressedAndUnrelatedDecisionsLeaveTheAskAlone() = runTest {
        val (vm) = startVM(listOf(
            card(), marker(11, "created", consent = null),
            marker(12, "closed", consent = null, outcome = "approved"),
            coordinatorDecision(13, target = null), coordinatorDecision(14, target = 8),
            coordinatorDecision(15, kind = "spawn"),
        ))
        assertEquals(AgentChatCardState.Idle, vm.agentChatState("10"))
    }

    @Test fun aLaterSweepCannotUndoADecisionOrItsCoordinatorBadge() = runTest {
        for (coordinator in listOf(false, true)) {
            val (vm) = startVM(listOf(
                card(), marker(11, "created"),
                marker(12, "closed", outcome = "denied", decidedBy = if (coordinator) "coordinator" else null),
                marker(13, "reopened"), marker(14, "closed", ask = null, outcome = "gone"),
            ))
            assertEquals(AgentChatCardState.Answered(false, coordinator), vm.agentChatState("10"))
        }
        val (vm) = startVM(listOf(card(), marker(11, "created"), coordinatorDecision(12), marker(13, "closed", outcome = "approved")))
        assertEquals(AgentChatCardState.Answered(true, true), vm.agentChatState("10"))
    }

    @Test fun renewedAsksDoNotInheritTheOldItemsDecision() = runTest {
        for (ask in listOf("room-1/7", null)) {
            val (vm, snapshots) = startVM(listOf(
                card(), marker(11, "created", ask = ask), marker(12, "closed", ask = ask, outcome = if (ask != null) "denied" else null, note = "Declined."),
                card(20), marker(21, "created", item = "it_2", ask = ask),
                marker(22, "closed", ask = ask, outcome = "gone"),
            ))
            assertEquals(AgentChatCardState.Answered(false), vm.agentChatState("10"))
            assertEquals(AgentChatCardState.Idle, vm.agentChatState("20"))
            snapshots.value += coordinatorDecision(23)
            runCurrent()
            assertEquals(AgentChatCardState.Answered(true, true), vm.agentChatState("20"))
        }
    }

    @Test fun missingHistoryCannotPairAnOldItemWithARenewedAsk() = runTest {
        val (vm) = startVM(listOf(
            card(20), marker(21, "created", item = "it_2"),
            marker(22, "closed", item = "it_1", outcome = "expired"),
        ))
        assertEquals(AgentChatCardState.Idle, vm.agentChatState("20"))
        val (older) = startVM(listOf(
            card(), marker(11, "created"), marker(21, "created", item = "it_2"),
            marker(22, "closed", item = "it_2", outcome = "approved"),
        ))
        assertEquals(AgentChatCardState.Idle, older.agentChatState("10"))
    }

    @Test fun anUnpairedCardCanResolveFromANamedCloseWithoutACreatedMarker() = runTest {
        val (vm) = startVM(listOf(card(), marker(12, "closed", outcome = "approved")))
        assertEquals(AgentChatCardState.Answered(true), vm.agentChatState("10"))
    }

    @Test fun twoRequestsResolveFromTheirOwnItems() = runTest {
        val (vm) = startVM(listOf(
            card(), marker(11, "created"), card(20, target = 8), marker(21, "created", item = "it_2", ask = "room-1/8"),
            marker(22, "closed", outcome = "denied"),
        ))
        assertEquals(AgentChatCardState.Answered(false), vm.agentChatState("10"))
        assertEquals(AgentChatCardState.Idle, vm.agentChatState("20"))
    }

    @Test fun journalDecisionRefinesALocallyRememberedConflictAndBlocksFurtherTaps() = runTest {
        val store = InMemoryKeyValueStore().apply { setStringList("matron.agentChatAnswers.room-1", listOf("10:expired")) }
        val answerer = Answerer()
        val (vm) = startVM(listOf(card(), coordinatorDecision(12)), answerer, store)
        assertEquals(AgentChatCardState.Answered(true, true), vm.agentChatState("10"))
        val (fresh) = startVM(listOf(card(), coordinatorDecision(12)), answerer)
        fresh.answerAgentChat("10", (card().kind as TimelineItem.Kind.AgentChatRequestCard).request, AgentChatDecision.APPROVE)
        runCurrent()
        assertEquals(0, answerer.calls)
    }

    @Test fun replyNotesUpdateTheSurvivingCardButStayInTheTimeline() = runTest {
        val (vm) = startVM(listOf(
            marker(11, "created", awaiting = "user", consent = null),
            marker(12, "commented", awaiting = "agent", note = "Use A", title = "Updated title", consent = null),
        ))
        val rows = vm.itemRows()
        assertEquals(listOf("11", "12"), rows.map { it.id })
        val marker = (rows[0].kind as TimelineItem.Kind.ItemMarker).marker
        assertEquals(chat.matron.android.models.ItemAwaiting.AGENT, marker.awaiting)
        assertEquals("Updated title", marker.title)
        assertNull(marker.comment)
        assertEquals("Use A", (rows[1].kind as TimelineItem.Kind.ItemMarker).marker.comment?.body)
    }

    @Test fun reopeningRemovesTheDoneStateAndClosingNoteWithoutDuplicatingTheCard() = runTest {
        val (vm) = startVM(listOf(
            marker(11, "created", consent = null),
            marker(12, "closed", resolution = "answered", note = "Use A", consent = null),
            marker(13, "reopened", awaiting = "user", consent = null),
        ))
        val rows = vm.itemRows()
        assertEquals(listOf("12", "13"), rows.map { it.id })
        val shown = (rows[0].kind as TimelineItem.Kind.ItemMarker).marker
        assertEquals(ItemMarkerEvent.Action.CREATED, shown.action)
        assertEquals(chat.matron.android.models.ItemAwaiting.USER, shown.awaiting)
        assertNull(shown.resolution)
        assertNull(shown.comment)
    }

    @Test fun laterNotesKeepAClosedCardClosedAndDifferentItemsStaySeparate() = runTest {
        val (vm) = startVM(listOf(
            marker(11, "created", consent = null),
            marker(12, "closed", resolution = "answered", note = "Use A", consent = null),
            marker(13, "commented", resolution = "answered", note = "Follow up", consent = null),
            marker(14, "created", item = "it_2", awaiting = "user", consent = null),
        ))
        val rows = vm.itemRows()
        assertEquals(listOf("12", "13", "14"), rows.map { it.id })
        val shown = (rows[0].kind as TimelineItem.Kind.ItemMarker).marker
        assertEquals(ItemMarkerEvent.Action.CLOSED, shown.action)
        assertEquals("Use A", shown.comment?.body)
    }

    // MARK: Coordinator badge and Stop session

    private fun spawnCard(seq: Long = 30, requestID: String = "spawn-1") = event(seq, "permission_request", buildJsonObject {
        put("kind", "agent_spawn"); put("request_id", requestID)
        put("from_device_id", 4); put("target_device_id", 7)
        put("from_name", "box-a"); put("target_name", "box-b")
        put("workdir", "/srv/project"); put("task", "Fix the failing build")
    })

    private fun spawnDecision(seq: Long, requestID: String = "spawn-1", approve: Boolean = true) =
        event(seq, "consent_decision", buildJsonObject {
            put("kind", "spawn"); put("decision", if (approve) "approve" else "decline")
            put("request_id", requestID); put("by", "coordinator")
            put("reason", "Same repo as the\nparent")
        })

    private fun started(seq: Long, requestID: String = "spawn-1", child: String? = "child-1") =
        event(seq, "spawn_outcome", buildJsonObject {
            put("request_id", requestID); put("outcome", "started"); put("room_id", "room-9")
            child?.let { put("child_convo_id", it) }
        })

    private class SpawnAnswerer : AgentSpawnAnswering {
        override suspend fun answerAgentSpawn(requestId: String, decision: AgentSpawnDecision) = Unit
    }

    private class Stopper : SessionStopping {
        val stopped = mutableListOf<String>()
        var error: Throwable? = null
        override suspend fun stopSession(convoID: String) {
            stopped += convoID
            error?.let { throw it }
        }
    }

    private suspend fun TestScope.startSpawnVM(
        items: List<TimelineItem>, stopper: Stopper? = Stopper(),
    ): Pair<ChatViewModel, MutableStateFlow<List<TimelineItem>>> {
        val snapshots = MutableStateFlow(items)
        val timeline = object : TimelineService by FakeTimelineService() {
            override fun items() = snapshots
        }
        val vm = ChatViewModel(
            "room-1", timeline, FakeMediaService(), backgroundScope, InMemoryKeyValueStore(),
            agentChat = Answerer(), agentSpawn = SpawnAnswerer(), sessionStopper = stopper,
        )
        vm.start()
        runCurrent()
        return vm to snapshots
    }

    @Test fun badgeTextCarriesTheReasonOnOneLine() {
        val decision = (spawnDecision(31).kind as TimelineItem.Kind.ConsentDecision).decision
        assertEquals("Approved by the Coordinator — Same repo as the parent", decision.badgeText)
        assertEquals(
            "Declined by the Coordinator",
            decision.copy(decision = ConsentDecisionEvent.Decision.DECLINE, reason = " ").badgeText,
        )
    }

    @Test fun aSpawnCardTheCoordinatorAnsweredIsNotAnswerableBeforeTheOutcome() = runTest {
        val request = (spawnCard().kind as TimelineItem.Kind.AgentSpawnRequestCard).request
        val (vm, snapshots) = startSpawnVM(listOf(spawnCard()))
        assertEquals(AgentSpawnCardState.Idle, vm.agentSpawnState("30", request))
        assertNull(vm.coordinatorBadge("30", request))

        snapshots.value += spawnDecision(31)
        runCurrent()
        assertEquals(AgentSpawnCardState.AnsweredByCoordinator(approved = true), vm.agentSpawnState("30", request))
        val badge = vm.coordinatorBadge("30", request)!!
        assertEquals("Approved by the Coordinator — Same repo as the parent", badge.text)
        assertNull("nothing to stop before the session starts", badge.stoppableConvoID)
    }

    @Test fun aDecisionBadgesOnlyTheLatestCardForItsRequest() = runTest {
        val (vm) = startSpawnVM(listOf(spawnCard(30), spawnCard(32), spawnDecision(33)))
        val request = (spawnCard().kind as TimelineItem.Kind.AgentSpawnRequestCard).request
        assertNull(vm.coordinatorBadge("30", request))
        assertEquals(ConsentDecisionEvent.Kind.SPAWN, vm.coordinatorBadge("32", request)!!.decision.kind)
    }

    @Test fun stopSessionOnceStartedSendsOnceAndSettles() = runTest {
        val request = (spawnCard().kind as TimelineItem.Kind.AgentSpawnRequestCard).request
        val stopper = Stopper()
        val (vm) = startSpawnVM(listOf(spawnCard(), spawnDecision(31), started(32)), stopper)
        assertTrue(vm.agentSpawnState("30", request) is AgentSpawnCardState.Resolved)
        val badge = vm.coordinatorBadge("30", request)!!
        assertEquals("child-1", badge.stoppableConvoID)
        assertEquals(SessionStopState.Idle, badge.stop)

        vm.stopSpawnedSession("child-1")
        runCurrent()
        vm.stopSpawnedSession("child-1")
        runCurrent()
        assertEquals(listOf("child-1"), stopper.stopped)
        assertEquals(SessionStopState.Sent, vm.coordinatorBadge("30", request)!!.stop)
    }

    @Test fun aFailedStopCanBeTappedAgain() = runTest {
        val request = (spawnCard().kind as TimelineItem.Kind.AgentSpawnRequestCard).request
        val stopper = Stopper().apply { error = IllegalStateException("disk full") }
        val (vm) = startSpawnVM(listOf(spawnCard(), spawnDecision(31), started(32)), stopper)
        vm.stopSpawnedSession("child-1")
        runCurrent()
        assertTrue(vm.coordinatorBadge("30", request)!!.stop is SessionStopState.Failed)

        stopper.error = null
        vm.stopSpawnedSession("child-1")
        runCurrent()
        assertEquals(listOf("child-1", "child-1"), stopper.stopped)
        assertEquals(SessionStopState.Sent, vm.coordinatorBadge("30", request)!!.stop)
    }

    @Test fun noStopWithoutAChildAStopperOrAnApproval() = runTest {
        val request = (spawnCard().kind as TimelineItem.Kind.AgentSpawnRequestCard).request
        val (noChild) = startSpawnVM(listOf(spawnCard(), spawnDecision(31), started(32, child = null)))
        assertNull(noChild.coordinatorBadge("30", request)!!.stoppableConvoID)
        val (noStopper) = startSpawnVM(listOf(spawnCard(), spawnDecision(31), started(32)), stopper = null)
        assertNull(noStopper.coordinatorBadge("30", request)!!.stoppableConvoID)
        val (declined) = startSpawnVM(listOf(spawnCard(), spawnDecision(31, approve = false)))
        assertEquals(AgentSpawnCardState.AnsweredByCoordinator(approved = false), declined.agentSpawnState("30", request))
        assertNull(declined.coordinatorBadge("30", request)!!.stoppableConvoID)
    }

    @Test fun aChatCardTheCoordinatorAnsweredCarriesTheBadge() = runTest {
        val (vm) = startSpawnVM(listOf(card(), coordinatorDecision(11)))
        val badge = vm.coordinatorBadge("10")!!
        assertEquals("Approved by the Coordinator — Matches the request", badge.text)
        assertNull(badge.stoppableConvoID)
        assertNull("a card the Coordinator did not answer has no badge", startSpawnVM(listOf(card(20, 8), coordinatorDecision(21))).first.coordinatorBadge("20"))
    }
}
