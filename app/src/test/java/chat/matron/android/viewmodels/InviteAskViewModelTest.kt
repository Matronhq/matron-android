package chat.matron.android.viewmodels

import chat.matron.android.chat.FakeMediaService
import chat.matron.android.chat.FakeTimelineService
import chat.matron.android.chat.JournalTimelineMapper
import chat.matron.android.chat.TimelineItem
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import chat.matron.android.events.ContactInviteAsk
import chat.matron.android.events.InviteAskState
import chat.matron.android.journal.ContactRefusal
import chat.matron.android.journal.InviteAskAnswer
import chat.matron.android.journal.InviteAskAnswering
import chat.matron.android.journal.RevealedInviteLinks
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

    private fun vm(
        scope: CoroutineScope,
        store: InMemoryKeyValueStore,
        answerer: InviteAskAnswering?,
        links: RevealedInviteLinks = RevealedInviteLinks(scope),
        timeline: FakeTimelineService = FakeTimelineService(),
    ) = ChatViewModel("!r:s", timeline, FakeMediaService(), scope, store, inviteAsks = answerer, inviteLinks = links)

    private fun event(seq: Long, type: String, payload: kotlinx.serialization.json.JsonObject): TimelineItem =
        JournalTimelineMapper.timelineItem(
            JournalEvent(seq, "!r:s", Instant.ofEpochSecond(seq), "agent:box-a", type, payload),
            "user:alice", "https://example.com".toHttpUrl(),
        )!!

    /// The ask card (seq 10) and its tracker mirror's close (seq 12), the
    /// way the journal records an approve made on the mirror item.
    private fun approvedTimeline(): FakeTimelineService = FakeTimelineService().apply {
        val card = event(10, "permission_request", parseJsonObjectOrNull(
            """{"kind":"contact_invite","direction":"out","ask_id":"ia_1","from_name":"box-a"}""",
        )!!)
        val close = event(12, "item", buildJsonObject {
            put("item_id", "it_1"); put("num", 1); put("kind", "question"); put("title", "Approve making an invite link")
            put("by", "user"); put("action", "closed"); put("resolution", "decided"); put("consent", "invite")
            putJsonObject("comment") { put("id", "c12"); put("body", "Approved — the link was shown on the device you tapped on.") }
        })
        snapshotsToEmit = listOf(listOf(card, close))
    }

    @Test
    fun readOnlyWithNoAnswerer() = vmTest { scope ->
        assertEquals(InviteAskState.Unavailable, vm(scope, InMemoryKeyValueStore(), null).inviteAskState("10"))
    }

    @Test
    fun approveHandsTheLinkToTheStoreAndNeverStoresIt() = vmTest { scope ->
        val store = InMemoryKeyValueStore()
        val answerer = Answerer(link = link)
        val links = RevealedInviteLinks(scope)
        val first = vm(scope, store, answerer, links)
        assertEquals(InviteAskState.Idle, first.inviteAskState("10"))
        first.answerInviteAsk("10", ask, approve = true)
        waitUntil { first.inviteAskState("10") == InviteAskState.Approved }
        assertEquals(listOf("ia_1" to true), answerer.calls)
        assertEquals(listOf(RevealedInviteLinks.Reveal(link, RevealedInviteLinks.Source.ASK)), links.current.value)
        // Nothing written anywhere carries the link.
        assertFalse(store.getStringList("matron.inviteAskAnswers.!r:s").orEmpty().any { it.contains("matron://") })

        links.done(links.current.value.single())
        assertTrue(links.current.value.isEmpty())
        // A new view model remembers the answer, not the link.
        assertEquals(InviteAskState.Approved, vm(scope, store, answerer).inviteAskState("10"))
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

    @Test
    fun anApproveOnTheTrackerItemHereReadsAsApprovedOnTheCard() = vmTest { scope ->
        val links = RevealedInviteLinks(scope)
        val vm = vm(scope, InMemoryKeyValueStore(), Answerer(), links, approvedTimeline())
        vm.start().join()
        waitUntil { vm.inviteAskResolutions.value.isNotEmpty() }
        // The tracker item's Approve came back with the link on this device.
        links.show(link, RevealedInviteLinks.Source.ASK, askId = "ia_1")
        assertEquals(InviteAskState.Approved, vm.inviteAskState("10", ask))
        assertEquals("Approved", vm.inviteAskState("10", ask).resolvedText)
    }

    @Test
    fun anApproveFromAnotherDeviceStillReadsAsElsewhere() = vmTest { scope ->
        val links = RevealedInviteLinks(scope)
        val vm = vm(scope, InMemoryKeyValueStore(), Answerer(), links, approvedTimeline())
        vm.start().join()
        waitUntil { vm.inviteAskResolutions.value.isNotEmpty() }
        // Another ask approved here does not make this one ours.
        links.show(link, RevealedInviteLinks.Source.ASK, askId = "ia_other")
        assertEquals(InviteAskState.ApprovedElsewhere, vm.inviteAskState("10", ask))
        assertEquals("Approved on another device.", vm.inviteAskState("10", ask).resolvedText)
    }

    @Test
    fun aCardTapAfterApprovingOnTheTrackerItemHereReadsAsApproved() = vmTest { scope ->
        val links = RevealedInviteLinks(scope)
        links.show(link, RevealedInviteLinks.Source.ASK, askId = "ia_1")
        val vm = vm(scope, InMemoryKeyValueStore(), Answerer(error = ContactRefusal(409, "not_pending")), links)
        assertEquals(InviteAskState.Approved, vm.inviteAskState("10", ask))
    }

    @Test
    fun theApprovedHereSetIsMemoryOnlyAndKeepsNoLink() = vmTest { scope ->
        val store = InMemoryKeyValueStore()
        val links = RevealedInviteLinks(scope)
        links.show(link, RevealedInviteLinks.Source.ASK, askId = "ia_1")
        assertEquals(setOf("ia_1"), links.approvedHere.value)
        links.done(links.current.value.single())
        // Done drops the link, never the fact that it was approved here.
        assertEquals(setOf("ia_1"), links.approvedHere.value)
        vm(scope, store, Answerer(), links).inviteAskState("10", ask)
        assertTrue(store.getStringList("matron.inviteAskAnswers.!r:s").orEmpty().isEmpty())
        links.clearAll()
        assertTrue(links.approvedHere.value.isEmpty())
    }
}
