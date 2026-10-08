package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.db.ItemOutboxEntity
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import chat.matron.android.models.TrackerAttachment
import kotlinx.coroutines.launch

/// Ported from matron-apple's `ItemDetailViewModelTests`.
class ItemDetailViewModelTest {
    private open class Sync : FakeItemsSync() {
        /// When set, the next `refreshItem` suspends until [releaseRefresh].
        var holdRefresh = false
        private var gate: CompletableDeferred<Unit>? = null
        val isHeld: Boolean get() = gate != null
        fun releaseRefresh() { val g = gate; gate = null; g?.complete(Unit) }
        /// The order of the store writes a mutation makes: `apply` is the
        /// item the journal handed straight back, `refetch` the follow-up
        /// that brings the comment thread down.
        val log = mutableListOf<String>()
        override suspend fun refreshItem(id: String) {
            log += "refetch"
            if (holdRefresh) { holdRefresh = false; val g = CompletableDeferred<Unit>(); gate = g; g.await() }
            refetched += id
        }
        override suspend fun applyItem(item: TrackerItem) {
            log += "apply"
            super.applyItem(item)
        }
    }

    private class Api : FakeItemsApi() {
        val uploads = mutableListOf<String>()
        /// The byte count of every upload, in order.
        val uploadSizes = mutableListOf<Int>()
        /// Called as each upload starts, before the gate (index from 0).
        var onUploadStart: (Int) -> Unit = {}
        private var uploadStarts = 0
        val closes = mutableListOf<Pair<ItemResolution, String?>>()
        var reopens = 0
        var failUpload = false
        /// When set, every upload suspends until it completes; a
        /// completed-exceptionally gate fails the upload.
        var gate: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()
        override suspend fun uploadMedia(data: ByteArray, contentType: String, progress: ((Double) -> Unit)?): String {
            onUploadStart(uploadStarts++)
            started.complete(Unit)
            gate?.await()
            uploadSizes += data.size
            if (failUpload) throw JournalApiError.Transport("upload failed")
            uploads += contentType
            return "blob-${uploads.size}"
        }
        override suspend fun closeItem(id: String, resolution: ItemResolution, comment: String?): TrackerItem {
            closes += resolution to comment
            return TrackerItem(id = id, num = 1, kind = ItemKind.TASK, state = ItemState.CLOSED, title = "", originConvoID = "c1")
        }
        override suspend fun reopenItem(id: String, comment: String?): TrackerItem {
            reopens += 1
            return TrackerItem(id = id, num = 1, kind = ItemKind.TASK, title = "", originConvoID = "c1")
        }
    }

    private val staging: File = Files.createTempDirectory("staging-").toFile()
    private fun picked(name: String, bytes: ByteArray = byteArrayOf(1)): File =
        File(Files.createTempDirectory("picked-").toFile(), name).also { it.writeBytes(bytes) }
    private fun tempNote(bytes: ByteArray): File = Files.createTempFile("v-", ".m4a").toFile().also { it.writeBytes(bytes) }

    @Test
    fun loadedCommentCountIsSetFromTheStoreOnceTheOpeningRefetchCompletes() = runBlocking {
        val sync = Sync(); val store = FakeItemsStore()
        store.storedComments = listOf(TrackerComment("ic_1", "it_1", ItemAuthor.USER, body = "x"))
        val vm = ItemDetailViewModel("it_1", store, Api(), sync, this, staging)
        assertNull(vm.loadedCommentCount.value)
        vm.start()
        waitUntil { vm.loadedCommentCount.value != null }
        assertEquals(listOf("it_1"), sync.refetched)
        assertEquals("comments are read from the store before the count is set", listOf("ic_1"), vm.comments.value.map { it.id })
        assertEquals(1, vm.loadedCommentCount.value)
        vm.stop()
        vm.start()
        waitUntil { sync.refetched.size == 2 && vm.loadedCommentCount.value == 1 }
        vm.stop()
    }

