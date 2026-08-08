package chat.matron.android.viewmodels

import chat.matron.android.journal.AgentChatAllowanceDTO
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
    var allowances: List<AgentChatAllowanceDTO> = emptyList()
    var pendingError: Throwable? = null
    var answerError: Throwable? = null
    var revokeError: Throwable? = null
    val answers = mutableListOf<Quad>()
    val revokes = mutableListOf<Pair<Long, Long>>()
    var refreshCount = 0

    data class Quad(
        val roomID: String,
        val targetDeviceID: Long,
        val decision: AgentChatDecision,
        val alwaysAllow: Boolean,
    )

    override suspend fun agentChatPending(): List<AgentChatPendingDTO> {
        refreshCount++
        pendingError?.let { throw it }
        return pending
    }

    override suspend fun agentChatAllowances(): List<AgentChatAllowanceDTO> {
        pendingError?.let { throw it }
        return allowances
    }

    override suspend fun answerAgentChat(
        roomID: String,
        targetDeviceID: Long,
        decision: AgentChatDecision,
        alwaysAllow: Boolean,
    ): Boolean {
        answers.add(Quad(roomID, targetDeviceID, decision, alwaysAllow))
        answerError?.let { throw it }
        return true
    }

    override suspend fun revokeAgentChatAllowance(fromDeviceID: Long, targetDeviceID: Long) {
        revokes.add(fromDeviceID to targetDeviceID)
        revokeError?.let { throw it }
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

private fun allowanceRow(from: Long = 4, target: Long = 7) = AgentChatAllowanceDTO(
    fromDeviceID = from,
    targetDeviceID = target,
    fromName = "dev-2",
    targetName = "dev-3",
    createdAt = 1_000,
)

/// Ported from matron-apple's `AgentChatViewModelTests`.
class AgentChatViewModelTest {

    @Test
    fun refresh_loadsBothListsNewestFirst() = runBlocking {
        val api = FakeAgentChatApi()
        api.pending = listOf(pendingRow(room = "old", createdAt = 1), pendingRow(room = "new", createdAt = 9))
        api.allowances = listOf(allowanceRow(from = 1), allowanceRow(from = 2))
        val vm = AgentChatViewModel(api)

        vm.refresh()

        assertEquals(listOf("new", "old"), vm.pending.value.map { it.roomID })
        assertEquals(2, vm.allowances.value.size)
        assertTrue(vm.isSupported.value)
        assertNull(vm.errorMessage.value)
    }

    /// A journal that predates agent chat 404s the route. Two permanently empty
    /// lists would read as "you have nothing pending", which is a different and
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
        assertFalse(api.answers[0].alwaysAllow)
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

    @Test
    fun revoke_sendsTheDirectedPairAndDropsTheRowLocallyFirst() = runBlocking {
        val api = FakeAgentChatApi()
        api.allowances = listOf(allowanceRow(from = 4, target = 7))
        val vm = AgentChatViewModel(api)
        vm.refresh()
        // The server has dropped it; a refetch that fails must not leave the
        // revoked row on screen looking live.
        api.allowances = emptyList()

        vm.revoke(vm.allowances.value[0])

        assertEquals(1, api.revokes.size)
        assertEquals(4L to 7L, api.revokes[0])
        assertTrue(vm.allowances.value.isEmpty())
    }

    @Test
    fun revoke_failureKeepsTheRowAndSaysSo() = runBlocking {
        val api = FakeAgentChatApi()
        api.allowances = listOf(allowanceRow())
        val vm = AgentChatViewModel(api)
        vm.refresh()
        api.revokeError = JournalApiError.Transport("offline")

        vm.revoke(vm.allowances.value[0])

        assertNotNull(vm.errorMessage.value)
    }

    /// A join request self-targets, which is the only thing that tells the two
    /// shapes apart in the pending list — there is no `request` field.
    @Test
    fun headline_distinguishesJoinFromInvite() {
        assertEquals(
            "dev-2 wants to start a chat with another agent.",
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
}
