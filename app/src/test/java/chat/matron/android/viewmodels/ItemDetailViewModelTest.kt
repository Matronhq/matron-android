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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `ItemDetailViewModelTests`.
class ItemDetailViewModelTest {
    private class Sync : FakeItemsSync() {
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
        val closes = mutableListOf<Pair<ItemResolution, String?>>()
        var reopens = 0
        var failUpload = false
        override suspend fun uploadMedia(data: ByteArray, contentType: String, progress: ((Double) -> Unit)?): String {
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

    private fun png() = OutgoingAttachment(byteArrayOf(1), "s.png", "image/png")
    private fun tempNote(bytes: ByteArray): File = Files.createTempFile("v-", ".m4a").toFile().also { it.writeBytes(bytes) }

    @Test
    fun loadedCommentCountIsSetFromTheStoreOnceTheOpeningRefetchCompletes() = runBlocking {
        val sync = Sync(); val store = FakeItemsStore()
        store.storedComments = listOf(TrackerComment("ic_1", "it_1", ItemAuthor.USER, body = "x"))
        val vm = ItemDetailViewModel("it_1", store, Api(), sync, this)
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
        val vm = ItemDetailViewModel("it_1", store, Api(), sync, this)
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
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this)
        vm.draft = " use A "
        vm.submitComment(listOf(png()))
        assertEquals(listOf("image/png"), api.uploads)
        assertEquals("use A", sync.comments.first().second)
        assertEquals("blob-1", sync.comments.first().third.first().blobRef)
        assertEquals("s.png", sync.comments.first().third.first().name)
        assertEquals("", vm.draft)
        vm.submitComment(emptyList())
        assertEquals("empty draft + no attachments is a no-op", 1, sync.comments.size)
    }

    @Test
    fun voiceNoteIsAnAudioAttachmentComment() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this)
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
        val vm = ItemDetailViewModel("it_1", store, api, sync, this)
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
        val vm = ItemDetailViewModel("it_1", store, api, sync, this)
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
        val vm = ItemDetailViewModel("it_1", store, api, sync, this)
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

    @Test
    fun submitUploadFailureSetsErrorAndDoesNotEnqueue() = runBlocking {
        val api = Api(); val sync = Sync()
        api.failUpload = true
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this)
        vm.draft = "keep me"
        vm.submitComment(listOf(png()))
        assertNotNull(vm.error.value)
        assertEquals("keep me", vm.draft)
        assertTrue(sync.comments.isEmpty())
        assertFalse(vm.isBusy.value)
    }

    /// Attaching a file/photo must not post whatever's sitting half-written
    /// in `draft`, and must not clear it.
    @Test
    fun submitAttachmentsLeavesDraftIntactAndEnqueuesEmptyBody() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this)
        vm.draft = "still composing this"
        val ok = vm.submitAttachments(listOf(png()))
        assertTrue(ok)
        assertEquals(listOf("image/png"), api.uploads)
        assertEquals("attachment-only comment has an empty body, not the draft text", "", sync.comments.first().second)
        assertEquals("blob-1", sync.comments.first().third.first().blobRef)
        assertEquals("the in-progress draft is left alone", "still composing this", vm.draft)
    }

    @Test
    fun sendVoiceNoteLeavesDraftIntact() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this)
        vm.draft = "still composing this"
        vm.sendVoiceNote(tempNote(byteArrayOf(0, 1, 2)))
        assertEquals("", sync.comments.first().second)
        assertEquals("still composing this", vm.draft)
    }

    /// An upload failure must not destroy the only copy of the recording.
    @Test
    fun sendVoiceNoteUploadFailureKeepsFileAndSetsError() = runBlocking {
        val api = Api(); val sync = Sync()
        api.failUpload = true
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this)
        val file = tempNote(byteArrayOf(0, 1, 2))
        vm.sendVoiceNote(file)
        assertNotNull(vm.error.value)
        assertTrue("the recording survives a failed upload", file.exists())
        assertTrue(sync.comments.isEmpty())
        file.delete()
        Unit
    }

    /// Unlike an upload failure, an empty recording will read the same way
    /// again — nothing worth keeping the temp file around for.
    @Test
    fun sendVoiceNoteEmptyRecordingDeletesFileAndSetsError() = runBlocking {
        val api = Api(); val sync = Sync()
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this)
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
        val vm = ItemDetailViewModel("it_1", store, api, sync, this)
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
        val vm = ItemDetailViewModel("it_1", FakeItemsStore(), api, sync, this)
        vm.close(ItemResolution.DONE, "why")
        assertEquals(ItemResolution.DONE to "why", api.closes.first())
    }
}