    /// The comments subscription taken at `start()` may still hold a
    /// pre-refetch snapshot when the refetch completes; it is dropped and
    /// re-taken, so that snapshot can never overwrite the loaded thread.
    @Test
    fun staleSubscriptionIsReplacedOnceTheThreadIsLoaded() = runBlocking {
        val sync = Sync(); val store = FakeItemsStore()
        store.storedComments = listOf(
            TrackerComment("ic_1", "it_1", ItemAuthor.USER, body = "x"),
            TrackerComment("ic_2", "it_1", ItemAuthor.AGENT, body = "y"),
        )
        val vm = ItemDetailViewModel("it_1", store, Api(), sync, this, staging)
        sync.holdRefresh = true
        vm.start()
        waitUntil { store.commentsSubscriptions == 1 && sync.isHeld }
        sync.releaseRefresh()
        waitUntil { vm.loadedCommentCount.value == 2 }
        assertEquals("the opening subscription was dropped and re-taken", 2, store.commentsSubscriptions)
        assertEquals(listOf("ic_1", "ic_2"), vm.comments.value.map { it.id })
        store.comments.emit(store.storedComments + TrackerComment("ic_3", "it_1", ItemAuthor.USER, body = "z"))
        waitUntil { vm.comments.value.size == 3 }
        vm.stop()
    }

