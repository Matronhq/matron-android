package chat.matron.android.viewmodels

import chat.matron.android.journal.AgentChatDecision
import chat.matron.android.journal.AgentChatPendingDTO
import chat.matron.android.journal.JournalApiError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Records every call and replays scripted outcomes, so the tests can pin
/// exactly what reaches the wire — the point of this whole change is that the
/// old path reached the wrong endpoint entirely.
private class FakeAgentChatApi : AgentChatProviding {
    var pending: List<AgentChatPendingDTO> = emptyList()
    var pendingError: Throwable? = null
    var answerError: Throwable? = null
    val answers = mutableListOf<Answer>()
    var refreshCount = 0

    data class Answer(
        val roomID: String,
        val targetDeviceID: Long,
        val decision: AgentChatDecision,
    )

    override suspend fun agentChatPending(): List<AgentChatPendingDTO> {
        refreshCount++
        pendingError?.let { throw it }
        return pending
    }

    override suspend fun answerAgentChat(
        roomID: String,
        targetDeviceID: Long,
        decision: AgentChatDecision,
    ): Boolean {
        answers.add(Answer(roomID, targetDeviceID, decision))
        answerError?.let { throw it }
        return true
    }
}

private fun pendingRow(
    room: String = "room-1",
    target: Long = 7,
    initiator: Long = 4,
    createdAt: Long = 1_000,
) = AgentChatPendingDTO(
    roomID = room,
    targetDeviceID = target,
    initiatorDeviceID = initiator,
    initiatorName = "dev-2",
    targetName = "dev-3",
    topic = "ci",
    justification = "logs",
    roomTitle = "dev-2 ↔ dev-3",
    createdAt = createdAt,
)

/// Ported from matron-apple's `AgentChatViewModelTests`.
class AgentChatViewModelTest {

    @Test
    fun refresh_loadsPendingNewestFirst() = runBlocking {
        val api = FakeAgentChatApi()
        api.pending = listOf(pendingRow(room = "old", createdAt = 1), pendingRow(room = "new", createdAt = 9))
        val vm = AgentChatViewModel(api)

        vm.refresh()

        assertEquals(listOf("new", "old"), vm.pending.value.map { it.roomID })
        assertTrue(vm.isSupported.value)
        assertNull(vm.errorMessage.value)
    }

    /// A journal that predates agent chat 404s the route. A permanently empty
    /// list would read as "you have nothing pending", which is a different and
    /// misleading claim.
    @Test
    fun refresh_serverWithoutAgentChatIsReportedAsUnsupported() = runBlocking {
        val api = FakeAgentChatApi()
        api.pendingError = JournalApiError.NotFound
        val vm = AgentChatViewModel(api)

        vm.refresh()

        assertFalse(vm.isSupported.value)
        assertNull("an old server is not an error to shout about", vm.errorMessage.value)
    }

    @Test
    fun answer_sendsTheRowsOwnKeyAndRefreshes() = runBlocking {
        val api = FakeAgentChatApi()
        api.pending = listOf(pendingRow(room = "room-9", target = 12))
        val vm = AgentChatViewModel(api)
        vm.refresh()

        vm.answer(vm.pending.value[0], AgentChatDecision.APPROVE)

        assertEquals(1, api.answers.size)
        assertEquals("room-9", api.answers[0].roomID)
        assertEquals(12L, api.answers[0].targetDeviceID)
        assertEquals(AgentChatDecision.APPROVE, api.answers[0].decision)
        assertEquals("the list must re-read after a decision", 2, api.refreshCount)
    }

    /// 409 = the row stopped awaiting an answer between the list load and the
    /// tap (answered on another device, or 24h expired). Nothing the user can
    /// act on, so it refreshes the row away rather than showing an error they
    /// would only retry.
    @Test
    fun answer_conflictIsResolvedQuietly() = runBlocking {
        val api = FakeAgentChatApi()
        api.pending = listOf(pendingRow())
        val vm = AgentChatViewModel(api)
        vm.refresh()
        api.answerError = JournalApiError.Conflict
        api.pending = emptyList()

        vm.answer(pendingRow(), AgentChatDecision.DENY)

        assertNull(vm.errorMessage.value)
        assertTrue(vm.pending.value.isEmpty())
    }

    @Test
    fun answer_failureSurfacesAnError() = runBlocking {
        val api = FakeAgentChatApi()
        api.answerError = JournalApiError.Transport("offline")
        val vm = AgentChatViewModel(api)

        vm.answer(pendingRow(), AgentChatDecision.APPROVE)

        assertNotNull(vm.errorMessage.value)
    }

    /// A join request self-targets, which is the only thing that tells the two
    /// shapes apart in the pending list — there is no `request` field.
    @Test
    fun headline_distinguishesJoinFromInvite() {
        assertEquals(
            "dev-2 wants to start a chat with dev-3.",
            pendingRow(target = 7, initiator = 4).headline,
        )
        assertEquals("dev-2 wants to join a chat.", pendingRow(target = 4, initiator = 4).headline)
    }

    @Test
    fun requesterLabel_fallsBackToTheDeviceID() {
        val row = AgentChatPendingDTO(
            roomID = "r", targetDeviceID = 7, initiatorDeviceID = 4,
            initiatorName = null, targetName = null, topic = null, justification = null,
            roomTitle = "", createdAt = 0,
        )
        assertEquals("Device 4", row.requesterLabel)
    }

    /// A failed refresh must leave the last good list standing rather than
    /// clearing it beside an error saying the load failed.
    @Test
    fun refresh_failedLeavesTheLastGoodListStanding() = runBlocking {
        val api = FakeAgentChatApi()
        api.pending = listOf(pendingRow(room = "first"))
        val vm = AgentChatViewModel(api)
        vm.refresh()

        api.pending = listOf(pendingRow(room = "second"))
        api.pendingError = JournalApiError.Transport("offline")
        vm.refresh()

        assertEquals(listOf("first"), vm.pending.value.map { it.roomID })
        assertNotNull(vm.errorMessage.value)
    }

    /// `refresh()` runs from a LaunchedEffect, i.e. after the first frame — an
    /// unguarded empty list tells the user they have no pending requests a
    /// beat before their pending requests arrive.
    @Test
    fun hasLoaded_isFalseUntilTheFirstLoadSettles() = runBlocking {
        val api = FakeAgentChatApi()
        val vm = AgentChatViewModel(api)
        assertFalse(vm.hasLoaded.value)

        api.pendingError = JournalApiError.Transport("offline")
        vm.refresh()
        assertFalse("a failed load has not established that the lists are empty", vm.hasLoaded.value)

        api.pendingError = null
        vm.refresh()
        assertTrue(vm.hasLoaded.value)
    }

    /// The state the screen reads to tell "still fetching" from "tried and
    /// failed". Both look like `hasLoaded == false`, and "Loading…" shown for
    /// the second one claims we are still trying when we have already given up.
    @Test
    fun failedFirstLoad_isNotStillLoading() = runBlocking {
        val api = FakeAgentChatApi()
        api.pendingError = JournalApiError.Transport("offline")
        val vm = AgentChatViewModel(api)

        vm.refresh()

        assertFalse(vm.hasLoaded.value)
        assertFalse("a spinner here would outlive the attempt it stands for", vm.isLoading.value)
        assertNotNull("which leaves the error and its retry as the whole screen", vm.errorMessage.value)
    }
}
