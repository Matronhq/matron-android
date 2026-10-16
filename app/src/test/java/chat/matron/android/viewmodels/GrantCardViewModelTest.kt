package chat.matron.android.viewmodels

import chat.matron.android.chat.FakeMediaService
import chat.matron.android.chat.FakeTimelineService
import chat.matron.android.events.GrantCardState
import chat.matron.android.events.GrantDecision
import chat.matron.android.events.KeepLevel
import chat.matron.android.events.PersonGrantRequest
import chat.matron.android.journal.GrantAnswering
import chat.matron.android.journal.JournalApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// The chat view model's handling of person-to-person grant cards.
class GrantCardViewModelTest {
    private fun vmTest(body: suspend CoroutineScope.(CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try { body(scope) } finally { scope.cancel() }
        Unit
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            delay(5)
        }
    }

    private class Recorder(
        private val error: Throwable? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : GrantAnswering {
        val calls = mutableListOf<Triple<String, GrantDecision, List<String>>>()
        val keepLevels = mutableListOf<KeepLevel?>()
        override suspend fun answerGrant(grantId: String, decision: GrantDecision, excludeMedia: List<String>, keepLevel: KeepLevel?) {
            calls.add(Triple(grantId, decision, excludeMedia))
            keepLevels.add(keepLevel)
            gate?.await()
            error?.let { throw it }
        }
    }

    private val request = PersonGrantRequest(
        kind = PersonGrantRequest.Kind.HANDOVER,
        direction = PersonGrantRequest.Direction.OUT,
        grantId = "gr_1",
        granteeName = "bob",
    )

    private fun vm(scope: CoroutineScope, store: InMemoryKeyValueStore, grants: GrantAnswering?) =
        ChatViewModel("!r:s", FakeTimelineService(), FakeMediaService(), scope, store, grants = grants)

    @Test
    fun readOnlyWithNoAnswerer() = vmTest { scope ->
        assertEquals(GrantCardState.Unavailable, vm(scope, InMemoryKeyValueStore(), null).grantState("10"))
    }

    @Test
    fun approvePassesTheUntickedFilesAndIsRememberedAcrossViewModels() = vmTest { scope ->
        val store = InMemoryKeyValueStore()
        val gate = CompletableDeferred<Unit>()
        val answerer = Recorder(gate = gate)
        val first = vm(scope, store, answerer)
        assertEquals(GrantCardState.Idle, first.grantState("10"))

        first.answerGrant("10", request, GrantDecision.APPROVE, listOf("md_2"))
        assertEquals(GrantCardState.Sending, first.grantState("10"))
        // A second tap while in flight is ignored.
        first.answerGrant("10", request, GrantDecision.DECLINE)
        gate.complete(Unit)
        waitUntil { first.grantState("10") is GrantCardState.Answered }

        assertEquals(listOf(Triple("gr_1", GrantDecision.APPROVE, listOf("md_2"))), answerer.calls)
        assertEquals(GrantCardState.Answered(true), first.grantState("10"))
        // A fresh view model for the same room reads the answer back.
        assertEquals(GrantCardState.Answered(true), vm(scope, store, Recorder()).grantState("10"))
    }

    @Test
    fun conflictOrNotFoundSettlesAsNoLongerWaiting() = vmTest { scope ->
        for (error in listOf(JournalApiError.Conflict, JournalApiError.NotFound)) {
            val answerer = Recorder(error = error)
            val model = vm(scope, InMemoryKeyValueStore(), answerer)
            model.answerGrant("10", request, GrantDecision.DECLINE)
            waitUntil { model.grantState("10") == GrantCardState.Unavailable }
            model.answerGrant("10", request, GrantDecision.APPROVE)
            assertEquals(1, answerer.calls.size)
        }
    }

    @Test
    fun aFailureLeavesTheCardAnswerable() = vmTest { scope ->
        val answerer = Recorder(error = JournalApiError.Transport("boom"))
        val model = vm(scope, InMemoryKeyValueStore(), answerer)
        model.answerGrant("10", request, GrantDecision.APPROVE)
        waitUntil { model.grantState("10") is GrantCardState.Failed }
        assertTrue((model.grantState("10") as GrantCardState.Failed).message.isNotEmpty())
        model.answerGrant("10", request, GrantDecision.APPROVE)
        waitUntil { answerer.calls.size == 2 }
    }

    @Test
    fun keepLevelStartsFromThePayloadAndSurvivesARebuiltRow() = vmTest { scope ->
        val model = vm(scope, InMemoryKeyValueStore(), Recorder())
        val contribute = request.copy(keepLevel = KeepLevel.CONTRIBUTE)
        assertEquals(KeepLevel.READ, model.grantKeepLevel("10", request))
        assertEquals(KeepLevel.CONTRIBUTE, model.grantKeepLevel("11", contribute))

        model.setGrantKeepLevel("10", KeepLevel.CONTRIBUTE)
        model.setGrantKeepLevel("11", KeepLevel.READ)
        // A rebuilt row asks again with the same (unchanged) payload and
        // gets the user's choice, not the payload's.
        assertEquals(KeepLevel.CONTRIBUTE, model.grantKeepLevel("10", request))
        assertEquals(KeepLevel.READ, model.grantKeepLevel("11", contribute))
        assertEquals(mapOf("10" to KeepLevel.CONTRIBUTE, "11" to KeepLevel.READ), model.grantKeepLevels.value)
    }

    @Test
    fun approveAlwaysSendsTheSwitchValue() = vmTest { scope ->
        val answerer = Recorder()
        val model = vm(scope, InMemoryKeyValueStore(), answerer)
        // Untouched: the payload's value.
        model.answerGrant("10", request, GrantDecision.APPROVE)
        waitUntil { answerer.calls.size == 1 }
        // Switched on.
        model.setGrantKeepLevel("11", KeepLevel.CONTRIBUTE)
        model.answerGrant("11", request, GrantDecision.APPROVE, listOf("md_2"))
        waitUntil { answerer.calls.size == 2 }
        assertEquals(listOf(KeepLevel.READ, KeepLevel.CONTRIBUTE), answerer.keepLevels)
        assertEquals(listOf("md_2"), answerer.calls[1].third)
    }

    @Test
    fun declineAndTheRecipientsAcceptSendNoKeepLevel() = vmTest { scope ->
        val answerer = Recorder()
        val model = vm(scope, InMemoryKeyValueStore(), answerer)
        model.setGrantKeepLevel("10", KeepLevel.CONTRIBUTE)
        model.answerGrant("10", request, GrantDecision.DECLINE)
        waitUntil { answerer.calls.size == 1 }
        val incoming = request.copy(direction = PersonGrantRequest.Direction.IN, keepLevel = KeepLevel.CONTRIBUTE)
        model.answerGrant("11", incoming, GrantDecision.APPROVE)
        waitUntil { answerer.calls.size == 2 }
        assertEquals(listOf<KeepLevel?>(null, null), answerer.keepLevels)
    }
}
