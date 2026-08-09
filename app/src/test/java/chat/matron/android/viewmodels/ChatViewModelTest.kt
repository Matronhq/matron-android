package chat.matron.android.viewmodels

import chat.matron.android.chat.FakeMediaService
import chat.matron.android.chat.FakeTimelineService
import chat.matron.android.chat.TimelineItem
import chat.matron.android.events.AgentChatCardState
import chat.matron.android.events.AgentChatRequest
import chat.matron.android.events.AskUserEvent
import chat.matron.android.journal.AgentChatDecision
import chat.matron.android.models.SessionStatus
import chat.matron.android.models.SessionStatusUpdate
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.platform.Haptics
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `ChatViewModelTests`. Background tasks run on a
/// per-test VM scope sharing the runBlocking event loop (single cooperative
/// thread — MainActor-like); the scope is cancelled on the way out so
/// never-completing tasks (status stream, poll loops) can't hang runBlocking.
///
/// Deviations: the `MatronFileLog` unit test is dropped (no Kotlin file-log —
/// diagnostic only). `snapshotWaitMs` is shortened in paginate tests (the Swift
/// constant is 2.5s; behaviour is identical, only the wait bound changes).
class ChatViewModelTest {

    private fun vmTest(body: suspend CoroutineScope.(CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            body(scope)
        } finally {
            scope.cancel()
        }
        Unit
    }

    private fun textItem(
        id: String,
        body: String = "hi",
        isOwn: Boolean = false,
        timestamp: Instant = Instant.now(),
        inReplyTo: String? = null,
    ) = TimelineItem(
        id = id,
        sender = if (isOwn) "@me:s" else "@a:s",
        timestamp = timestamp,
        kind = TimelineItem.Kind.Text(body, null),
        isOwn = isOwn,
        inReplyToEventID = inReplyTo,
    )

    private fun askItem(id: String, expiresAt: Instant? = null) = TimelineItem(
        id = id,
        sender = "@bot:s",
        timestamp = Instant.now(),
        kind = TimelineItem.Kind.AskUser(id, AskUserEvent("Q?", AskUserEvent.InputKind.Text, expiresAt)),
        isOwn = false,
    )

    private fun answerItem(id: String, promptEventID: String, isOwn: Boolean) = TimelineItem(
        id = id,
        sender = if (isOwn) "@me:s" else "@someone-else:s",
        timestamp = Instant.now(),
        kind = TimelineItem.Kind.AskUserAnswer(promptEventID, listOf("yes")),
        isOwn = isOwn,
    )

    private fun makeVM(
        scope: CoroutineScope,
        timeline: FakeTimelineService = FakeTimelineService(),
        media: FakeMediaService = FakeMediaService(),
        store: KeyValueStore = InMemoryKeyValueStore(),
        roomID: String = "!r:s",
        haptics: Haptics = Haptics.None,
    ) = ChatViewModel(roomID, timeline, media, scope, store, haptics)

    private suspend fun makeAskVM(
        scope: CoroutineScope,
        items: List<TimelineItem>,
        store: KeyValueStore,
    ): ChatViewModel {
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(items)
        val vm = ChatViewModel("!ask-room:s", fake, FakeMediaService(), scope, store)
        vm.start().join()
        return vm
    }

    // MARK: - stream + derived state

