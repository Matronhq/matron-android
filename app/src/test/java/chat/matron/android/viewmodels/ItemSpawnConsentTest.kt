package chat.matron.android.viewmodels

import chat.matron.android.designsystem.ItemContext
import chat.matron.android.designsystem.ItemDetailModel
import chat.matron.android.designsystem.itemDrawsBody
import chat.matron.android.events.AgentSpawnCardState
import chat.matron.android.events.ItemSpawnConsent
import chat.matron.android.events.SpawnOutcome
import chat.matron.android.journal.AgentSpawnAnswering
import chat.matron.android.journal.AgentSpawnDecision
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemState
import chat.matron.android.models.TrackerItem
import chat.matron.android.models.TrackerLink
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// A spawn consent item's page draws the ask's live card from the origin
/// conversation's own rows, answers it as the chat card does, and leaves the
/// item's text copy of the request out while the card is drawn.
class ItemSpawnConsentTest {
    private val staging: File = Files.createTempDirectory("staging-").toFile()

    private val item = TrackerItem(
        id = "it_1", num = 7, kind = ItemKind.DECISION, title = "Start a session on box-b?",
        body = "box-a asks to start a session on box-b: Run the test suite.", originConvoID = "c1",
        links = listOf(TrackerLink("matron://consent/spawn/sp_1")), actions = listOf("Approve", "Decline"),
    )

    private fun card(seq: Long, requestID: String = "sp_1") = JournalEvent(
        seq, "c1", Instant.ofEpochSecond(seq), "journal", JournalEventType.PERMISSION_REQUEST,
        buildJsonObject {
            put("kind", "agent_spawn"); put("request_id", requestID); put("task", "Run the test suite.")
            put("from_device_id", 1); put("from_name", "box-a"); put("target_device_id", 2); put("target_name", "box-b")
        },
    )

    private fun outcome(seq: Long, outcome: String) = JournalEvent(
        seq, "c1", Instant.ofEpochSecond(seq), "journal", JournalEventType.SPAWN_OUTCOME,
        buildJsonObject { put("request_id", "sp_1"); put("outcome", outcome) },
    )

    private class Answerer(var error: Throwable? = null) : AgentSpawnAnswering {
        val answers = mutableListOf<Pair<String, AgentSpawnDecision>>()
        override suspend fun answerAgentSpawn(requestId: String, decision: AgentSpawnDecision) {
            error?.let { throw it }
            answers += requestId to decision
        }
    }

