package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemWorking
import chat.matron.android.models.ItemWorkingIndicator
import chat.matron.android.models.QueuedReplyState
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// A reply of the user's that waits on the agent's busy queue reads as queued
/// in the item thread, with the card's own Send now, Cancel and Edit. The
/// bridge's `queued_release` card names the reply it holds (`item: {item_id,
/// comment_id}`) and its release row settles it.
class ItemQueuedRepliesTest {
    private fun card(
        seq: Long,
        prompt: String,
        comment: String,
        item: String = "it_1",
        sendOne: Boolean = false,
        editMode: String? = "resend",
        sender: String = "agent:box-a",
    ): JournalEvent {
        val payload = buildJsonObject {
            put("kind", "queued_release")
            put("prompt_id", prompt)
            put("items", buildJsonArray { add(buildJsonObject { put("id", "$prompt::0"); put("text", "Item #12") }) })
            put(
                "actions",
                JsonArray(listOfNotNull("send", if (sendOne) "send_one" else null, "cancel").map { buildJsonObject { put("id", it) } }),
            )
            put("item", buildJsonObject { put("item_id", item); put("comment_id", comment) })
            if (editMode != null) put("edit", buildJsonObject { put("mode", editMode); put("hold_seconds", 600) })
        }
        return JournalEvent(seq, "c1", Instant.ofEpochSecond(seq), sender, JournalEventType.PROMPT, payload)
    }

    private fun release(seq: Long, prompt: String, action: String, sender: String = "agent:box-a") = JournalEvent(
        seq, "c1", Instant.ofEpochSecond(seq), sender, JournalEventType.PROMPT_REPLY,
        JsonObject(mapOf("kind" to JsonPrimitive("queued_release"), "prompt_id" to JsonPrimitive(prompt), "action" to JsonPrimitive(action))),
    )

    // MARK: Derivation

    @Test
    fun aLiveCardMarksItsReplyQueuedWithTheCardsSeq() {
        assertEquals(
            mapOf("ic_1" to QueuedReplyState.Queued("c1", 10, offersSendOne = true)),
            ItemQueuedReplies.derive(listOf(card(10, "pr_a", "ic_1", sendOne = true)), "it_1"),
        )
    }

    @Test
    fun releasesSettleTheReply() {
        val rows = listOf(
            card(10, "pr_sent", "ic_sent"), card(11, "pr_one", "ic_one"),
            card(12, "pr_cancel", "ic_cancel"), card(13, "pr_exp", "ic_exp"),
            release(20, "pr_sent", "send"), release(21, "pr_one", "send_one"),
            release(22, "pr_cancel", "cancel"), release(23, "pr_exp", "expired"),
        )
        assertEquals(
            mapOf("ic_cancel" to QueuedReplyState.Cancelled, "ic_exp" to QueuedReplyState.NotDelivered),
            ItemQueuedReplies.derive(rows, "it_1"),
        )
    }

    @Test
    fun theEarliestReleaseWins() {
        val rows = listOf(card(10, "pr_a", "ic_1"), release(20, "pr_a", "send"), release(21, "pr_a", "expired"))
        assertEquals(emptyMap<String, QueuedReplyState>(), ItemQueuedReplies.derive(rows, "it_1"))
    }

    @Test
    fun onlyThisItemsBridgeCardsCount() {
        val rows = listOf(
            card(10, "pr_a", "ic_other", item = "it_2"),
            card(11, "pr_b", "ic_user", sender = "user:alice"),
            card(12, "pr_c", "ic_1"),
            release(20, "pr_c", "send", sender = "user:alice"),
        )
        assertEquals(mapOf("ic_1" to QueuedReplyState.Queued("c1", 12, false)), ItemQueuedReplies.derive(rows, "it_1"))
    }

    @Test
    fun onlyALiveResendCardMakesItsReplyEditable() {
        val rows = listOf(
            card(10, "pr_a", "ic_live"),
            card(11, "pr_b", "ic_gone"),
            card(12, "pr_c", "ic_old", editMode = null),
            card(13, "pr_d", "ic_replace", editMode = "replace"),
            release(20, "pr_b", "send"),
        )
        assertEquals(setOf("ic_live"), ItemQueuedReplies.editable(rows, "it_1"))
        assertEquals(emptySet<String>(), ItemQueuedReplies.editable(rows, "it_2"))
    }

    // MARK: The thread

    private val staging: File = Files.createTempDirectory("staging-").toFile()

    /// Records each card answer; [onSend] lets a test land the bridge's release.
    private class Release(var fail: Boolean = false, val onSend: (String) -> Unit = {}) : QueuedReplySending {
        val sent = mutableListOf<Triple<String, Long, String>>()
        /// When set, the next send waits here until completed.
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun sendQueuedRelease(convoID: String, targetSeq: Long, choice: String) {
            gate?.let { gate = null; it.await() }
            if (fail) throw JournalApiError.Transport("offline")
            sent += Triple(convoID, targetSeq, choice)
            onSend(choice)
        }
    }

