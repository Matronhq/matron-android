package chat.matron.android.viewmodels

import chat.matron.android.chat.FakeTimelineService
import chat.matron.android.events.AskUserEvent
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `AskUserSheetViewModelTests`.
class AskUserSheetViewModelTest {

    private fun makeVM(
        event: AskUserEvent,
        timeline: FakeTimelineService = FakeTimelineService(),
        onClose: () -> Unit = {},
    ) = AskUserSheetViewModel(event, "\$prompt-1", timeline, onClose)

    private fun option(id: String, label: String, value: String = label) =
        AskUserEvent.Option(id, label, value)

    // MARK: - text-reply channel

    @Test
    fun send_passesPromptEventID_asInReplyTo() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(AskUserEvent("Which?", AskUserEvent.InputKind.Text, null), fake)
        vm.textInput = "src/main.rs"
        vm.send()
        assertEquals(listOf("src/main.rs"), fake.sentText)
        assertEquals(listOf<String?>("\$prompt-1"), fake.sentInReplyTo)
    }

    @Test
    fun send_choiceReply_usesOptionLabel() = runBlocking {
        val fake = FakeTimelineService()
        val opts = listOf(option("a", "main.rs"), option("b", "lib.rs"))
        val vm = makeVM(AskUserEvent("Which file?", AskUserEvent.InputKind.Choice(opts, false), null), fake)
        vm.selectedChoiceIDs = setOf("b")
        vm.send()
        assertEquals(listOf("lib.rs"), fake.sentText)
    }

    @Test
    fun send_multiChoiceReply_joinsLabels() = runBlocking {
        val fake = FakeTimelineService()
        val opts = listOf(option("a", "Build"), option("b", "Test"), option("c", "Lint"))
        val vm = makeVM(AskUserEvent("Steps?", AskUserEvent.InputKind.MultiChoice(opts, false), null), fake)
        vm.selectedChoiceIDs = setOf("a", "c")
        vm.send()
        // Option order (not selection order) so the reply is stable.
        assertEquals(listOf("Build, Lint"), fake.sentText)
    }

    @Test
    fun send_booleanReply_sendsYesNo() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(AskUserEvent("Proceed?", AskUserEvent.InputKind.Boolean, null), fake)
        vm.booleanAnswer = true
        vm.send()
        assertEquals(listOf("Yes"), fake.sentText)
    }

    @Test
    fun send_isNoop_whenBodyEmpty() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(AskUserEvent("?", AskUserEvent.InputKind.Text, null), fake)
        vm.textInput = "   "
        vm.send()
        assertEquals(emptyList<String>(), fake.sentText)
    }

    // MARK: - double-submit guard

    @Test
    fun send_secondConcurrentCall_isSwallowed() = runBlocking {
        val fake = FakeTimelineService()
        fake.sendDelayNanos = 100_000_000
        val vm = makeVM(AskUserEvent("Q?", AskUserEvent.InputKind.Text, null), fake)
        vm.textInput = "answer"
        val first = async { vm.send() }
        val second = async { vm.send() }
        awaitAll(first, second)
        assertEquals(1, fake.sentText.size)
    }

    @Test
    fun send_afterSuccess_isNoop() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(AskUserEvent("Q?", AskUserEvent.InputKind.Text, null), fake)
        vm.textInput = "answer"
        vm.send()
        vm.send()
        assertEquals(1, fake.sentText.size)
    }

    @Test
    fun send_afterError_allowsRetry() = runBlocking {
        val fake = FakeTimelineService()
        fake.nextSendError = IOException("not connected")
        val vm = makeVM(AskUserEvent("Q?", AskUserEvent.InputKind.Text, null), fake)
        vm.textInput = "answer"
        vm.send()
        assertNotNull(vm.error.value)
        assertEquals(0, fake.sentText.size)

        vm.send()
        assertEquals(1, fake.sentText.size)
    }

    @Test
    fun send_closesSheet_onSuccess() = runBlocking {
        var closed = false
        val vm = makeVM(AskUserEvent("?", AskUserEvent.InputKind.Text, null), onClose = { closed = true })
        vm.textInput = "answer"
        vm.send()
        assertTrue(closed)
    }

    @Test
    fun send_surfacesError_andKeepsSheetOpen() = runBlocking {
        val fake = FakeTimelineService()
        fake.nextSendError = RuntimeException("boom")
        var closed = false
        val vm = makeVM(AskUserEvent("?", AskUserEvent.InputKind.Text, null), fake, onClose = { closed = true })
        vm.textInput = "answer"
        vm.send()
        assertEquals("boom", vm.error.value)
        assertFalse(closed)
        assertFalse(vm.isSending.value)
    }

    // MARK: - button-response channel

    @Test
    fun send_buttonResponse_sendsSelectedValues_notLabels() = runBlocking {
        val fake = FakeTimelineService()
        val opts = listOf(option("a", "Send now", "interrupt"), option("b", "Cancel message 1", "cancel:0"))
        val vm = makeVM(
            AskUserEvent(
                "Queued messages",
                AskUserEvent.InputKind.Choice(opts, false),
                null,
                AskUserEvent.ReplyChannel.CHOICE_REPLY,
            ),
            fake,
        )
        vm.selectedChoiceIDs = setOf("b")
        vm.send()
        assertEquals(1, fake.sentButtonResponses.size)
        assertEquals(listOf("cancel:0"), fake.sentButtonResponses[0].first)
        assertEquals("\$prompt-1", fake.sentButtonResponses[0].second)
        assertEquals(emptyList<String>(), fake.sentText)
    }

    @Test
    fun send_buttonResponse_isNoop_whenNothingSelected() = runBlocking {
        val fake = FakeTimelineService()
        val opts = listOf(option("a", "Yes", "yes"))
        val vm = makeVM(
            AskUserEvent(
                "?",
                AskUserEvent.InputKind.Choice(opts, false),
                null,
                AskUserEvent.ReplyChannel.CHOICE_REPLY,
            ),
            fake,
        )
        vm.send()
        assertEquals(0, fake.sentButtonResponses.size)
    }

    // MARK: - expiry

    @Test
    fun isExpired_isTrue_afterExpiresAt() {
        val vm = makeVM(AskUserEvent("Q", AskUserEvent.InputKind.Text, Instant.now().minusSeconds(1)))
        assertTrue(vm.isExpired)
    }

    @Test
    fun send_isNoop_whenExpired() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(AskUserEvent("Q", AskUserEvent.InputKind.Text, Instant.now().minusSeconds(1)), fake)
        vm.textInput = "answer"
        vm.send()
        assertEquals(emptyList<String>(), fake.sentText)
    }

    @Test
    fun awaitExpiry_callsOnExpire_afterExpiresAt() = runBlocking {
        var didExpire = false
        val vm = makeVM(AskUserEvent("Q?", AskUserEvent.InputKind.Text, Instant.now().plusMillis(100)))
        vm.awaitExpiry { didExpire = true }
        assertTrue(didExpire)
        assertTrue(vm.isExpired)
    }

    @Test
    fun awaitExpiry_isNoop_whenNoExpiresAt() = runBlocking {
        var didExpire = false
        val vm = makeVM(AskUserEvent("Q?", AskUserEvent.InputKind.Text, null))
        vm.awaitExpiry { didExpire = true }
        assertFalse(didExpire)
    }
}