    private suspend fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            delay(5)
        }
    }

    // MARK: The link

    @Test
    fun onlyASpawnConsentLinkNamesARequest() {
        assertEquals("sp_1", TrackerLink("matron://consent/spawn/sp_1").spawnConsentRequestID)
        assertEquals("sp_1", TrackerLink("MATRON://Consent/spawn/sp_1").spawnConsentRequestID)
        assertNull(TrackerLink("matron://consent/chat/room/2").spawnConsentRequestID)
        assertNull(TrackerLink("matron://consent/spawn/").spawnConsentRequestID)
        assertNull(TrackerLink("matron://consent/spawn/a/b").spawnConsentRequestID)
        assertNull(TrackerLink("https://example.com/consent/spawn/sp_1").spawnConsentRequestID)
        assertEquals("sp_1", item.spawnConsentRequestID)
    }

    // MARK: State

    @Test
    fun theOutcomeOutranksEverything() {
        val started = SpawnOutcome("sp_1", "started")
        assertEquals(
            AgentSpawnCardState.Resolved(started),
            ItemDetailViewModel.spawnState(started, itemIsOpen = true, transient = AgentSpawnCardState.Sending, canAnswer = true),
        )
    }

    @Test
    fun aClosedItemWithoutAnOutcomeIsNoLongerWaitingUnlessThisDeviceSettledIt() {
        assertEquals(
            AgentSpawnCardState.Unavailable,
            ItemDetailViewModel.spawnState(null, itemIsOpen = false, transient = AgentSpawnCardState.Sending, canAnswer = true),
        )
        val approved = AgentSpawnCardState.AnsweredByCoordinator(approved = true)
        assertEquals(approved, ItemDetailViewModel.spawnState(null, itemIsOpen = false, transient = approved, canAnswer = true))
    }

    @Test
    fun anOpenAskIsAnswerableOnlyWithAnAnswerer() {
        assertEquals(AgentSpawnCardState.Idle, ItemDetailViewModel.spawnState(null, true, null, canAnswer = true))
        assertEquals(AgentSpawnCardState.Unavailable, ItemDetailViewModel.spawnState(null, true, null, canAnswer = false))
    }

    // MARK: The body

    @Test
    fun theBodyIsLeftOutOnlyWhileTheCardIsDrawn() {
        fun model(consent: ItemSpawnConsent?) = ItemDetailModel(
            item = item, comments = emptyList(), pending = emptyList(),
            context = ItemContext(missionID = null, missionLabel = null, owner = null),
            availableResolutions = emptyList(), isBusy = false, spawnConsent = consent,
        )
        assertTrue(itemDrawsBody(model(null)))
        assertTrue("not synced yet: the text is all there is", itemDrawsBody(model(ItemSpawnConsent("sp_1", null, AgentSpawnCardState.Idle))))
        val request = chat.matron.android.events.AgentSpawnRequest.parse(card(10).payload)
        assertFalse(itemDrawsBody(model(ItemSpawnConsent("sp_1", request, AgentSpawnCardState.Idle))))
    }

    // MARK: The page

    @Test
    fun theCardIsDrawnFromTheConversationsOwnRows() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, agentSpawn = Answerer())
        store.item.emit(item)
        vm.start()
        waitUntil { vm.spawnConsent.value != null }
        assertNull("no card yet: nothing to approve", vm.spawnConsent.value!!.request)
        store.consentRows.value = listOf(card(9, requestID = "sp_other"), card(10))
        waitUntil { vm.spawnConsent.value?.request != null }
        assertEquals("sp_1", vm.spawnConsent.value!!.request!!.requestId)
        assertEquals(AgentSpawnCardState.Idle, vm.spawnConsent.value!!.state)
        store.consentRows.value = store.consentRows.value + outcome(11, "started")
        waitUntil { vm.spawnConsent.value?.state is AgentSpawnCardState.Resolved }
        vm.stop()
    }

    @Test
    fun approveAnswersTheAskAndWaitsForTheSession() = runBlocking {
        val store = FakeItemsStore()
        val answerer = Answerer()
        val sync = FakeItemsSync()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), sync, this, staging, agentSpawn = answerer)
        store.consentRows.value = listOf(card(10))
        store.item.emit(item)
        vm.start()
        waitUntil { vm.spawnConsent.value?.request != null }
        vm.answerSpawn(approve = true)
        assertEquals(listOf("sp_1" to AgentSpawnDecision.APPROVE), answerer.answers)
        assertEquals(AgentSpawnCardState.AnsweredByCoordinator(approved = true), vm.spawnConsent.value!!.state)
        assertTrue(sync.refetched.contains("it_1"))
        // A second tap does nothing.
        vm.answerSpawn(approve = false)
        assertEquals(1, answerer.answers.size)
        vm.stop()
    }

    @Test
    fun declineSettlesTheCardAsDeclined() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, agentSpawn = Answerer())
        store.consentRows.value = listOf(card(10))
        store.item.emit(item)
        vm.start()
        waitUntil { vm.spawnConsent.value?.request != null }
        vm.answerSpawn(approve = false)
        assertEquals("declined", (vm.spawnConsent.value!!.state as AgentSpawnCardState.Resolved).outcome.outcome)
        vm.stop()
    }

    @Test
    fun aConflictMeansNoLongerWaitingAndOtherErrorsStayAnswerable() = runBlocking {
        val store = FakeItemsStore()
        val answerer = Answerer(error = JournalApiError.Transport("offline"))
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, agentSpawn = answerer)
        store.consentRows.value = listOf(card(10))
        store.item.emit(item)
        vm.start()
        waitUntil { vm.spawnConsent.value?.request != null }
        vm.answerSpawn(approve = true)
        assertTrue(vm.spawnConsent.value!!.state is AgentSpawnCardState.Failed)
        answerer.error = JournalApiError.Conflict
        vm.answerSpawn(approve = true)
        assertEquals(AgentSpawnCardState.Unavailable, vm.spawnConsent.value!!.state)
        vm.stop()
    }

    @Test
    fun withoutItsCardTheAskIsNeverAnswered() = runBlocking {
        val store = FakeItemsStore()
        val answerer = Answerer()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, agentSpawn = answerer)
        store.item.emit(item)
        vm.start()
        waitUntil { vm.spawnConsent.value != null }
        vm.answerSpawn(approve = true)
        assertTrue(answerer.answers.isEmpty())
        assertNotNull(vm.spawnConsent.value)
        vm.stop()
    }

    @Test
    fun anOrdinaryItemHasNoCard() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, agentSpawn = Answerer())
        store.item.emit(item.copy(links = emptyList(), state = ItemState.OPEN))
        vm.start()
        waitUntil { vm.item.value != null }
        assertNull(vm.spawnConsent.value)
        vm.stop()
    }
}