    /// Polls [condition] until true or a 2s deadline — the sessionState
    /// collector runs on a real scope, so emissions land asynchronously.
    private suspend fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) delay(10)
        assertTrue(condition())
    }

    @Test
    fun sessionStateRunning_setsIsTurnRunning() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        fake.sessionStatesToEmit = listOf("running")
        val vm = makeVM(scope, fake)
        vm.start().join()
        waitFor { vm.isTurnRunning.value }
    }

    @Test
    fun sessionStateWaiting_clearsIsTurnRunning() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        fake.sessionStatesToEmit = listOf("running", "waiting")
        // Space the emissions so the test observes the armed state first —
        // otherwise the final `false` is indistinguishable from "never armed".
        fake.sessionStateEmitGapMs = 50
        val vm = makeVM(scope, fake)
        vm.start().join()
        waitFor { vm.isTurnRunning.value }
        waitFor { !vm.isTurnRunning.value }
    }

    @Test
    fun streamReceivedItems_appearInState() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(listOf(textItem("1", isOwn = true)))
        val vm = makeVM(scope, fake)
        vm.start().join()
        assertEquals(1, vm.items.value.size)
        assertEquals("1", vm.items.value.first().id)
    }

    @Test
    fun streamCompletion_isObservableViaTask() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        val vm = makeVM(scope, fake)
        val task = vm.start()
        val completed = withTimeoutOrNull(2000) { task.join(); true } ?: false
        assertTrue(completed)
    }

    @Test
    fun contentToEmptySnapshot_triggersHistoryRefill() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(listOf(textItem("1")), emptyList())
        val vm = makeVM(scope, fake)
        vm.snapshotWaitMs = 100
        vm.start().join()
        waitUntil { fake.paginateCalls >= 1 }
        assertTrue(fake.paginateCalls >= 1)
    }

    @Test
    fun rowAnchorIDs_retiredEcho_leavesTheAnchorNamespace() = vmTest { scope ->
        val fake = FakeTimelineService()
        val older = textItem("1", isOwn = true)
        val echo = textItem("echo:abc", "new msg", isOwn = true)
        val delivered = textItem("2", "new msg", isOwn = true)
        fake.snapshotsToEmit = listOf(listOf(older, echo), listOf(older, delivered))
        val vm = makeVM(scope, fake)
        vm.start().join()
        assertTrue(vm.rowAnchorIDs.value.contains("2"))
        assertFalse(vm.rowAnchorIDs.value.contains("echo:abc"))
    }

    @Test
    fun windowedRows_capTheRenderedTail_andExtendReveals() = vmTest { scope ->
        val fake = FakeTimelineService()
        val items = (0 until 200).map { textItem("m$it", "msg $it") }
        fake.snapshotsToEmit = listOf(items)
        val vm = makeVM(scope, fake)
        vm.start().join()

        assertEquals(201, vm.rows.value.size)
        assertEquals(121, vm.windowedRows.value.size)
        val last = vm.windowedRows.value.last()
        assertTrue(last is TimelineRow.Message && last.item.id == "m199")
        assertTrue(vm.windowedRows.value.first() is TimelineRow.Separator)

        vm.extendHistoryWindow()
        assertEquals(201, vm.windowedRows.value.size)

        vm.resetHistoryWindow()
        assertEquals(121, vm.windowedRows.value.size)
        val last2 = vm.windowedRows.value.last()
        assertTrue(last2 is TimelineRow.Message && last2.item.id == "m199")
    }

    @Test
    fun ensureWindowContains_widensToCoverARestoreTarget() = vmTest { scope ->
        val fake = FakeTimelineService()
        val items = (0 until 200).map { textItem("m$it", "msg $it") }
        fake.snapshotsToEmit = listOf(items)
        val vm = makeVM(scope, fake)
        vm.start().join()

        assertNull(vm.windowedRows.value.firstOrNull { it is TimelineRow.Message && it.item.id == "m5" })
        vm.ensureWindowContains("m5")
        assertNotNull(vm.windowedRows.value.firstOrNull { it is TimelineRow.Message && it.item.id == "m5" })
        assertTrue(vm.isExtendingWindow.value)
        delay(400)
        assertFalse(vm.isExtendingWindow.value)
    }

    // MARK: - historyPinTarget (static)

    @Test
    fun historyPinTarget_picksTopmostVisibleMessageRow() {
        assertEquals("42", ChatViewModel.historyPinTarget(listOf("42", "43", "44"), emptyList()))
    }

    @Test
    fun historyPinTarget_skipsSeparatorIDs() {
        assertEquals("7", ChatViewModel.historyPinTarget(listOf("sep:1752537600", "7", "8"), emptyList()))
    }

    @Test
    fun historyPinTarget_fallsBackToFirstMessageRowOfWindow() {
        val item = textItem("m3")
        val pin = ChatViewModel.historyPinTarget(
            listOf("sep:1752537600"),
            listOf(TimelineRow.Separator(Instant.now()), TimelineRow.Message(item)),
        )
        assertEquals("m3", pin)
    }

    @Test
    fun historyPinTarget_nilWhenNothingToPin() {
        assertNull(
            ChatViewModel.historyPinTarget(listOf("sep:1752537600"), listOf(TimelineRow.Separator(Instant.now()))),
        )
        assertNull(ChatViewModel.historyPinTarget(emptyList(), emptyList()))
    }

    // MARK: - activity indicator

    @Test
    fun activityIndicator_excludedFromRows_exposedAsFooterLabel() = vmTest { scope ->
        val fake = FakeTimelineService()
        val msg = textItem("1")
        val activity = TimelineItem(
            "activity", "agent", Instant.now(),
            TimelineItem.Kind.ActivityIndicator("thinking…"), isOwn = false,
        )
        fake.snapshotsToEmit = listOf(listOf(msg, activity))
        val vm = makeVM(scope, fake)
        vm.start().join()

        assertEquals("thinking…", vm.activityLabel.value)
        assertEquals("1", vm.lastRenderableItemID.value)
        assertFalse(vm.rowAnchorIDs.value.contains("activity"))
        assertFalse(
            vm.rows.value.any { it is TimelineRow.Message && it.item.kind is TimelineItem.Kind.ActivityIndicator },
        )
    }

    @Test
    fun activityLabel_clears_whenIndicatorLeavesSnapshot() = vmTest { scope ->
        val fake = FakeTimelineService()
        val msg = textItem("1")
        val activity = TimelineItem(
            "activity", "agent", Instant.now(),
            TimelineItem.Kind.ActivityIndicator("thinking…"), isOwn = false,
        )
        fake.snapshotsToEmit = listOf(listOf(msg, activity), listOf(msg))
        val vm = makeVM(scope, fake)
        vm.start().join()
        assertNull(vm.activityLabel.value)
    }

    @Test
    fun tick_firesOnce_whenActivityGoesRunningThenIdle() = vmTest { scope ->
        val fake = FakeTimelineService()
        val msg = textItem("1")
        val activity = TimelineItem(
            "activity", "agent", Instant.now(),
            TimelineItem.Kind.ActivityIndicator("thinking…"), isOwn = false,
        )
        fake.snapshotsToEmit = listOf(listOf(msg, activity), listOf(msg))
        val haptics = FakeHaptics()
        val vm = makeVM(scope, fake, haptics = haptics)
        vm.start().join()
        assertNull(vm.activityLabel.value)
        assertEquals(1, haptics.tickCount)
    }

    @Test
    fun tick_doesNotFire_whenChatOpensAlreadyIdle() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(listOf(textItem("1")))
        val haptics = FakeHaptics()
        val vm = makeVM(scope, fake, haptics = haptics)
        vm.start().join()
        assertNull(vm.activityLabel.value)
        assertEquals(0, haptics.tickCount)
    }

    @Test
    fun tick_doesNotFire_onTheInitialRunningSnapshot() = vmTest { scope ->
        val fake = FakeTimelineService()
        val activity = TimelineItem(
            "activity", "agent", Instant.now(),
            TimelineItem.Kind.ActivityIndicator("thinking…"), isOwn = false,
        )
        fake.snapshotsToEmit = listOf(listOf(textItem("1"), activity))
        val haptics = FakeHaptics()
        val vm = makeVM(scope, fake, haptics = haptics)
        vm.start().join()
        assertEquals("thinking…", vm.activityLabel.value)
        assertEquals(0, haptics.tickCount)
    }

    @Test
    fun tick_doesNotFire_whenTurnCompletesOffScreenOnACachedVM() = vmTest { scope ->
        // ChatVMCache keeps one ChatViewModel per room for the whole session, and
        // ChatLifecycle calls start() again on every re-entry. _activityLabel is
        // never reset by stop()/start(), so a stale "thinking…" baseline from a
        // prior visit must not be read as a "was running" edge on the first
        // recompute after re-entry — that edge must only fire for a completion
        // the user actually watched while the chat was open.
        val fake = FakeTimelineService()
        val activity = TimelineItem(
            "activity", "agent", Instant.now(),
            TimelineItem.Kind.ActivityIndicator("thinking…"), isOwn = false,
        )
        val msg = textItem("1")
        fake.snapshotsToEmit = listOf(listOf(msg, activity))
        val haptics = FakeHaptics()
        val vm = makeVM(scope, fake, haptics = haptics)
        vm.start().join()
        assertEquals("thinking…", vm.activityLabel.value)
        assertEquals(0, haptics.tickCount)

        // Leave the room while the agent is still "running" (label stays
        // "thinking…" on the cached VM instance), the turn completes off-screen,
        // then re-enter: the re-open's first snapshot is already idle.
        vm.stop()
        fake.snapshotsToEmit = listOf(listOf(msg))
        vm.start().join()

        assertNull(vm.activityLabel.value)
        assertEquals(0, haptics.tickCount)
    }

    @Test
    fun tick_doesNotFire_whenTimelineIsWipedMidTurn() = vmTest { scope ->
        // A mirror wipe (e.g. a resync) replaces a running timeline with an
        // empty snapshot. The trailing indicator vanishes because everything
        // did — not because a turn finished — so no tick should fire.
        val fake = FakeTimelineService()
        val msg = textItem("1")
        val activity = TimelineItem(
            "activity", "agent", Instant.now(),
            TimelineItem.Kind.ActivityIndicator("thinking…"), isOwn = false,
        )
        fake.snapshotsToEmit = listOf(listOf(msg, activity), emptyList())
        val haptics = FakeHaptics()
        val vm = makeVM(scope, fake, haptics = haptics)
        vm.start().join()
        assertNull(vm.activityLabel.value)
        assertEquals(0, haptics.tickCount)
    }

    @Test
    fun scrollMemory_dropsTransientIDs() {
        ChatScrollPositionMemory.resetForTesting()
        ChatScrollPositionMemory.store("!r:s", "echo:ABC")
        assertNull(ChatScrollPositionMemory.retrieve("!r:s"))

        ChatScrollPositionMemory.store("!r:s", "42")
        assertEquals("42", ChatScrollPositionMemory.retrieve("!r:s"))
        ChatScrollPositionMemory.store("!r:s", "activity")
        assertNull(ChatScrollPositionMemory.retrieve("!r:s"))

        ChatScrollPositionMemory.store("!r:s", "eph:msg_011abc")
        assertNull(ChatScrollPositionMemory.retrieve("!r:s"))
    }

    // MARK: - paginate / markAsRead / refresh / sendCommand / retry

    @Test
    fun paginate_invokesService() = vmTest { scope ->
        val fake = FakeTimelineService()
        val vm = makeVM(scope, fake)
        vm.snapshotWaitMs = 50
        vm.paginateBackward()
        assertEquals(1, fake.paginateCalls)
    }

    @Test
    fun markAsRead_invokesService() = vmTest { scope ->
        val fake = FakeTimelineService()
        val vm = makeVM(scope, fake)
        vm.markAsRead()
        assertEquals(1, fake.markReadCalls)
    }

    @Test
    fun start_returnsAfterFirstSnapshot_so_markAsRead_seesItems() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(listOf(textItem("1")))
        val vm = makeVM(scope, fake)
        vm.start()
        assertEquals(1, vm.items.value.size)
        vm.markAsRead()
        assertEquals(1, fake.markReadCalls)
    }

    @Test
    fun start_returnsPromptly_evenWhenStreamYieldsNoSnapshots() = vmTest { scope ->
        val fake = FakeTimelineService()
        val vm = makeVM(scope, fake)
        val completed = withTimeoutOrNull(2000) { vm.start(); true } ?: false
        assertTrue(completed)
    }

    @Test
    fun refresh_invokesPaginateBackward() = vmTest { scope ->
        val fake = FakeTimelineService()
        val vm = makeVM(scope, fake)
        vm.snapshotWaitMs = 50
        vm.refresh()
        assertEquals(1, fake.paginateCalls)
    }

    @Test
    fun paginateError_isRecorded() = vmTest { scope ->
        val fake = FakeTimelineService()
        val vm = makeVM(scope, fake)
        vm.snapshotWaitMs = 50
        vm.paginateBackward()
        assertNull(vm.error.value)
    }

    @Test
    fun sendCommand_sendsBodyAsPlainText() = vmTest { scope ->
        val fake = FakeTimelineService()
        val vm = makeVM(scope, fake)
        vm.sendCommand("/compact")
        assertEquals(listOf("/compact"), fake.sentText)
        assertEquals(listOf<String?>(null), fake.sentInReplyTo)
    }

    @Test
    fun retrySend_forwardsToTimelineService() = vmTest { scope ->
        // The "Tap to retry" affordance must actually reach the service layer
        // (it shipped as a logging-only stub once — the button did nothing).
        val fake = FakeTimelineService()
        val vm = makeVM(scope, fake)
        vm.retrySend("echo:abc")
        waitUntil { fake.retrySendCalls.isNotEmpty() }
        assertEquals(listOf("echo:abc"), fake.retrySendCalls)
    }

    @Test
    fun discardSend_forwardsToTimelineService() = vmTest { scope ->
        val fake = FakeTimelineService()
        val vm = makeVM(scope, fake)
        vm.discardSend("echo:abc")
        waitUntil { fake.discardSendCalls.isNotEmpty() }
        assertEquals(listOf("echo:abc"), fake.discardSendCalls)
    }

    // MARK: - media

    @Test
    fun imageRequest_populatesCacheViaMediaService() = vmTest { scope ->
        val media = FakeMediaService()
        val url = "mxc://example/abc"
        media.stubData[url] = byteArrayOf(1, 2, 3)
        val vm = makeVM(scope, media = media)

        assertNull(vm.image(url))
        waitUntil { vm.resolvedImage(url) != null }
        assertEquals(listOf(url), media.requested)
        assertNotNull(vm.resolvedImage(url))
    }

    @Test
    fun imageRequest_doesNotLoop_whenMediaServiceReturnsNil() = vmTest { scope ->
        val media = FakeMediaService()
        val url = "mxc://example/never-decodes"
        val vm = makeVM(scope, media = media)

        assertNull(vm.image(url))
        waitUntil { media.requested.size >= 1 }
        repeat(5) { assertNull(vm.image(url)) }
        delay(100)
        assertEquals(1, media.requested.size)
    }

    @Test
    fun imageRequest_isCoalescedWhileInFlight() = vmTest { scope ->
        val media = FakeMediaService()
        val url = "mxc://example/abc"
        media.stubData[url] = byteArrayOf(1)
        val vm = makeVM(scope, media = media)

        vm.image(url)
        vm.image(url)
        vm.image(url)
        waitUntil { vm.resolvedImage(url) != null }
        assertEquals(1, media.requested.size)
    }

    @Test
    fun resolvedImageCache_evicts_oldestEntry_whenLimitExceeded() = vmTest { scope ->
        val media = FakeMediaService()
        val vm = makeVM(scope, media = media)
        val limit = ChatViewModel.MEDIA_CACHE_LIMIT
        val urls = (0..limit).map { "mxc://example/$it" }
        urls.forEach { media.stubData[it] = byteArrayOf(1) }
        for (url in urls) {
            vm.image(url)
            waitUntil(5000) { vm.resolvedImage(url) != null }
        }
        assertEquals(limit, vm.resolvedImageCount)
        assertNull(vm.resolvedImage(urls.first()))
        assertNotNull(vm.resolvedImage(urls.last()))
    }

    @Test
    fun failedRequestCache_evicts_oldestEntry_whenLimitExceeded() = vmTest { scope ->
        val media = FakeMediaService()
        val vm = makeVM(scope, media = media)
        val limit = ChatViewModel.MEDIA_CACHE_LIMIT
        val urls = (0..limit).map { "mxc://example/fail/$it" }
        for (url in urls) vm.image(url)
        waitUntil(5000) { vm.failedRequestCount >= limit }
        assertEquals(limit, vm.failedRequestCount)
    }

    @Test
    fun imageRequest_isRetried_afterConnectivityRestored() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        val media = FakeMediaService()
        val url = "mxc://example/retry-me"
        val vm = makeVM(scope, fake, media)
        vm.start().join()

        // First attempt fails (no stub yet) and negative-caches the URL.
        assertNull(vm.image(url))
        waitUntil { media.requested.size >= 1 }
        assertEquals(1, vm.failedRequestCount)

        // Repeat taps don't re-fetch while negative-cached.
        assertNull(vm.image(url))
        delay(50)
        assertEquals(1, media.requested.size)

        // Connectivity returns: the failure cache clears...
        waitUntil { fake.connectionStateFlow.subscriptionCount.value > 0 }
        fake.emitConnectionState(SyncConnectionState.Running)
        waitUntil { vm.failedRequestCount == 0 }

        // ...so the next tap re-fetches, and this time it succeeds.
        media.stubData[url] = byteArrayOf(4, 5, 6)
        assertNull(vm.image(url))
        waitUntil { vm.resolvedImage(url) != null }
        assertEquals(2, media.requested.size)
    }

    // MARK: - writeTempFile

    @Test
    fun writeTempFile_writesFetchedBytes_underAttachmentsDir() = vmTest { scope ->
        val media = FakeMediaService()
        val url = "mxc://example/report"
        media.stubData[url] = byteArrayOf(9, 8, 7)
        val vm = makeVM(scope, media = media)
        val root = java.nio.file.Files.createTempDirectory("write-temp-file").toFile()
        try {
            val file = vm.writeTempFile(url, "report.pdf", root)
            assertNotNull(file)
            assertEquals("report.pdf", file!!.name)
            assertEquals(File(root, "matron-attachments"), file.parentFile)
            assertTrue(byteArrayOf(9, 8, 7).contentEquals(file.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeTempFile_returnsNull_whenFetchFails() = vmTest { scope ->
        val vm = makeVM(scope)
        val root = java.nio.file.Files.createTempDirectory("write-temp-file").toFile()
        try {
            assertNull(vm.writeTempFile("mxc://example/missing", "report.pdf", root))
            // Nothing written on the failure path — not even the subdir.
            assertFalse(File(root, "matron-attachments").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeTempFile_failure_surfacesAttachmentError() = vmTest { scope ->
        val vm = makeVM(scope)
        val root = java.nio.file.Files.createTempDirectory("write-temp-file").toFile()
        try {
            assertNull(vm.attachmentError.value)
            assertNull(vm.writeTempFile("mxc://example/missing", "report.pdf", root))
            assertNotNull(vm.attachmentError.value)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeTempFile_success_doesNotSetAttachmentError() = vmTest { scope ->
        val media = FakeMediaService()
        val url = "mxc://example/report"
        media.stubData[url] = byteArrayOf(1)
        val vm = makeVM(scope, media = media)
        val root = java.nio.file.Files.createTempDirectory("write-temp-file").toFile()
        try {
            assertNotNull(vm.writeTempFile(url, "report.pdf", root))
            assertNull(vm.attachmentError.value)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun dismissAttachmentError_clearsIt() = vmTest { scope ->
        val vm = makeVM(scope)
        val root = java.nio.file.Files.createTempDirectory("write-temp-file").toFile()
        try {
            vm.writeTempFile("mxc://example/missing", "report.pdf", root)
            assertNotNull(vm.attachmentError.value)
            vm.dismissAttachmentError()
            assertNull(vm.attachmentError.value)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun attachmentError_clearedOnRestart_soACachedVMDoesNotResurfaceAStaleError() = vmTest { scope ->
        // ChatVMCache keeps one ChatViewModel per room for the whole session; an
        // undismissed error from a prior visit must not look "fresh" on the next
        // one (the same bug class Task 3 fixed for ComposerViewModel.sendError).
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        val vm = makeVM(scope, fake)
        val root = java.nio.file.Files.createTempDirectory("write-temp-file").toFile()
        try {
            vm.start().join()
            vm.writeTempFile("mxc://example/missing", "report.pdf", root)
            assertNotNull(vm.attachmentError.value)

            // Simulate leaving and re-entering the room: ChatLifecycle calls
            // start() again on the same cached VM instance.
            vm.stop()
            fake.snapshotsToEmit = listOf(emptyList())
            vm.start().join()

            assertNull(vm.attachmentError.value)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeTempFile_traversalFilename_staysInsideAttachmentsDir() = vmTest { scope ->
        val media = FakeMediaService()
        val url = "mxc://example/evil"
        media.stubData[url] = byteArrayOf(1)
        val vm = makeVM(scope, media = media)
        val root = java.nio.file.Files.createTempDirectory("write-temp-file").toFile()
        try {
            val file = vm.writeTempFile(url, "../../escape.txt", root)
            assertNotNull(file)
            assertEquals(File(root, "matron-attachments"), file!!.parentFile)
            assertEquals("escape.txt", file.name)
        } finally {
            root.deleteRecursively()
        }
    }

    // MARK: - sanitisedAttachmentFilename (ported Swift contract)

    @Test
    fun sanitisedFilename_plainName_isPreserved() {
        assertEquals("report.pdf", ChatViewModel.sanitisedAttachmentFilename("report.pdf"))
    }

    @Test
    fun sanitisedFilename_dropsDirectoryTree() {
        assertEquals(
            "authorized_keys",
            ChatViewModel.sanitisedAttachmentFilename("../../.ssh/authorized_keys"),
        )
        assertEquals("notes.txt", ChatViewModel.sanitisedAttachmentFilename("C:\\Users\\x\\notes.txt"))
    }

    @Test
    fun sanitisedFilename_replacesColons() {
        assertEquals("a_b.txt", ChatViewModel.sanitisedAttachmentFilename("a:b.txt"))
    }

    @Test
    fun sanitisedFilename_degenerateInputs_fallBackToNonEmptyName() {
        for (raw in listOf("", "/", "..", ".", "a/..", "   ")) {
            val name = ChatViewModel.sanitisedAttachmentFilename(raw)
            assertTrue("'$raw' should map to a safe non-empty name", name.isNotBlank())
            assertFalse(name.contains('/'))
            assertFalse(name == "." || name == "..")
        }
    }

    // MARK: - first snapshot / rows / stop / error

    @Test
    fun hasReceivedFirstSnapshot_initiallyFalse() = vmTest { scope ->
        val vm = makeVM(scope)
        assertFalse(vm.hasReceivedFirstSnapshot.value)
    }

    @Test
    fun hasReceivedFirstSnapshot_flipsTrue_afterEmptySnapshot() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        val vm = makeVM(scope, fake)
        vm.start().join()
        assertTrue(vm.hasReceivedFirstSnapshot.value)
    }

    @Test
    fun hasReceivedFirstSnapshot_flipsTrue_evenWhenStreamYieldsNothing() = vmTest { scope ->
        val fake = FakeTimelineService()
        val vm = makeVM(scope, fake)
        vm.start().join()
        assertTrue(vm.hasReceivedFirstSnapshot.value)
    }

    @Test
    fun rows_isEmpty_whenItemsEmpty() = vmTest { scope ->
        val vm = makeVM(scope)
        assertTrue(vm.rows.value.isEmpty())
    }

    @Test
    fun rows_interleavesSeparators_betweenCalendarDays() = vmTest { scope ->
        val day1 = LocalDateTime.of(2026, 3, 1, 12, 0).toInstant(ZoneOffset.UTC)
        val day1Later = day1.plusSeconds(4 * 3600)
        val day2 = day1.plusSeconds(24 * 3600)
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(
            listOf(
                textItem("a", "morning", timestamp = day1),
                textItem("b", "afternoon", timestamp = day1Later),
                textItem("c", "next day", timestamp = day2),
            ),
        )
        val vm = makeVM(scope, fake)
        vm.zone = ZoneId.of("UTC")
        vm.start().join()

        val rows = vm.rows.value
        assertEquals(5, rows.size)
        assertTrue(rows[0] is TimelineRow.Separator)
        assertTrue(rows[1] is TimelineRow.Message && (rows[1] as TimelineRow.Message).item.id == "a")
        assertTrue(rows[2] is TimelineRow.Message && (rows[2] as TimelineRow.Message).item.id == "b")
        assertTrue(rows[3] is TimelineRow.Separator)
        assertTrue(rows[4] is TimelineRow.Message && (rows[4] as TimelineRow.Message).item.id == "c")
    }

    @Test
    fun rows_singleSeparator_whenAllItemsSameDay() = vmTest { scope ->
        val base = LocalDateTime.of(2026, 3, 1, 9, 0).toInstant(ZoneOffset.UTC)
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(
            (0 until 3).map { textItem("m$it", "msg $it", timestamp = base.plusSeconds(it * 3600L)) },
        )
        val vm = makeVM(scope, fake)
        vm.zone = ZoneId.of("UTC")
        vm.start().join()
        assertEquals(1, vm.rows.value.count { it is TimelineRow.Separator })
    }

    @Test
    fun stop_cancelsObservationTask() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        val vm = makeVM(scope, fake)
        val task = vm.start()
        vm.stop()
        task.join()
        vm.stop()
    }

    @Test
    fun upstreamStreamError_populatesErrorField() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.streamError = RuntimeException("no timeline for room")
        val vm = makeVM(scope, fake)
        vm.start()
        waitUntil { vm.error.value != null }
        assertEquals("no timeline for room", vm.error.value)
    }

    // MARK: - empty-state debounce

    @Test
    fun settledEmpty_falseAfterTransientClear() = vmTest { scope ->
        val populated = listOf(textItem("\$1"))
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(populated, emptyList(), populated)
        val vm = makeVM(scope, fake)
        vm.emptyPlaceholderGraceMs = 30
        vm.start().join()
        delay(70)
        assertFalse(vm.settledEmpty.value)
    }

    @Test
    fun settledEmpty_trueForGenuinelyEmptyRoom() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        val vm = makeVM(scope, fake)
        vm.emptyPlaceholderGraceMs = 30
        vm.start().join()
        waitUntil { vm.settledEmpty.value }
        assertTrue(vm.settledEmpty.value)
    }

    // MARK: - foreground resume

    @Test
    fun handleForeground_suppressesPlaceholderDuringResync() = vmTest { scope ->
        val vm = makeVM(scope)
        vm.emptyPlaceholderGraceMs = 20
        vm.resumeGraceMs = 600
        vm.handleForeground()
        vm.updateSettledEmpty(true)
        delay(60)
        assertFalse(vm.settledEmpty.value)
        vm.updateSettledEmpty(false)
        assertFalse(vm.settledEmpty.value)
    }

    @Test
    fun handleForeground_showsPlaceholderForGenuinelyEmptyRoom_afterCeiling() = vmTest { scope ->
        val vm = makeVM(scope)
        vm.emptyPlaceholderGraceMs = 20
        vm.resumeGraceMs = 60
        vm.handleForeground()
        vm.updateSettledEmpty(true)
        waitUntil { vm.settledEmpty.value }
        assertTrue(vm.settledEmpty.value)
    }

    @Test
    fun handleForeground_contentArrivalEndsResumeWindow() = vmTest { scope ->
        val vm = makeVM(scope)
        vm.emptyPlaceholderGraceMs = 20
        vm.resumeGraceMs = 5_000
        vm.handleForeground()
        vm.updateSettledEmpty(false)
        vm.updateSettledEmpty(true)
        waitUntil { vm.settledEmpty.value }
        assertTrue(vm.settledEmpty.value)
    }

    // MARK: - pendingAsk

    @Test
    fun pendingAsk_returnsMostRecentUnansweredPrompt() = vmTest { scope ->
        val vm = makeAskVM(scope, listOf(askItem("\$1"), askItem("\$2")), InMemoryKeyValueStore())
        assertEquals("\$2", vm.pendingAsk()?.id)
    }

    @Test
    fun pendingAsk_excludesAnsweredPrompts_evenAfterRedelivery() = vmTest { scope ->
        val store = InMemoryKeyValueStore()
        val vm = makeAskVM(scope, listOf(askItem("\$1")), store)
        assertNotNull(vm.pendingAsk())
        vm.markPromptAnswered("\$1")
        val vm2 = makeAskVM(scope, listOf(askItem("\$1")), store)
        assertNull(vm2.pendingAsk())
    }

    @Test
    fun pendingAsk_surfacesOlderPrompt_onceNewestIsAnswered() = vmTest { scope ->
        val vm = makeAskVM(scope, listOf(askItem("\$1"), askItem("\$2")), InMemoryKeyValueStore())
        assertEquals("\$2", vm.pendingAsk()?.id)
        vm.markPromptAnswered("\$2")
        assertEquals("\$1", vm.pendingAsk()?.id)
    }

    @Test
    fun pendingAsk_clearedBy_buttonResponseInTimeline() = vmTest { scope ->
        val vm = makeAskVM(scope, listOf(askItem("\$1"), answerItem("\$2", "\$1", isOwn = true)), InMemoryKeyValueStore())
        assertNull(vm.pendingAsk())
    }

    @Test
    fun pendingAsk_clearedBy_ownReplyInTimeline() = vmTest { scope ->
        val reply = textItem("\$2", "answer", isOwn = true, inReplyTo = "\$1")
        val vm = makeAskVM(scope, listOf(askItem("\$1"), reply), InMemoryKeyValueStore())
        assertNull(vm.pendingAsk())
    }

    @Test
    fun pendingAsk_persistsCrossDeviceAnswer_acrossSnapshots() = vmTest { scope ->
        val store = InMemoryKeyValueStore()
        val vm = makeAskVM(scope, listOf(askItem("\$1"), answerItem("\$2", "\$1", isOwn = true)), store)
        assertNull(vm.pendingAsk())
        val vm2 = makeAskVM(scope, listOf(askItem("\$1")), store)
        assertNull(vm2.pendingAsk())
    }

    @Test
    fun persistVisibleAnswers_keepsInlineCardResolved_acrossSnapshots() = vmTest { scope ->
        val store = InMemoryKeyValueStore()
        val vm = makeAskVM(scope, listOf(askItem("\$1"), answerItem("\$2", "\$1", isOwn = true)), store)
        vm.persistVisibleAnswers()
        assertTrue(vm.isPromptAnswered("\$1"))
        val vm2 = makeAskVM(scope, listOf(askItem("\$1")), store)
        assertTrue(vm2.isPromptAnswered("\$1"))
    }

    @Test
    fun pendingAsk_notClearedBy_othersReplies() = vmTest { scope ->
        val botReply = textItem("\$2", "any thoughts?", isOwn = false, inReplyTo = "\$1")
        val vm = makeAskVM(scope, listOf(askItem("\$1"), botReply), InMemoryKeyValueStore())
        assertEquals("\$1", vm.pendingAsk()?.id)
    }

    @Test
    fun pendingAsk_notClearedBy_othersButtonResponse() = vmTest { scope ->
        val vm = makeAskVM(scope, listOf(askItem("\$1"), answerItem("\$2", "\$1", isOwn = false)), InMemoryKeyValueStore())
        assertEquals("\$1", vm.pendingAsk()?.id)
    }

    @Test
    fun pendingAsk_skipsExpiredPrompts() = vmTest { scope ->
        val vm = makeAskVM(scope, listOf(askItem("\$1", expiresAt = Instant.now().minusSeconds(10))), InMemoryKeyValueStore())
        assertNull(vm.pendingAsk())
    }

    // MARK: - isPromptAnswered / persistence

    @Test
    fun isPromptAnswered_trueOnlyWhenAnsweredHereOrCrossDevice() = vmTest { scope ->
        val answer = answerItem("\$2", "\$x", isOwn = true)
        val othersAnswer = answerItem("\$3", "\$other", isOwn = false)
        val vm = makeAskVM(
            scope,
            listOf(askItem("\$1"), askItem("\$x"), askItem("\$other"), answer, othersAnswer),
            InMemoryKeyValueStore(),
        )
        assertFalse(vm.isPromptAnswered("\$1"))
        assertTrue(vm.isPromptAnswered("\$x"))
        assertFalse(vm.isPromptAnswered("\$other"))
        vm.markPromptAnswered("\$1")
        assertTrue(vm.isPromptAnswered("\$1"))
    }

    @Test
    fun isPromptAnswered_falseWhenItemsTransientlyEmpty() = vmTest { scope ->
        val vm = makeAskVM(scope, emptyList(), InMemoryKeyValueStore())
        assertFalse(vm.isPromptAnswered("\$1"))
    }

    @Test
    fun answeredPromptIDs_persistAcrossInstances() = vmTest { scope ->
        val store = InMemoryKeyValueStore()
        makeAskVM(scope, listOf(askItem("\$persist-1")), store).markPromptAnswered("\$persist-1")
        val vm2 = makeAskVM(scope, listOf(askItem("\$persist-1")), store)
        assertNull(vm2.pendingAsk())
    }

    // MARK: - inline ask-user cards

    @Test
    fun askViewModel_isStablePerPrompt() = vmTest { scope ->
        val prompt = TimelineItem(
            "p1", "@bot:s", Instant.now(),
            TimelineItem.Kind.AskUser(
                "p1",
                AskUserEvent(
                    "Q",
                    AskUserEvent.InputKind.Choice(listOf(AskUserEvent.Option("s", "Send", "send:0")), false),
                    null,
                    AskUserEvent.ReplyChannel.CHOICE_REPLY,
                ),
            ),
            isOwn = false,
        )
        val vm = makeAskVM(scope, listOf(prompt), InMemoryKeyValueStore())
        val first = vm.askViewModel("p1")
        val second = vm.askViewModel("p1")
        assertNotNull(first)
        assertSame(first, second)
        assertNull(vm.askViewModel("missing"))
    }

    @Test
    fun answerSummary_buttons_mapsValuesToLabels() = vmTest { scope ->
        val prompt = TimelineItem(
            "p1", "@bot:s", Instant.now(),
            TimelineItem.Kind.AskUser(
                "p1",
                AskUserEvent(
                    "Q",
                    AskUserEvent.InputKind.Choice(
                        listOf(AskUserEvent.Option("s", "Send", "send:0"), AskUserEvent.Option("c", "Cancel", "cancel:0")),
                        false,
                    ),
                    null,
                    AskUserEvent.ReplyChannel.CHOICE_REPLY,
                ),
            ),
            isOwn = false,
        )
        val answer = TimelineItem(
            "a1", "@me:s", Instant.now(),
            TimelineItem.Kind.AskUserAnswer("p1", listOf("send:0")), isOwn = true,
        )
        val vm = makeAskVM(scope, listOf(prompt, answer), InMemoryKeyValueStore())
        assertEquals("Send", vm.answerSummary("p1"))
        assertNull(vm.answerSummary("p1-unanswered"))
    }

    @Test
    fun answerSummary_textReply_returnsReplyBody() = vmTest { scope ->
        val prompt = TimelineItem(
            "p2", "@bot:s", Instant.now(),
            TimelineItem.Kind.AskUser("p2", AskUserEvent("Workdir?", AskUserEvent.InputKind.Text, null)),
            isOwn = false,
        )
        val reply = textItem("r1", "src/", isOwn = true, inReplyTo = "p2")
        val vm = makeAskVM(scope, listOf(prompt, reply), InMemoryKeyValueStore())
        assertEquals("src/", vm.answerSummary("p2"))
    }

    // MARK: - session status

    @Test
    fun sessionStatusSubscription_mergesPartialFrames() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        val vm = makeVM(scope, fake)
        vm.start().join()
        waitUntil { fake.sessionStatusFlow.subscriptionCount.value > 0 }

        fake.emitStatus(
            SessionStatusUpdate("!r:s", null, SessionStatus.Context(100_000, 1_000_000, 10), null, null, null, null, null),
        )
        waitUntil { vm.sessionStatus.value?.context != null }
        assertEquals(10, vm.sessionStatus.value?.context?.pct)

        fake.emitStatus(SessionStatusUpdate("!r:s", "claude-fable-5", null, null, null, null, null, null))
        waitUntil { vm.sessionStatus.value?.model != null }
        assertEquals("claude-fable-5", vm.sessionStatus.value?.model)
        assertEquals(10, vm.sessionStatus.value?.context?.pct)
    }

    @Test
    fun sessionStatusFlow_emitsSeparatelyForEachFrame() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        val vm = makeVM(scope, fake)
        vm.start().join()
        waitUntil { fake.sessionStatusFlow.subscriptionCount.value > 0 }

        // A real collector (Compose's collectAsState, notably) only sees a
        // status update when the StateFlow actually emits — reading .value
        // directly would hide an in-place mutation bug, since the mutated
        // object's fields would already reflect the new frame. Collecting is
        // the only way to observe that a *second* distinct emission happened.
        val emissions = mutableListOf<SessionStatus?>()
        val collector = scope.launch { vm.sessionStatus.collect { emissions.add(it) } }
        waitUntil { emissions.isNotEmpty() } // initial null

        fake.emitStatus(
            SessionStatusUpdate("!r:s", null, SessionStatus.Context(100_000, 1_000_000, 10), null, null, null, null, null),
        )
        waitUntil { emissions.size >= 2 }

        fake.emitStatus(SessionStatusUpdate("!r:s", "claude-fable-5", null, null, null, null, null, null))
        waitUntil { emissions.size >= 3 }

        assertEquals(3, emissions.size)
        assertEquals("claude-fable-5", emissions[2]?.model)
        assertEquals(10, emissions[2]?.context?.pct)

        collector.cancel()
    }

    @Test
    fun sessionStatus_clearedOnRestart() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(emptyList())
        val vm = makeVM(scope, fake)
        vm.start().join()
        waitUntil { fake.sessionStatusFlow.subscriptionCount.value > 0 }

        fake.emitStatus(
            SessionStatusUpdate("!r:s", null, SessionStatus.Context(100_000, 1_000_000, 10), null, null, null, null, null),
        )
        waitUntil { vm.sessionStatus.value?.context != null }
        assertEquals(10, vm.sessionStatus.value?.context?.pct)

        vm.stop()
        fake.snapshotsToEmit = listOf(emptyList())
        vm.start().join()
        assertNull(vm.sessionStatus.value)
    }

    // MARK: - agent-chat consent

    /// Suspends until [gate] opens, standing in for an answer still on the wire.
    private class GatedAnswerer(private val gate: CompletableDeferred<Unit>) : AgentChatAnswering {
        var calls = 0
            private set

        override suspend fun answerAgentChat(
            roomID: String,
            targetDeviceID: Long,
            decision: AgentChatDecision,
            alwaysAllow: Boolean,
        ): Boolean {
            calls += 1
            gate.await()
            return true
        }
    }

    /// The consent card is a row in a lazy list. Answering used to run on that
    /// row's coroutine scope, so scrolling the card off-screen — or leaving the
    /// chat — cancelled the request in flight and left the card holding the
    /// `Sending` marker that blocks retries: permanently unanswerable. The
    /// answer belongs to the view model, which outlives any one row.
    @Test
    fun answerAgentChat_outlivesTheRowThatTriggeredIt() = vmTest { scope ->
        val gate = CompletableDeferred<Unit>()
        val answerer = GatedAnswerer(gate)
        val vm = ChatViewModel(
            "!r:s", FakeTimelineService(), FakeMediaService(), scope,
            InMemoryKeyValueStore(), Haptics.None, answerer,
        )
        val request = AgentChatRequest(
            ask = AgentChatRequest.Ask.INVITE, roomID = "!room:s", fromDeviceID = 4,
            fromName = "dev-2", targetDeviceID = 7, topic = null, justification = null,
        )
        val rowScope = CoroutineScope(coroutineContext + Job())

        rowScope.launch {
            vm.answerAgentChat("42", request, AgentChatDecision.APPROVE, alwaysAllow = false)
        }.join()
        rowScope.cancel() // the card scrolls out of view, mid-request
        gate.complete(Unit)

        waitUntil { vm.agentChatState("42") is AgentChatCardState.Answered }
        assertEquals(1, answerer.calls)
        assertTrue((vm.agentChatState("42") as AgentChatCardState.Answered).approved)
    }
}
