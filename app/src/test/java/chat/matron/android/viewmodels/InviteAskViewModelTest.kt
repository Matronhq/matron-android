package chat.matron.android.viewmodels

import chat.matron.android.chat.FakeMediaService
import chat.matron.android.chat.FakeTimelineService
import chat.matron.android.events.ContactInviteAsk
import chat.matron.android.events.InviteAskState
import chat.matron.android.journal.ContactRefusal
import chat.matron.android.journal.InviteAskAnswer
import chat.matron.android.journal.InviteAskAnswering
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// The chat view model's handling of an agent's invite-link ask.
class InviteAskViewModelTest {
    private val link = "matron://contact?v=1&j=a.example&u=alice&t=${"A".repeat(43)}&fp=${"B".repeat(26)}"
    private val ask = ContactInviteAsk(askId = "ia_1", fromName = "box-a")

    private fun vmTest(body: suspend CoroutineScope.(CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try { body(scope) } finally { scope.cancel() }
        Unit
    }

    private suspend fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            delay(5)
        }
    }

    private class Answerer(private val error: Throwable? = null, private val link: String? = null) : InviteAskAnswering {
        val calls = mutableListOf<Pair<String, Boolean>>()
        override suspend fun answerInviteAsk(askId: String, approve: Boolean): InviteAskAnswer {
            calls.add(askId to approve)
            error?.let { throw it }
            return InviteAskAnswer(if (approve) "approved" else "declined", if (approve) link else null)
        }
    }

    private fun vm(scope: CoroutineScope, store: InMemoryKeyValueStore, answerer: InviteAskAnswering?) =
        ChatViewModel("!r:s", FakeTimelineService(), FakeMediaService(), scope, store, inviteAsks = answerer)

    @Test
    fun readOnlyWithNoAnswerer() = vmTest { scope ->
        assertEquals(InviteAskState.Unavailable, vm(scope, InMemoryKeyValueStore(), null).inviteAskState("10"))
    }

    @Test
    fun approveShowsTheLinkOnceAndNeverStoresIt() = vmTest { scope ->
        val store = InMemoryKeyValueStore()
        val answerer = Answerer(link = link)
        val first = vm(scope, store, answerer)
        assertEquals(InviteAskState.Idle, first.inviteAskState("10"))
        first.answerInviteAsk("10", ask, approve = true)
        waitUntil { first.inviteAskState("10") is InviteAskState.Approved }
        assertEquals(InviteAskState.Approved(link), first.inviteAskState("10"))
        assertEquals(listOf("ia_1" to true), answerer.calls)
        // Nothing written anywhere carries the link.
        assertFalse(store.getStringList("matron.inviteAskAnswers.!r:s").orEmpty().any { it.contains("matron://") })

        first.dismissInviteLink("10")
        assertEquals(InviteAskState.Approved(null), first.inviteAskState("10"))

        // A new view model remembers the answer, not the link.
        assertEquals(InviteAskState.Approved(null), vm(scope, store, answerer).inviteAskState("10"))
    }

    @Test
    fun declineIsRemembered() = vmTest { scope ->
        val store = InMemoryKeyValueStore()
        val first = vm(scope, store, Answerer())
        first.answerInviteAsk("10", ask, approve = false)
        waitUntil { first.inviteAskState("10") == InviteAskState.Declined }
        assertEquals(InviteAskState.Declined, vm(scope, store, Answerer()).inviteAskState("10"))
    }

    @Test
    fun answeredElsewhereOrExpiredSettlesTheCard() = vmTest { scope ->
        val elsewhere = vm(scope, InMemoryKeyValueStore(), Answerer(error = ContactRefusal(409, "not_pending")))
        elsewhere.answerInviteAsk("10", ask, approve = true)
        waitUntil { elsewhere.inviteAskState("10") == InviteAskState.ApprovedElsewhere }

        val expired = vm(scope, InMemoryKeyValueStore(), Answerer(error = ContactRefusal(409, "expired")))
        expired.answerInviteAsk("10", ask, approve = true)
        waitUntil { expired.inviteAskState("10") == InviteAskState.Expired }
    }

    @Test
    fun aRefusedApproveCanBeTriedAgain() = vmTest { scope ->
        val vm = vm(scope, InMemoryKeyValueStore(), Answerer(error = ContactRefusal(409, "too_many_invites")))
        vm.answerInviteAsk("10", ask, approve = true)
        waitUntil { vm.inviteAskState("10") is InviteAskState.Failed }
        val state = vm.inviteAskState("10") as InviteAskState.Failed
        assertEquals("You already have 20 open invites. Withdraw one first.", state.message)
        assertTrue(state.answerable)
    }
}