    @Test
    fun submitUploadsThenEnqueuesAndClearsDraft() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = " use A "
        vm.attachFiles(listOf(picked("s.png")))
        vm.submitComment()
        assertEquals(listOf("image/png"), api.uploads)
        assertEquals("use A", sync.comments.first().second)
        assertEquals("blob-1", sync.comments.first().third.first().blobRef)
        assertEquals("s.png", sync.comments.first().third.first().name)
        assertEquals("", vm.draft)
        vm.submitComment()
        assertEquals("empty draft + empty tray is a no-op", 1, sync.comments.size)
    }

    /// Picking a photo/file stages it in the tray — nothing is uploaded or
    /// posted, and the draft is left alone, until the user sends.
    @Test
    fun attachFilesStagesIntoTheTrayWithoutPosting() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = "still composing this"
        vm.attachFiles(listOf(picked("a.png"), picked("b.txt")))
        assertEquals(listOf("a.png", "b.txt"), vm.stagedAttachments.value.map { it.filename })
        assertEquals(listOf("image/png", "text/plain"), vm.stagedAttachments.value.map { it.mimeType })
        assertTrue(api.uploads.isEmpty())
        assertTrue(sync.comments.isEmpty())
        assertEquals("still composing this", vm.draft)
    }

    @Test
    fun removeAttachmentDropsItFromTheTrayAndDeletesTheCopy() = runBlocking {
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), Api(), Sync(), this, staging)
        vm.attachFiles(listOf(picked("a.png"), picked("b.txt")))
        val first = vm.stagedAttachments.value.first()
        vm.removeAttachment(first.id)
        assertEquals(listOf("b.txt"), vm.stagedAttachments.value.map { it.filename })
        assertFalse(first.file.exists())
    }

    @Test
    fun anUnreadablePickIsReportedAndStagesNothing() = runBlocking {
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), Api(), Sync(), this, staging)
        vm.attachFiles(listOf(File(staging, "missing.png")))
        assertTrue(vm.stagedAttachments.value.isEmpty())
        assertNotNull(vm.error.value)
    }

    /// Send posts the text and the whole tray as ONE comment, in tray order,
    /// then empties the tray and deletes the staged copies.
    @Test
    fun submitSendsTheDraftAndTheTrayAsOneComment() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = "look at these"
        vm.attachFiles(listOf(picked("a.png"), picked("b.txt")))
        val staged = vm.stagedAttachments.value
        vm.submitComment()
        assertEquals(1, sync.comments.size)
        val (_, body, attachments) = sync.comments.single()
        assertEquals("look at these", body)
        assertEquals(listOf("a.png", "b.txt"), attachments.map { it.name })
        assertEquals(listOf("image/png", "text/plain"), attachments.map { it.mime })
        assertEquals(listOf(1L, 1L), attachments.map { it.size })
        assertTrue(vm.stagedAttachments.value.isEmpty())
        assertEquals("", vm.draft)
        assertTrue("sent copies are cleaned up", staged.none { it.file.exists() })
    }

    @Test
    fun submitWithOnlyATraySendsAnAttachmentOnlyComment() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.attachFiles(listOf(picked("a.png")))
        vm.submitComment()
        assertEquals("", sync.comments.single().second)
        assertEquals(listOf("a.png"), sync.comments.single().third.map { it.name })
    }

    @Test
    fun tapActionQueuesTheLabelAsAnAnswerToThatComment() = runBlocking {
        val sync = Sync(); val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, Api(), sync, this, staging)
        val asking = TrackerComment("ic_q", "it_1", ItemAuthor.AGENT, body = "Merge it?", actions = listOf("Merge", "Wait"))
        vm.start()
        store.item.emit(TrackerItem(id = "it_1", num = 1, kind = ItemKind.TASK, awaiting = ItemAwaiting.USER, title = "T", originConvoID = "c1"))
        waitUntil { vm.item.value != null }
        vm.tapAction(asking, "Nope")
        assertTrue("a label the comment does not offer is ignored", sync.taps.isEmpty())
        vm.tapAction(asking, "Wait")
        assertEquals(listOf(Triple("it_1", "Wait", "ic_q")), sync.taps)
        assertEquals("the comment's body is the label", "Wait", sync.comments.single().second)
        // The same answer already on its way is not queued twice — read from
        // the store, so it holds before the pending flow has caught up.
        store.storedOutbox = listOf(outboxTap("L1", "Wait", "ic_q"))
        assertTrue("the flow has not emitted the row", vm.pendingComments.value.isEmpty())
        vm.tapAction(asking, "Wait")
        assertEquals(1, sync.taps.size)
        // A different answer is queued.
        vm.tapAction(asking, "Merge")
        assertEquals(listOf("Wait", "Merge"), sync.taps.map { it.second })
        vm.stop()
    }

    @Test
    fun tapActionSkipsTheAnswerThatHasAlreadyLandedButAllowsChangingIt() = runBlocking {
        val sync = Sync(); val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, Api(), sync, this, staging)
        // The view still holds the comment as it was before the tap landed.
        val asking = TrackerComment("ic_q", "it_1", ItemAuthor.AGENT, body = "Merge it?", actions = listOf("Merge", "Wait"))
        store.storedComments = listOf(asking.copy(chosenAction = "Wait"))
        vm.start()
        store.item.emit(TrackerItem(id = "it_1", num = 1, kind = ItemKind.TASK, awaiting = ItemAwaiting.AGENT, title = "T", originConvoID = "c1"))
        waitUntil { vm.item.value != null }
        vm.tapAction(asking, "Wait")
        assertTrue("already the answer", sync.taps.isEmpty())
        vm.tapAction(asking, "Merge")
        assertEquals(listOf("Merge"), sync.taps.map { it.second })
        // Changing back while that change is still queued is a new answer too.
        store.storedOutbox = listOf(outboxTap("L1", "Merge", "ic_q"))
        vm.tapAction(asking, "Wait")
        assertEquals(listOf("Merge", "Wait"), sync.taps.map { it.second })
        vm.stop()
    }

    @Test
    fun overlappingTapsOnOneButtonQueueOneAnswer() = runBlocking {
        val store = FakeItemsStore()
        // The enqueue is slow, and only once it returns is the row in the outbox.
        val sync = object : Sync() {
            override suspend fun enqueueComment(
                itemID: String, localID: String, body: String, attachments: List<TrackerAttachment>, action: String?, replyTo: String?,
            ): Boolean {
                kotlinx.coroutines.delay(50)
                super.enqueueComment(itemID, localID, body, attachments, action, replyTo)
                store.storedOutbox = store.storedOutbox + outboxTap(localID, action!!, replyTo!!)
                return true
            }
        }
        val vm = ItemDetailViewModel("it_1", store, Api(), sync, this, staging)
        val asking = TrackerComment("ic_q", "it_1", ItemAuthor.AGENT, body = "Merge it?", actions = listOf("Merge", "Wait"))
        vm.start()
        store.item.emit(TrackerItem(id = "it_1", num = 1, kind = ItemKind.TASK, awaiting = ItemAwaiting.USER, title = "T", originConvoID = "c1"))
        waitUntil { vm.item.value != null }
        val first = launch { vm.tapAction(asking, "Merge") }
        val second = launch { vm.tapAction(asking, "Merge") }
        first.join(); second.join()
        assertEquals(listOf("Merge"), sync.taps.map { it.second })
        vm.stop()
    }

    @Test
    fun tapActionIsIgnoredOnAClosedItem() = runBlocking {
        val sync = Sync(); val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, Api(), sync, this, staging)
        val asking = TrackerComment("ic_q", "it_1", ItemAuthor.AGENT, body = "Merge it?", actions = listOf("Merge"))
        vm.tapAction(asking, "Merge")
        assertTrue("not loaded yet", sync.taps.isEmpty())
        vm.start()
        store.item.emit(TrackerItem(id = "it_1", num = 1, kind = ItemKind.TASK, state = ItemState.CLOSED, title = "T", originConvoID = "c1"))
        waitUntil { vm.item.value != null }
        vm.tapAction(asking, "Merge")
        assertTrue(sync.taps.isEmpty())
        vm.stop()
    }

    @Test
    fun pendingTapsReadTheOutboxLatestWinsAndSkipTypedReplies() {
        val rows = listOf(
            outboxTap("L1", "Wait", "ic_q"),
            ItemOutboxEntity("L2", "it_1", ItemOutboxEntity.OP_COMMENT, """{"body":"typed"}""", 2, 0, null),
            outboxTap("L3", "Merge", "ic_q"),
            outboxTap("L4", "Now", "ic_q2"),
            ItemOutboxEntity("L5", "it_1", ItemOutboxEntity.OP_COMMENT, "not json", 5, 0, null),
        )
        assertEquals(mapOf("ic_q" to "Merge", "ic_q2" to "Now"), ItemDetailViewModel.pendingTaps(rows))
        assertTrue(ItemDetailViewModel.pendingTaps(emptyList()).isEmpty())
    }

    @Test
    fun tapItemActionQueuesSeenOnTheItemItself() = runBlocking {
        val sync = Sync(); val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, Api(), sync, this, staging)
        val notice = TrackerItem(
            id = "it_1", num = 1, kind = ItemKind.NOTICE, awaiting = ItemAwaiting.USER, title = "Deploy finished",
            originConvoID = "c1", actions = listOf(TrackerItem.SEEN_ACTION),
        )
        vm.start()
        store.item.emit(notice)
        waitUntil { vm.item.value != null }
        assertTrue("a notice has no close menu: Seen is the way", vm.availableResolutions.isEmpty())
        vm.tapItemAction("Nope")
        assertTrue("a label the item does not offer is ignored", sync.taps.isEmpty())
        vm.tapItemAction("Seen")
        assertEquals("an item-level tap names no comment", listOf(Triple("it_1", "Seen", null as String?)), sync.taps)
        assertEquals("Seen", sync.comments.single().second)
        // Already on its way: not queued twice.
        store.storedOutbox = listOf(outboxItemTap("L1", "Seen"))
        assertEquals("Seen", ItemDetailViewModel.pendingItemTap(store.storedOutbox))
        assertNull("a comment tap is not an item tap", ItemDetailViewModel.pendingItemTap(listOf(outboxTap("L2", "Go", "ic_q"))))
        vm.tapItemAction("Seen")
        assertEquals(1, sync.taps.size)
        // Once the journal's close lands, the buttons are a record.
        store.storedOutbox = emptyList()
        store.item.emit(notice.copy(state = ItemState.CLOSED, resolution = ItemResolution.DONE, awaiting = null, chosenAction = "Seen"))
        waitUntil { vm.item.value?.state == ItemState.CLOSED }
        vm.tapItemAction("Seen")
        assertEquals(1, sync.taps.size)
        vm.stop()
    }

    private fun outboxItemTap(localID: String, label: String) = ItemOutboxEntity(
        localID, "it_1", ItemOutboxEntity.OP_COMMENT, """{"body":"$label","action":"$label"}""", 1, 0, null,
    )

    private fun outboxTap(localID: String, label: String, replyTo: String) = ItemOutboxEntity(
        localID, "it_1", ItemOutboxEntity.OP_COMMENT,
        """{"body":"$label","action":"$label","replyTo":"$replyTo"}""", 1, 0, null,
    )

    @Test
    fun voiceNoteIsAnAudioAttachmentComment() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        val file = tempNote(byteArrayOf(0, 1, 2))
        vm.sendVoiceNote(file)
        assertEquals(listOf("audio/mp4"), api.uploads)
        assertEquals("audio/mp4", sync.comments.first().third.first().mime)
        assertEquals("voice-note.m4a", sync.comments.first().third.first().name)
        assertFalse("the recording is deleted once uploaded", file.exists())
    }

    @Test
    fun questionOffersAnsweredOnlyOnceTheUserHasReplied() {
        assertEquals(listOf(ItemResolution.CANCELLED), ItemDetailViewModel.resolutions(ItemKind.QUESTION, userHasReplied = false))
        assertEquals(listOf(ItemResolution.ANSWERED, ItemResolution.CANCELLED), ItemDetailViewModel.resolutions(ItemKind.QUESTION, userHasReplied = true))
        assertEquals(listOf(ItemResolution.DONE, ItemResolution.CANCELLED), ItemDetailViewModel.resolutions(ItemKind.TASK, userHasReplied = false))
        assertEquals(emptyList<ItemResolution>(), ItemDetailViewModel.resolutions(null, userHasReplied = true))
    }

    @Test
    fun userHasRepliedCountsOnlyTheirOwnComments() = runBlocking {
        val api = Api(); val sync = Sync(); val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, api, sync, this, staging)
        vm.start()
        waitUntil { sync.refetched == listOf("it_1") }
        store.item.emit(TrackerItem(id = "it_1", num = 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, title = "Q", originConvoID = "c1"))
        waitUntil { vm.item.value?.kind == ItemKind.QUESTION }
        assertEquals(listOf(ItemResolution.CANCELLED), vm.availableResolutions)
        // An agent comment and a status row are not a reply from the user.
        store.comments.emit(
            listOf(
                TrackerComment("a", "it_1", ItemAuthor.AGENT, body = "Options are…"),
                TrackerComment("s", "it_1", ItemAuthor.USER, kind = TrackerComment.Kind.STATUS, body = ""),
            ),
        )
        waitUntil { vm.comments.value.size == 2 }
        assertEquals(listOf(ItemResolution.CANCELLED), vm.availableResolutions)
        store.comments.emit(listOf(TrackerComment("u", "it_1", ItemAuthor.USER, body = "Keep it.")))
        waitUntil { vm.comments.value.size == 1 }
        assertEquals(listOf(ItemResolution.ANSWERED, ItemResolution.CANCELLED), vm.availableResolutions)
        vm.stop()
    }

    /// A reply the user just sent sits in the outbox until the thread catches
    /// up — it is still their reply, so the question must not read as
    /// unanswered in the meantime.
    @Test
    fun aPendingReplyCountsAsHavingReplied() = runBlocking {
        val api = Api(); val sync = Sync(); val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, api, sync, this, staging)
        vm.start()
        waitUntil { sync.refetched == listOf("it_1") }
        store.item.emit(TrackerItem(id = "it_1", num = 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, title = "Q", originConvoID = "c1"))
        waitUntil { vm.item.value?.kind == ItemKind.QUESTION }
        assertEquals(listOf(ItemResolution.CANCELLED), vm.availableResolutions)
        store.outbox.emit(listOf(ItemOutboxEntity("L1", "it_1", "comment", """{"body":"Keep it.","attachments":[]}""", 0, 0, null)))
        waitUntil { vm.pendingComments.value.size == 1 }
        assertEquals(listOf(ItemResolution.ANSWERED, ItemResolution.CANCELLED), vm.availableResolutions)
        vm.stop()
    }

    @Test
    fun closeReopenReverseAndResolutions() = runBlocking {
        val api = Api(); val sync = Sync(); val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, api, sync, this, staging)
        vm.start()
        // Opening the detail screen must itself trigger a refetch (that's
        // the only path comments reach the local cache).
        waitUntil { sync.refetched == listOf("it_1") }
        store.item.emit(TrackerItem(id = "it_1", num = 1, kind = ItemKind.DECISION, title = "D", originConvoID = "c1"))
        waitUntil { vm.item.value?.kind == ItemKind.DECISION }
        assertEquals(listOf(ItemResolution.REVERSED, ItemResolution.DECIDED, ItemResolution.CANCELLED), vm.availableResolutions)
        vm.reverse()
        assertEquals(ItemResolution.REVERSED, api.closes.first().first)
        vm.reopen()
        assertEquals(1, api.reopens)
        assertEquals(listOf("it_1", "it_1", "it_1"), sync.refetched)
        assertFalse(vm.isBusy.value)
        vm.stop()
    }

    /// A failed send puts the text and the tray back for another go.
    @Test
    fun submitUploadFailureRestoresTheDraftAndTrayAndSetsError() = runBlocking {
        val api = Api(); val sync = Sync()
        api.failUpload = true
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = "keep me"
        vm.attachFiles(listOf(picked("a.png")))
        val staged = vm.stagedAttachments.value
        vm.submitComment()
        assertNotNull(vm.error.value)
        assertEquals("keep me", vm.draft)
        assertEquals(staged, vm.stagedAttachments.value)
        assertTrue(staged.single().file.exists())
        assertTrue(sync.comments.isEmpty())
        assertFalse(vm.isBusy.value)
    }

    /// The draft and tray clear as the send starts (so the field is ready for
    /// the next reply), and a late failure does not overwrite what the user
    /// typed meanwhile — the tray still comes back.
    @Test
    fun sendClearsAtStartAndAFailureDoesNotOverwriteNewText() = runBlocking {
        val api = Api(); val sync = Sync()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = "first"
        vm.attachFiles(listOf(picked("a.png")))
        val job = launch { vm.submitComment() }
        api.started.await()
        assertEquals("", vm.draft)
        assertTrue(vm.stagedAttachments.value.isEmpty())
        vm.draft = "typed meanwhile"
        api.failUpload = true
        gate.complete(Unit)
        job.join()
        assertEquals("typed meanwhile", vm.draft)
        assertEquals(listOf("a.png"), vm.stagedAttachments.value.map { it.filename })
        assertTrue(sync.comments.isEmpty())
    }

    /// A voice note with a draft is ONE comment: the text as its body, the
    /// recording first, then the tray.
    @Test
    fun voiceNoteCarriesTheDraftAndTheTrayAsOneComment() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = " see recording "
        vm.attachFiles(listOf(picked("a.png"), picked("b.txt")))
        val file = tempNote(byteArrayOf(0, 1, 2))
        vm.sendVoiceNote(file)
        assertEquals(1, sync.comments.size)
        val (_, body, attachments) = sync.comments.single()
        assertEquals("see recording", body)
        assertEquals(listOf("voice-note.m4a", "a.png", "b.txt"), attachments.map { it.name })
        assertEquals(listOf("audio/mp4", "image/png", "text/plain"), attachments.map { it.mime })
        assertEquals(listOf("audio/mp4", "image/png", "text/plain"), api.uploads)
        assertEquals("", vm.draft)
        assertTrue(vm.stagedAttachments.value.isEmpty())
        assertFalse("the recording's temp file is gone", file.exists())
    }

    /// An upload failure must not destroy the recording: it goes back in the
    /// tray (first, ahead of the rest) with the draft restored, ready to send.
    @Test
    fun sendVoiceNoteUploadFailurePutsTheRecordingInTheTrayAndRestoresTheDraft() = runBlocking {
        val api = Api(); val sync = Sync()
        api.failUpload = true
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = "about this"
        vm.attachFiles(listOf(picked("a.png")))
        vm.sendVoiceNote(tempNote(byteArrayOf(0, 1, 2)))
        assertNotNull(vm.error.value)
        assertTrue(sync.comments.isEmpty())
        assertEquals("about this", vm.draft)
        val tray = vm.stagedAttachments.value
        assertEquals(listOf("voice-note.m4a", "a.png"), tray.map { it.filename })
        assertEquals("audio/mp4", tray.first().mimeType)
        assertTrue("the recording survives a failed upload", tray.first().file.exists())

        // ...and sending again posts it, the recording still first.
        api.failUpload = false
        vm.submitComment()
        assertEquals(listOf("voice-note.m4a", "a.png"), sync.comments.single().third.map { it.name })
        assertEquals("about this", sync.comments.single().second)
    }

    /// Leaving the screen mid-upload (its composition scope cancelled) must
    /// not drop the reply: the send runs on the view model's session scope,
    /// so the upload finishes and the comment reaches the outbox anyway.
    @Test
    fun leavingTheScreenMidSendStillEnqueuesTheReply() = runBlocking {
        val api = Api(); val sync = Sync()
        api.gate = CompletableDeferred()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = "keep this"
        vm.attachFiles(listOf(picked("a.png")))
        val staged = vm.stagedAttachments.value
        val screen = launch { vm.submitComment() }
        api.started.await()
        screen.cancel(); screen.join()
        api.gate!!.complete(Unit)
        waitUntil { sync.comments.isNotEmpty() && !vm.isBusy.value }
        assertEquals("keep this", sync.comments.single().second)
        assertEquals(listOf("a.png"), sync.comments.single().third.map { it.name })
        assertTrue(vm.stagedAttachments.value.isEmpty())
        assertTrue("sent copies are cleaned up", staged.none { it.file.exists() })
    }

    @Test
    fun leavingTheScreenMidVoiceNoteSendStillEnqueuesIt() = runBlocking {
        val api = Api(); val sync = Sync()
        api.gate = CompletableDeferred()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = "about this"
        vm.attachFiles(listOf(picked("a.png")))
        val screen = launch { vm.sendVoiceNote(tempNote(byteArrayOf(0, 1, 2))) }
        api.started.await()
        screen.cancel(); screen.join()
        api.gate!!.complete(Unit)
        waitUntil { sync.comments.isNotEmpty() && !vm.isBusy.value }
        assertEquals("about this", sync.comments.single().second)
        assertEquals(listOf("voice-note.m4a", "a.png"), sync.comments.single().third.map { it.name })
    }

    /// The session itself ending (sign-out cancels the view model's scope)
    /// mid-upload: what the user made comes back rather than vanishing.
    @Test
    fun aCancelledVoiceNoteSendRestoresTheDraftAndTray() = runBlocking {
        val api = Api(); val sync = Sync()
        api.gate = CompletableDeferred()
        val session = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, session, staging)
        vm.draft = "about this"
        vm.attachFiles(listOf(picked("a.png")))
        val screen = launch { vm.sendVoiceNote(tempNote(byteArrayOf(0, 1, 2))) }
        api.started.await()
        session.cancel()
        withTimeout(5_000) { screen.join() }
        assertEquals("about this", vm.draft)
        assertEquals(listOf("voice-note.m4a", "a.png"), vm.stagedAttachments.value.map { it.filename })
        assertTrue(sync.comments.isEmpty())
        assertFalse(vm.isBusy.value)
    }

    /// Attachments are read and uploaded one at a time, so at most one is
    /// held in memory: a staged copy that changes while an earlier one is
    /// uploading goes out as it is when ITS turn comes, not as it was when
    /// the send started.
    @Test
    fun attachmentsAreReadOneAtATimeAsEachUploads() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.attachFiles(listOf(picked("a.png"), picked("b.txt")))
        val second = vm.stagedAttachments.value[1].file
        api.onUploadStart = { index -> if (index == 0) second.writeBytes(ByteArray(5)) }
        vm.submitComment()
        assertEquals(listOf(1, 5), api.uploadSizes)
        assertEquals(listOf(1L, 5L), sync.comments.single().third.map { it.size })
    }

    /// Staging the recording can fail (a full disk): it then goes out on its
    /// own straight from the temp file, leaving the draft alone.
    @Test
    fun voiceNoteThatCannotBeStagedGoesOutAlone() = runBlocking {
        val api = Api(); val sync = Sync()
        val notADirectory = Files.createTempFile("staging-", ".file").toFile()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, notADirectory)
        vm.draft = "still composing this"
        val file = tempNote(byteArrayOf(0, 1, 2))
        vm.sendVoiceNote(file)
        assertEquals("", sync.comments.single().second)
        assertEquals(listOf("voice-note.m4a"), sync.comments.single().third.map { it.name })
        assertEquals("still composing this", vm.draft)
        assertFalse(file.exists())
        notADirectory.delete()
        Unit
    }

    /// Unlike an upload failure, an empty recording will read the same way
    /// again — nothing worth keeping the temp file around for.
    @Test
    fun sendVoiceNoteEmptyRecordingDeletesFileAndSetsError() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        val file = tempNote(byteArrayOf())
        vm.sendVoiceNote(file)
        assertNotNull(vm.error.value)
        assertFalse("an empty recording's temp file must not be orphaned", file.exists())
        assertTrue(sync.comments.isEmpty())
        assertTrue(api.uploads.isEmpty())
    }

    @Test
    fun closeAndReopenLandTheReturnedItemBeforeTheRefetch() = runBlocking {
        val api = Api(); val sync = Sync(); val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, api, sync, this, staging)
        // No `start()`: nothing is feeding the store's item flow, which is
        // exactly the case the refetch can't rescue — it swallows its failures.
        vm.close(ItemResolution.DONE, null)
        assertEquals("the returned item lands before the refetch", listOf("apply", "refetch"), sync.log)
        assertEquals(listOf(ItemState.CLOSED), sync.applied.map { it.state })
        assertEquals("the screen shows the item as closed straight away", ItemState.CLOSED, vm.item.value?.state)

        sync.log.clear()
        vm.reopen()
        assertEquals(listOf("apply", "refetch"), sync.log)
        assertEquals(ItemState.OPEN, sync.applied.last().state)
        assertEquals(ItemState.OPEN, vm.item.value?.state)
        assertNull(vm.error.value)
    }

    @Test
    fun closeWithCommentPassesCommentThrough() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.close(ItemResolution.DONE, "why")
        assertEquals(ItemResolution.DONE to "why", api.closes.first())
    }

    /// The journal takes at most 20 attachments on one comment: the tray stops
    /// there, and a voice note on a full tray goes alone, leaving it in place.
    @Test
    fun aFullTrayStopsAtTwentyAndAVoiceNoteThenGoesAlone() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = "still here"
        vm.attachFiles((0 until 21).map { picked("f$it.png") })
        assertEquals(ItemDetailViewModel.MAX_COMMENT_ATTACHMENTS, vm.stagedAttachments.value.size)
        assertNotNull(vm.error.value)

        vm.sendVoiceNote(tempNote(byteArrayOf(1, 2, 3)))

        assertEquals(1, sync.comments.size)
        assertEquals(listOf("voice-note.m4a"), sync.comments.single().third.map { it.name })
        assertEquals("still here", vm.draft)
        assertEquals(ItemDetailViewModel.MAX_COMMENT_ATTACHMENTS, vm.stagedAttachments.value.size)
    }

    /// A restore lands on [ItemDetailViewModel.draftFlow], so a field that was
    /// remounted while the send was in flight picks the words back up.
    @Test
    fun aFailedSendRestoresTheDraftThroughTheFlow() = runBlocking {
        val api = Api(); val sync = Sync()
        api.failUpload = true
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this, staging)
        vm.draft = "keep me"
        vm.attachFiles(listOf(picked("a.png")))
        vm.submitComment()
        waitUntil { !vm.isBusy.value }
        assertEquals("keep me", vm.draftFlow.value)
    }
}