    private val reply = TrackerComment("ic_1", "it_1", ItemAuthor.USER, body = "Use the blue one")
    private val item = TrackerItem(id = "it_1", num = 12, kind = ItemKind.QUESTION, title = "Which colour?", originConvoID = "c1")

    private suspend fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!cond()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            delay(5)
        }
    }

    private suspend fun started(store: FakeItemsStore, vm: ItemDetailViewModel) {
        store.storedComments = listOf(reply)
        store.comments.emit(listOf(reply))
        store.item.emit(item)
        vm.start()
        waitUntil { vm.loadedCommentCount.value == 1 && vm.queuedReplies.value.isNotEmpty() }
    }

    @Test
    fun sendNowAnswersTheCardWithSendOneWhereItCanAndSettlesOnTheRelease() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1", sendOne = true))
        val release = Release { store.queuedRows.value = store.queuedRows.value + release(20, "pr_a", "send_one") }
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, queuedRelease = release)
        started(store, vm)
        vm.sendQueuedReplyNow("ic_1")
        assertEquals(listOf(Triple("c1", 10L, "send_one")), release.sent)
        waitUntil { vm.queuedReplies.value.isEmpty() }
        vm.stop()
    }

    @Test
    fun cancelMarksTheReplyCancelledOnceTheBridgeConfirms() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val release = Release { store.queuedRows.value = store.queuedRows.value + release(20, "pr_a", "cancel") }
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, queuedRelease = release)
        started(store, vm)
        vm.cancelQueuedReply("ic_1")
        assertEquals("cancel", release.sent.single().third)
        waitUntil { vm.queuedReplies.value["ic_1"] == QueuedReplyState.Cancelled }
        vm.stop()
    }

    @Test
    fun anUnconfirmedSendNowComesBackTappable() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val vm = ItemDetailViewModel(
            "it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging,
            queuedRelease = Release(), sendNowConfirmTimeoutMillis = 50,
        )
        started(store, vm)
        vm.sendQueuedReplyNow("ic_1")
        val state = vm.queuedReplies.value["ic_1"]
        assertTrue(state is QueuedReplyState.SendFailed && state.canSendNow)
        vm.stop()
    }

    @Test
    fun aFailedSendNowSaysSo() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, queuedRelease = Release(fail = true))
        started(store, vm)
        vm.sendQueuedReplyNow("ic_1")
        assertEquals("Still queued. Couldn't reach the journal. Try again.",
            chat.matron.android.designsystem.itemReplyDeliveryText(vm.queuedReplies.value.getValue("ic_1")))
        vm.stop()
    }

    @Test
    fun editHoldsTheReplyThenSendingCancelsTheOriginalAndPostsTheEditInItsPlace() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val release = Release { choice ->
            if (choice == "cancel") store.queuedRows.value = store.queuedRows.value + release(20, "pr_a", "cancel")
        }
        val sync = FakeItemsSync()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), sync, this, staging, queuedRelease = release)
        started(store, vm)
        vm.draft = "half-typed"
        vm.editQueuedReply("ic_1")
        assertEquals("edit", release.sent.last().third)
        assertEquals("ic_1", vm.editingQueuedReplyID.value)
        assertEquals("Use the blue one", vm.draft)
        vm.draft = "Use the green one"
        vm.submitComment()
        assertEquals(listOf("edit", "cancel"), release.sent.map { it.third })
        assertEquals("Use the green one", sync.comments.single().second)
        assertEquals(listOf<String?>("ic_1"), sync.replacing)
        assertNull(vm.editingQueuedReplyID.value)
        assertNull(vm.error.value)
        vm.stop()
    }

    @Test
    fun cancelEditLetsTheReplyGoAsItWasAndGivesBackWhatWasTyped() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val release = Release()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, queuedRelease = release)
        started(store, vm)
        vm.draft = "half-typed"
        vm.editQueuedReply("ic_1")
        vm.cancelQueuedReplyEdit()
        assertEquals(listOf("edit", "edit_cancel"), release.sent.map { it.third })
        assertEquals("half-typed", vm.draft)
        assertNull(vm.editingQueuedReplyID.value)
        vm.stop()
    }

    @Test
    fun anUnconfirmedCancelKeepsTheEditInTheBox() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val sync = FakeItemsSync()
        val vm = ItemDetailViewModel(
            "it_1", store, FakeItemsApi(), sync, this, staging,
            queuedRelease = Release(), sendNowConfirmTimeoutMillis = 100,
        )
        started(store, vm)
        vm.editQueuedReply("ic_1")
        vm.draft = "Use the green one"
        vm.submitComment()
        assertTrue(sync.comments.isEmpty())
        assertEquals("Use the green one", vm.draft)
        assertEquals("ic_1", vm.editingQueuedReplyID.value)
        assertTrue(vm.error.value!!.contains("hasn't confirmed"))
        vm.stop()
    }

    @Test
    fun aReplyLeavingTheQueueMidEditEndsTheEdit() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, queuedRelease = Release())
        started(store, vm)
        vm.editQueuedReply("ic_1")
        store.queuedRows.value = store.queuedRows.value + release(20, "pr_a", "send")
        waitUntil { vm.editingQueuedReplyID.value == null }
        assertEquals("Use the blue one", vm.draft)
        assertTrue(vm.error.value!!.contains("left the queue"))
        vm.stop()
    }

    @Test
    fun editAndResendOfACancelledReplyReplacesIt() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"), release(20, "pr_a", "cancel"))
        val sync = FakeItemsSync()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), sync, this, staging, queuedRelease = Release())
        started(store, vm)
        vm.editAndResend("ic_1")
        assertEquals("Use the blue one", vm.draft)
        vm.submitComment()
        assertEquals(listOf<String?>("ic_1"), sync.replacing)
        vm.stop()
    }

    @Test
    fun aRunningTurnWithAWaitingReplyIsBusyElsewhere() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, queuedRelease = Release())
        val running = item.copy(working = ItemWorking(ItemWorking.State.RUNNING, since = Instant.now()))
        store.storedComments = listOf(reply)
        store.comments.emit(listOf(reply))
        store.item.emit(running)
        store.ownerSession.emit("running")
        vm.start()
        waitUntil { vm.workingIndicator.value == ItemWorkingIndicator.BUSY_ELSEWHERE }
        launch { store.queuedRows.value = store.queuedRows.value + release(20, "pr_a", "send") }
        waitUntil { vm.workingIndicator.value == ItemWorkingIndicator.WORKING }
        vm.stop()
    }

    @Test
    fun withoutASenderNothingIsDerived() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging)
        store.item.emit(item)
        vm.start()
        waitUntil { vm.item.value != null }
        assertTrue(vm.queuedReplies.value.isEmpty())
        vm.stop()
    }

    @Test
    fun cancelEditWaitsForTheHoldToLand() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val release = Release()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, queuedRelease = release)
        started(store, vm)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        release.gate = gate
        val editing = launch { vm.editQueuedReply("ic_1") }
        waitUntil { vm.editHoldPending.value }
        vm.cancelQueuedReplyEdit()
        assertEquals("ic_1", vm.editingQueuedReplyID.value)
        gate.complete(Unit)
        editing.join()
        assertTrue(!vm.editHoldPending.value)
        assertEquals("Use the blue one", vm.draft)
        vm.cancelQueuedReplyEdit()
        assertEquals(listOf("edit", "edit_cancel"), release.sent.map { it.third })
        vm.stop()
    }

    @Test
    fun editAndResendWaitsWhileAQueuedReplyIsBeingEdited() = runBlocking {
        val store = FakeItemsStore()
        val cancelled = TrackerComment("ic_2", "it_1", ItemAuthor.USER, body = "Or maybe red")
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"), card(11, "pr_b", "ic_2"), release(20, "pr_b", "cancel"))
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging, queuedRelease = Release())
        store.storedComments = listOf(reply, cancelled)
        store.comments.emit(listOf(reply, cancelled))
        store.item.emit(item)
        vm.start()
        waitUntil { vm.queuedReplies.value.size == 2 }
        vm.editQueuedReply("ic_1")
        vm.editAndResend("ic_2")
        assertEquals("Use the blue one", vm.draft)
        assertNull(vm.replacingCommentID.value)
        vm.cancelQueuedReplyEdit()
        // And the other way round: a resend in the box blocks Edit.
        vm.editAndResend("ic_2")
        assertEquals("ic_2", vm.replacingCommentID.value)
        vm.editQueuedReply("ic_1")
        assertNull(vm.editingQueuedReplyID.value)
        vm.stop()
    }

    @Test
    fun aVoiceNoteSentWhileTheHoldIsOnItsWayGoesBackInTheTray() = runBlocking {
        val store = FakeItemsStore()
        store.queuedRows.value = listOf(card(10, "pr_a", "ic_1"))
        val release = Release()
        val sync = FakeItemsSync()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), sync, this, staging, queuedRelease = release)
        started(store, vm)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        release.gate = gate
        val editing = launch { vm.editQueuedReply("ic_1") }
        waitUntil { vm.editHoldPending.value }
        assertTrue(vm.isBusy.value)
        val note = File.createTempFile("note", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        vm.sendVoiceNote(note)
        assertTrue(sync.comments.isEmpty())
        assertEquals(1, vm.stagedAttachments.value.size)
        assertTrue(vm.error.value!!.contains("still being held"))
        gate.complete(Unit)
        editing.join()
        vm.stop()
    }
}
