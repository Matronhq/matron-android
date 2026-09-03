package chat.matron.android.viewmodels

import chat.matron.android.chat.ConversationSummaryEntry
import chat.matron.android.search.SearchHit
import chat.matron.android.chat.FakeMediaService
import chat.matron.android.chat.FakeTimelineService
import chat.matron.android.chat.JournalTimelineMapper
import chat.matron.android.chat.TimelineItem
import chat.matron.android.events.AgentChatCardState
import chat.matron.android.events.AgentChatRequest
import chat.matron.android.events.AgentSpawnCardState
import chat.matron.android.events.AgentSpawnRequest
import chat.matron.android.events.AskUserEvent
import chat.matron.android.events.SpawnOutcome
import chat.matron.android.journal.AgentChatDecision
import chat.matron.android.journal.AgentSpawnAnswering
import chat.matron.android.journal.AgentSpawnDecision
import chat.matron.android.journal.JournalApiError
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
import kotlinx.coroutines.yield
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

    /// A bridge queued_release card: choice buttons plus the bridge prompt id
    /// releases resolve against (apple #162).
    private fun queuedCardItem(id: String, promptID: String) = TimelineItem(
        id = id,
        sender = "@bot:s",
        timestamp = Instant.now(),
        kind = TimelineItem.Kind.AskUser(
            id,
            AskUserEvent(
                prompt = "Send all 2 queued messages now, or cancel this one?",
                kind = AskUserEvent.InputKind.Choice(
                    listOf(
                        AskUserEvent.Option("send", "⚡ Send all now", "send"),
                        AskUserEvent.Option("cancel", "✕ Cancel this", "cancel"),
                    ),
                    allowOther = false,
                ),
                expiresAt = null,
                replyChannel = AskUserEvent.ReplyChannel.CHOICE_REPLY,
                queuedReleasePromptID = promptID,
            ),
        ),
        isOwn = false,
    )

    /// The bridge's release row as the mapper hides it: a namespaced answer
    /// that is NOT ours (the bridge authored it).
    private fun releaseItem(id: String, promptID: String, action: String = "send") = TimelineItem(
        id = id,
        sender = "agent:bridge",
        timestamp = Instant.now(),
        kind = TimelineItem.Kind.AskUserAnswer("qr:$promptID", listOf(action)),
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
            // apple #138 namespaces the write by a digest of the attachment
            // URL so same-named attachments can't clobber each other.
            assertEquals(ChatViewModel.attachmentURLDigest(url), file.parentFile!!.name)
            assertEquals(File(root, "matron-attachments"), file.parentFile!!.parentFile)
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
            assertEquals(File(root, "matron-attachments"), file!!.parentFile!!.parentFile)
            assertEquals("escape.txt", file.name)
        } finally {
            root.deleteRecursively()
        }
    }

    // MARK: - attachmentURLDigest

    @Test
    fun attachmentURLDigest_pinsExactHex_andMasksHighBytes() {
        // Pinned: first 8 bytes of SHA-256("mxc://matron.chat/abc123"), hex.
        // The digest contains bytes >= 0x80 (0xe7, 0xa8), so this also pins
        // that formatting treats bytes as unsigned — a sign-extending
        // formatter would render "ffffffe7" and blow past 16 chars.
        assertEquals(
            "222ce7a7942892a8",
            ChatViewModel.attachmentURLDigest("mxc://matron.chat/abc123"),
        )
    }

    @Test
    fun attachmentURLDigest_isAlways16LowercaseHexChars() {
        for (url in listOf("mxc://a/b", "https://example.com/x?y=1", "", "é你好")) {
            val digest = ChatViewModel.attachmentURLDigest(url)
            assertTrue("'$url' digest '$digest'", digest.matches(Regex("[0-9a-f]{16}")))
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

    // MARK: - queued_release resolution (stale buttons after a flush, apple #162)

    /// A "Send all now" tap on ONE card flushes the whole queue; the bridge
    /// emits a release per flushed card. Those releases are bridge-authored
    /// (not isOwn) and must still retire the buttons — the queue action
    /// happened regardless of which device tapped.
    @Test
    fun isPromptAnswered_viaQueuedRelease_despiteNotOwn() = vmTest { scope ->
        val vm = makeAskVM(scope, listOf(queuedCardItem("\$1", "pr_a"), releaseItem("\$9", "pr_a")), InMemoryKeyValueStore())
        assertTrue(vm.isPromptAnswered("\$1"))
    }

    @Test
    fun queuedRelease_leavesSiblingCardsLive() = vmTest { scope ->
        val vm = makeAskVM(
            scope,
            listOf(queuedCardItem("\$1", "pr_a"), queuedCardItem("\$2", "pr_b"), releaseItem("\$9", "pr_a")),
            InMemoryKeyValueStore(),
        )
        assertTrue(vm.isPromptAnswered("\$1"))
        assertFalse("a release names one prompt; other queued cards stay actionable", vm.isPromptAnswered("\$2"))
    }

    @Test
    fun answerSummary_mapsReleaseActionThroughCardOptions() = vmTest { scope ->
        val vm = makeAskVM(scope, listOf(queuedCardItem("\$1", "pr_a"), releaseItem("\$9", "pr_a", "send")), InMemoryKeyValueStore())
        assertEquals("⚡ Send all now", vm.answerSummary("\$1"))
    }

    /// Boot reconcile emits terminal `expired` releases for orphaned cards. No
    /// option matches; the card shows its generic resolved state rather than
    /// "You chose: expired".
    @Test
    fun answerSummary_expiredRelease_isNull() = vmTest { scope ->
        val vm = makeAskVM(scope, listOf(queuedCardItem("\$1", "pr_a"), releaseItem("\$9", "pr_a", "expired")), InMemoryKeyValueStore())
        assertTrue(vm.isPromptAnswered("\$1"))
        assertNull(vm.answerSummary("\$1"))
    }

    @Test
    fun pendingAsk_skipsReleaseResolvedCard() = vmTest { scope ->
        val vm = makeAskVM(scope, listOf(queuedCardItem("\$1", "pr_a"), releaseItem("\$9", "pr_a")), InMemoryKeyValueStore())
        assertNull(vm.pendingAsk())
    }

    /// The realistic double-release: a committed `send` that was never acked,
    /// then boot reconcile's terminal `expired` for the same prompt_id. The
    /// earliest release wins — the queue really was flushed, so the card keeps
    /// reporting the send that happened rather than downgrading to the generic
    /// resolved state.
    @Test
    fun queuedRelease_earliestWins_sendThenExpired() = vmTest { scope ->
        val vm = makeAskVM(
            scope,
            listOf(queuedCardItem("\$1", "pr_a"), releaseItem("\$8", "pr_a", "send"), releaseItem("\$9", "pr_a", "expired")),
            InMemoryKeyValueStore(),
        )
        assertTrue(vm.isPromptAnswered("\$1"))
        assertEquals("⚡ Send all now", vm.answerSummary("\$1"))
    }

    /// Bugbot (#48): a release lands as a hidden row, so no visible row changes
    /// — the screen must observe the memo itself for the sibling cards' buttons
    /// to retire. The published flow flips when the release arrives on a later
    /// snapshot.
    @Test
    fun releaseResolvedAnswers_publishesWhenAReleaseLands() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(listOf(queuedCardItem("\$1", "pr_a")))
        val vm = ChatViewModel("!ask-room:s", fake, FakeMediaService(), scope, InMemoryKeyValueStore())
        vm.start().join()
        assertTrue(vm.releaseResolvedAnswers.value.isEmpty())
        assertFalse(vm.isPromptAnswered("\$1"))

        fake.snapshotsToEmit = listOf(listOf(queuedCardItem("\$1", "pr_a"), releaseItem("\$9", "pr_a")))
        vm.start().join()
        assertEquals(mapOf("\$1" to listOf("send")), vm.releaseResolvedAnswers.value)
        assertTrue(vm.isPromptAnswered("\$1"))
    }

    /// The whole design rests on release rows being invisible: they must not
    /// become rows, day separators, or scroll anchors.
    @Test
    fun releaseRow_neverEntersRows() = vmTest { scope ->
        val vm = makeAskVM(scope, listOf(queuedCardItem("\$1", "pr_a"), releaseItem("\$9", "pr_a")), InMemoryKeyValueStore())
        val messageIDs = vm.rows.value.mapNotNull { (it as? TimelineRow.Message)?.item?.id }
        assertEquals(listOf("\$1"), messageIDs)
        assertEquals(1, vm.rows.value.count { it is TimelineRow.Separator })
    }

    // MARK: - In-conversation search (apple #172)

    private fun searchHit(id: String, t: Long = 0) =
        SearchHit(id = id, roomID = "r1", sender = "@a:s", timestamp = Instant.ofEpochSecond(t), snippet = "<mark>match</mark> $id")

    private fun searchVM(scope: CoroutineScope, timeline: chat.matron.android.chat.TimelineService, search: FakeSearchService?) =
        ChatViewModel("r1", timeline, FakeMediaService(), scope, InMemoryKeyValueStore(), Haptics.None, null, null, search)

    /// begin runs the room-scoped query, focuses the NEWEST match, and the
    /// chevrons step through matches via focus(seq) with clamping at both ends.
    @Test
    fun inChatSearch_beginStepAndEnd() = vmTest { scope ->
        val items = (1..3).map { textItem(it.toString(), body = "match $it", timestamp = Instant.ofEpochSecond(it.toLong())) }
        val fake = PagingFakeTimelineService(loaded = items, olderPages = mutableListOf())
        val search = FakeSearchService(listOf(searchHit("3", 3), searchHit("1", 1)))
        val vm = searchVM(scope, fake, search)
        vm.start()
        waitUntil { vm.hasReceivedFirstSnapshot.value }

        vm.beginChatSearch("match")
        assertEquals(listOf(3L, 1L), vm.chatSearch.value?.matchSeqs)
        assertEquals(0, vm.chatSearch.value?.index)
        assertEquals("3", vm.pendingFocusID.value)
        vm.clearPendingFocus()

        vm.stepChatSearch(older = true)
        assertEquals(1, vm.chatSearch.value?.index)
        assertEquals("1", vm.pendingFocusID.value)
        vm.clearPendingFocus()

        vm.stepChatSearch(older = true)
        assertEquals("clamped at the oldest match", 1, vm.chatSearch.value?.index)
        assertNull("a clamped step must not re-focus", vm.pendingFocusID.value)

        vm.stepChatSearch(older = false)
        assertEquals(0, vm.chatSearch.value?.index)

        vm.stepChatSearch(older = true)
        vm.beginChatSearch("match")
        assertEquals("a re-query resets to the newest match", 0, vm.chatSearch.value?.index)

        vm.endChatSearch()
        assertNull(vm.chatSearch.value)
        vm.stop()
    }

    @Test
    fun inChatSearch_nonNumericHitIDsAreDropped() = vmTest { scope ->
        val fake = PagingFakeTimelineService(loaded = listOf(textItem("3", body = "match")), olderPages = mutableListOf())
        val search = FakeSearchService(listOf(searchHit("3"), searchHit("\$not-a-seq")))
        val vm = searchVM(scope, fake, search)
        vm.start()
        waitUntil { vm.hasReceivedFirstSnapshot.value }
        vm.beginChatSearch("match")
        assertEquals(listOf(3L), vm.chatSearch.value?.matchSeqs)
        vm.stop()
    }

    /// Arming a search on a COLD view model (the results tap runs before the
    /// destination's start()) must not run the focus loop against the
    /// unsubscribed stream — that would falsely latch reachedHistoryStart.
    /// The jump parks and fires once the first snapshot lands.
    @Test
    fun inChatSearch_beforeStartParksJumpAndNeverLatchesHistoryStart() = vmTest { scope ->
        val fake = PagingFakeTimelineService(loaded = listOf(textItem("3", body = "match")), olderPages = mutableListOf())
        val vm = searchVM(scope, fake, FakeSearchService(listOf(searchHit("3"))))
        vm.beginChatSearch("match")
        assertEquals(listOf(3L), vm.chatSearch.value?.matchSeqs)
        assertNull("parked, not focused", vm.pendingFocusID.value)
        assertFalse(vm.reachedHistoryStart)
        vm.start()
        waitUntil { vm.pendingFocusID.value == "3" }
        assertEquals("3", vm.pendingFocusID.value)
        vm.stop()
    }

    /// The warm twin: a cached VM whose previous view already ran stop() has
    /// hasReceivedFirstSnapshot == true but a dead stream — the jump must park
    /// and fire on the restarted stream's first delivery.
    @Test
    fun inChatSearch_afterStopParksJumpUntilRestart() = vmTest { scope ->
        val fake = PagingFakeTimelineService(loaded = listOf(textItem("3", body = "match")), olderPages = mutableListOf())
        val vm = searchVM(scope, fake, FakeSearchService(listOf(searchHit("3"))))
        vm.start()
        waitUntil { vm.hasReceivedFirstSnapshot.value }
        vm.stop()
        vm.beginChatSearch("match")
        assertNull("parked on a stopped VM", vm.pendingFocusID.value)
        vm.start()
        waitUntil { vm.pendingFocusID.value == "3" }
        assertEquals("3", vm.pendingFocusID.value)
        vm.stop()
    }

    /// A re-query must disarm the previous query's parked jump: begin on a
    /// stopped VM (parks), re-query with no hits, then restart — the stale
    /// park must NOT fire a jump to a match the bar no longer shows.
    @Test
    fun inChatSearch_noHitRequeryDisarmsParkedJump() = vmTest { scope ->
        val fake = PagingFakeTimelineService(loaded = listOf(textItem("3", body = "match")), olderPages = mutableListOf())
        val search = FakeSearchService(listOf(searchHit("3")))
        val vm = searchVM(scope, fake, search)
        vm.beginChatSearch("match")
        search.hits = emptyList()
        vm.beginChatSearch("nothing")
        assertEquals(emptyList<Long>(), vm.chatSearch.value?.matchSeqs)
        vm.start()
        waitUntil { vm.hasReceivedFirstSnapshot.value }
        delay(50)
        assertNull("the stale park must not fire", vm.pendingFocusID.value)
        vm.stop()
    }

    /// Bugbot (#56): closing the bar while the query is still in flight must
    /// not let the late result resurrect it.
    @Test
    fun inChatSearch_endMidQueryDoesNotResurrectTheBar() = vmTest { scope ->
        val fake = PagingFakeTimelineService(loaded = listOf(textItem("3", body = "match")), olderPages = mutableListOf())
        val search = FakeSearchService(listOf(searchHit("3")))
        search.queryGate = CompletableDeferred()
        val vm = searchVM(scope, fake, search)
        vm.start()
        waitUntil { vm.hasReceivedFirstSnapshot.value }
        val begin = launch { vm.beginChatSearch("match") }
        waitUntil { search.queryCalls == 1 }
        vm.endChatSearch()
        search.queryGate!!.complete(Unit)
        begin.join()
        assertNull("a superseded query must not publish", vm.chatSearch.value)
        assertNull(vm.pendingFocusID.value)
        vm.stop()
    }

    /// Bugbot (#56): a slower earlier query must not overwrite a newer one.
    @Test
    fun inChatSearch_newerQuerySupersedesASlowerEarlierOne() = vmTest { scope ->
        val fake = PagingFakeTimelineService(loaded = listOf(textItem("3", body = "match")), olderPages = mutableListOf())
        val search = FakeSearchService(listOf(searchHit("3")))
        search.queryGate = CompletableDeferred()
        val vm = searchVM(scope, fake, search)
        vm.start()
        waitUntil { vm.hasReceivedFirstSnapshot.value }
        val slow = launch { vm.beginChatSearch("slow") }
        waitUntil { search.queryCalls == 1 }
        val slowGate = search.queryGate!!
        search.queryGate = null
        search.hits = emptyList()
        vm.beginChatSearch("newer")
        assertEquals("newer", vm.chatSearch.value?.query)
        slowGate.complete(Unit)
        slow.join()
        assertEquals("the earlier query's late result is discarded", "newer", vm.chatSearch.value?.query)
        vm.stop()
    }

    /// Without a search service the entry point is a no-op — the bar must not
    /// come up reporting bogus emptiness.
    @Test
    fun inChatSearch_noServiceIsANoOp() = vmTest { scope ->
        val vm = searchVM(scope, FakeTimelineService(), null)
        vm.beginChatSearch("match")
        assertNull(vm.chatSearch.value)
    }

    /// Dismissing the bar mid-jump must abandon the jump: a deep hit's
    /// pagination otherwise keeps running and lands pendingFocusID after the
    /// user closed search. The gated fake parks the paginate so the dismissal
    /// deterministically lands mid-flight.
    @Test
    fun inChatSearch_endMidJumpCancelsTheJump() = vmTest { scope ->
        val fake = BlockingPagingFakeTimelineService(
            loaded = listOf(textItem("300", body = "late")),
            olderPages = mutableListOf(listOf(textItem("3", body = "match"))),
        )
        val vm = searchVM(scope, fake, FakeSearchService(listOf(searchHit("3"))))
        vm.start()
        waitUntil { vm.hasReceivedFirstSnapshot.value }
        val jump = launch { vm.beginChatSearch("match") }
        waitUntil { fake.paginateStarted }
        vm.endChatSearch()
        fake.release()
        jump.join()
        delay(50)
        assertNull("an abandoned jump must not land", vm.pendingFocusID.value)
        vm.stop()
    }

    // MARK: - render window cap + slide (apple #166)

    private fun messageIDs(vm: ChatViewModel) = vm.windowedRows.value.mapNotNull { (it as? TimelineRow.Message)?.item?.id }

    /// 600 same-day messages — enough to exercise the cap (360) and the slide
    /// beyond it.
    private suspend fun bigVM(scope: CoroutineScope, fake: AppendableFakeTimelineService = AppendableFakeTimelineService()): ChatViewModel {
        val base = Instant.parse("2026-08-20T10:00:00Z")
        fake.emit((0 until 600).map { textItem("m$it", "msg $it", timestamp = base.plusSeconds(it.toLong())) })
        val vm = ChatViewModel("!r:s", fake, FakeMediaService(), scope, InMemoryKeyValueStore())
        // start() returns the live collection job; this fake's flow never
        // completes, so joining it would hang. start() itself already awaits
        // the first snapshot.
        vm.start()
        waitUntil { vm.rows.value.size == 600 }
        return vm
    }

    @Test
    fun extendHistoryWindow_capsAtMax_thenSlidesUpThroughHistory() = vmTest { scope ->
        val vm = bigVM(scope)
        assertEquals(120, messageIDs(vm).size)
        assertTrue(vm.windowContainsTail)
        vm.extendHistoryWindow(); vm.extendHistoryWindow()
        assertEquals(360, messageIDs(vm).size)
        assertEquals("m599", messageIDs(vm).last())
        // At the cap the window slides instead of growing: the newest edge
        // moves up by a step and pins to a row identity (row ids carry the
        // "msg:" prefix; the rendered ids below are bare item ids).
        vm.extendHistoryWindow()
        assertEquals(360, messageIDs(vm).size)
        assertFalse(vm.windowContainsTail)
        assertEquals("msg:m479", vm.windowTailAnchorID.value)
        assertEquals("m479", messageIDs(vm).last())
        assertEquals("m120", messageIDs(vm).first())
        vm.extendHistoryWindow()
        assertEquals("msg:m359", vm.windowTailAnchorID.value)
        assertEquals("m0", messageIDs(vm).first())
        // Nothing older is loaded now: the next extend paginates (the fake
        // has nothing) and the window holds.
        vm.extendHistoryWindow()
        assertEquals("m0", messageIDs(vm).first())
        vm.stop()
    }

    @Test
    fun revealNewerHistory_slidesDown_andReattachesAtTail() = vmTest { scope ->
        val vm = bigVM(scope)
        repeat(4) { vm.extendHistoryWindow() }
        assertEquals("msg:m359", vm.windowTailAnchorID.value)
        vm.revealNewerHistory()
        waitUntil { !vm.isExtendingWindow.value }
        assertEquals("msg:m479", vm.windowTailAnchorID.value)
        assertEquals("m479", messageIDs(vm).last())
        vm.revealNewerHistory()
        waitUntil { !vm.isExtendingWindow.value }
        assertTrue("reattached at the tail", vm.windowContainsTail)
        assertEquals("m599", messageIDs(vm).last())
        assertEquals(360, messageIDs(vm).size)
        vm.stop()
    }

    /// Stream appends below a slid window must not move it: the window is
    /// pinned to a row identity, not a count from the tail.
    @Test
    fun streamAppends_doNotMoveAnAnchoredWindow() = vmTest { scope ->
        val fake = AppendableFakeTimelineService()
        val vm = bigVM(scope, fake)
        repeat(3) { vm.extendHistoryWindow() }
        assertEquals("msg:m479", vm.windowTailAnchorID.value)
        val before = messageIDs(vm)
        fake.emit(vm.items.value + textItem("m600", "new", timestamp = Instant.parse("2026-08-20T11:00:00Z")))
        waitUntil { vm.rows.value.size == 601 }
        assertEquals(before, messageIDs(vm))
        assertEquals("msg:m479", vm.windowTailAnchorID.value)
        vm.stop()
    }

    /// The anchored row vanishing (a wipe of the region) rescues the window
    /// to the nearest surviving row it was showing rather than snapping to
    /// the tail.
    @Test
    fun vanishedAnchor_rescuesToNearestSurvivingRow() = vmTest { scope ->
        val fake = AppendableFakeTimelineService()
        val vm = bigVM(scope, fake)
        repeat(3) { vm.extendHistoryWindow() }
        assertEquals("msg:m479", vm.windowTailAnchorID.value)
        fake.emit(vm.items.value.filterNot { it.id == "m479" })
        waitUntil { vm.rows.value.size == 599 }
        assertEquals("msg:m478", vm.windowTailAnchorID.value)
        assertEquals("m478", messageIDs(vm).last())
        vm.stop()
    }

    /// A deep restore/jump target mounts a capped window around it instead
    /// of every row between it and the tail.
    @Test
    fun ensureWindowContains_deepTarget_capsTheWindowAroundIt() = vmTest { scope ->
        val vm = bigVM(scope)
        vm.ensureWindowContains("m10")
        val ids = messageIDs(vm)
        assertTrue(ids.size <= ChatViewModel.MAX_WINDOW_SIZE)
        assertTrue("the target is in the window", "m10" in ids)
        assertFalse(vm.windowContainsTail)
        vm.resetHistoryWindow()
        assertTrue(vm.windowContainsTail)
        assertEquals(120, messageIDs(vm).size)
        vm.stop()
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
            SessionStatusUpdate("!r:s", null, SessionStatus.Context(100_000, 1_000_000, 10), null, null, null, null, null, modelOptions = null, effortLevels = null, effort = null),
        )
        waitUntil { vm.sessionStatus.value?.context != null }
        assertEquals(10, vm.sessionStatus.value?.context?.pct)

        fake.emitStatus(SessionStatusUpdate("!r:s", "claude-fable-5", null, null, null, null, null, null, modelOptions = null, effortLevels = null, effort = null))
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
            SessionStatusUpdate("!r:s", null, SessionStatus.Context(100_000, 1_000_000, 10), null, null, null, null, null, modelOptions = null, effortLevels = null, effort = null),
        )
        waitUntil { emissions.size >= 2 }

        fake.emitStatus(SessionStatusUpdate("!r:s", "claude-fable-5", null, null, null, null, null, null, modelOptions = null, effortLevels = null, effort = null))
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
            SessionStatusUpdate("!r:s", null, SessionStatus.Context(100_000, 1_000_000, 10), null, null, null, null, null, modelOptions = null, effortLevels = null, effort = null),
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
            vm.answerAgentChat("42", request, AgentChatDecision.APPROVE)
        }.join()
        rowScope.cancel() // the card scrolls out of view, mid-request
        gate.complete(Unit)

        waitUntil { vm.agentChatState("42") is AgentChatCardState.Answered }
        assertEquals(1, answerer.calls)
        assertTrue((vm.agentChatState("42") as AgentChatCardState.Answered).approved)
    }

    // MARK: - Summary TOC entries (matron-apple #124 port)

    /// Port of matron-apple `ChatViewModelTests
    /// .testSummaryEntriesFlowFromServiceToViewModel`: `summaryEntriesStream()`
    /// frames flow through to published state unchanged (order, newest-first,
    /// preserved from the service).
    @Test
    fun summaryEntriesFlowFromServiceToViewModel() = vmTest { scope ->
        val fake = FakeTimelineService()
        fake.summaryEntriesToEmit = listOf(
            listOf(
                ConversationSummaryEntry(seq = 40, toc = "Newer", detail = "d2", date = Instant.ofEpochSecond(2)),
                ConversationSummaryEntry(seq = 10, toc = "Older", detail = "d1", date = Instant.ofEpochSecond(1)),
            ),
        )
        val vm = makeVM(scope, fake)
        vm.start()
        waitUntil { vm.summaryEntries.value.size == 2 }
        assertEquals(listOf(40L, 10L), vm.summaryEntries.value.map { it.seq })
        vm.stop()
    }

    // MARK: - focus(seq) jump-to-message (matron-apple #124 port)

    /// Seeds a VM with one fully-loaded snapshot of text messages whose ids
    /// are `seq.toString()` for each seq (ascending, matching
    /// `JournalTimelineMapper`'s convention) — the plain case, no pagination.
    private suspend fun makeVMWithMessages(scope: CoroutineScope, seqs: List<Int>): ChatViewModel {
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(seqs.map { textItem(it.toString()) })
        val vm = makeVM(scope, fake)
        vm.start()
        return vm
    }

    /// Seeds a VM on [PagingFakeTimelineService]: [loaded] is the initially-
    /// available window, [olderPages] the queue of pages a `paginateBackward()`
    /// call reveals one at a time. Every seq across every range becomes a text
    /// message row with id `seq.toString()`.
    private suspend fun makeVMWithPagedHistory(
        scope: CoroutineScope,
        loaded: List<IntRange>,
        olderPages: List<List<IntRange>>,
    ): ChatViewModel {
        fun items(ranges: List<IntRange>) = ranges.flatMap { range -> range.map { textItem(it.toString()) } }
        val fake = PagingFakeTimelineService(
            loaded = items(loaded),
            olderPages = olderPages.map { items(it) }.toMutableList(),
        )
        val vm = makeVM(scope, timeline = fake)
        vm.snapshotWaitMs = 200
        vm.start()
        return vm
    }

    private fun makeVM(scope: CoroutineScope, timeline: PagingFakeTimelineService) =
        ChatViewModel("!r:s", timeline, FakeMediaService(), scope, InMemoryKeyValueStore(), Haptics.None)

    /// Port of matron-apple `ChatViewModelTests.testFocusPicksNearestRowAtOrBeforeSeq`.
    @Test
    fun focusPicksNearestRowAtOrBeforeSeq() = vmTest { scope ->
        val vm = makeVMWithMessages(scope, listOf(10, 20, 30, 40))
        vm.focus(35)
        assertEquals("30", vm.pendingFocusID.value)   // nearest message with seq <= 35
        vm.clearPendingFocus()
        assertNull(vm.pendingFocusID.value)
        vm.stop()
    }

    /// Port of matron-apple `ChatViewModelTests.testFocusPaginatesBackwardUntilTargetLoaded`:
    /// seq 150 only appears after two paginateBackward calls (page 200..299,
    /// then page 100..199).
    @Test
    fun focusPaginatesBackwardUntilTargetLoaded() = vmTest { scope ->
        val vm = makeVMWithPagedHistory(
            scope, loaded = listOf(300..340), olderPages = listOf(listOf(200..299), listOf(100..199)),
        )
        vm.focus(150)
        assertEquals("150", vm.pendingFocusID.value)
        vm.stop()
    }

    /// Port of matron-apple `ChatViewModelTests.testFocusLandsOnOldestWhenRegionUnavailable`:
    /// no older pages queued, so every paginateBackward() call is a genuine
    /// (uncontended) no-growth no-op. focus()'s loop bails to the oldest-row
    /// fallback on the first such call rather than waiting out
    /// `reachedHistoryStart`'s full 2-consecutive-call latch.
    @Test
    fun focusLandsOnOldestWhenRegionUnavailable() = vmTest { scope ->
        val vm = makeVMWithPagedHistory(scope, loaded = listOf(300..340), olderPages = emptyList())
        vm.focus(5)
        assertEquals("300", vm.pendingFocusID.value)   // oldest available row
        vm.stop()
    }

    /// Port of matron-apple `ChatViewModelTests.testFocusSingleFlight_secondCallSupersedesFirst`:
    /// a second focus() call must supersede an in-flight first call rather
    /// than race it — seq 150 needs two paginateBackward() calls to load
    /// (exercising the loop this test wants cancelled mid-flight); seq 320 is
    /// already in the initial window, so its call resolves immediately.
    /// Whichever was requested LAST must win, not whichever finished last.
    @Test
    fun focusSingleFlight_secondCallSupersedesFirst() = vmTest { scope ->
        val vm = makeVMWithPagedHistory(
            scope, loaded = listOf(300..340), olderPages = listOf(listOf(200..299), listOf(100..199)),
        )
        val first = launch { vm.focus(150) }
        val second = launch { vm.focus(320) }
        first.join()
        second.join()
        assertEquals("320", vm.pendingFocusID.value)
        vm.stop()
    }

    /// Port of matron-apple `ChatViewModelTests
    /// .testFocusSingleFlight_cancelledBreakPath_doesNotLandFallback`: the
    /// paginate loop can also exit via the uncontended `break` (no growth,
    /// nothing else in flight) — that path must re-check cancellation before
    /// the `pendingFocusID` write, or a superseded call's fallback target
    /// lands over the newer call's. Seq 150 sits outside the loaded window
    /// with NO older pages queued, so the first call's paginateBackward()
    /// makes no progress and its loop exits via `break`. The yield() gives it
    /// a chance to actually begin before the second call cancels it.
    @Test
    fun focusSingleFlight_cancelledBreakPathDoesNotLandFallback() = vmTest { scope ->
        val vm = makeVMWithPagedHistory(scope, loaded = listOf(300..340), olderPages = emptyList())
        val first = launch { vm.focus(150) }
        yield()
        val second = launch { vm.focus(320) }
        first.join()
        second.join()
        assertEquals("320", vm.pendingFocusID.value)
        vm.stop()
    }

    /// Bugbot "Stale focus survives chat exit": VM instances are cached across
    /// visits (ChatVMCache) and `pendingFocusID` is a StateFlow, replayed to
    /// every new collector — so a target still set when the view exits
    /// (between the focus landing and the consumer's clearPendingFocus) must
    /// not replay the jump on re-entry. stop() drops it.
    @Test
    fun stopClearsUnconsumedPendingFocus() = vmTest { scope ->
        val vm = makeVMWithMessages(scope, listOf(10, 20, 30, 40))
        vm.focus(35)
        assertEquals("30", vm.pendingFocusID.value)   // landed, not yet consumed
        vm.stop()
        assertNull(vm.pendingFocusID.value)
        vm.start()
        assertNull(vm.pendingFocusID.value)           // re-entry sees no stale jump
        vm.stop()
    }

    // MARK: - agent-spawn consent

    private fun spawnRequest(requestId: String, fromConvoTitle: String? = null) = AgentSpawnRequest(
        requestId = requestId, fromDeviceId = 4, fromName = "dev-2", fromConvoId = null,
        fromConvoTitle = fromConvoTitle, targetDeviceId = 7, targetName = "dev-9",
        workdir = "/w", task = "do it", topic = null,
    )

    private fun spawnCardItem(id: String, request: AgentSpawnRequest) = TimelineItem(
        id = id, sender = "@bot:s", timestamp = Instant.now(),
        kind = TimelineItem.Kind.AgentSpawnRequestCard(id, request), isOwn = false,
    )

    private fun spawnOutcomeItem(id: String, outcome: SpawnOutcome) = TimelineItem(
        id = id, sender = "@bot:s", timestamp = Instant.now(),
        kind = TimelineItem.Kind.SpawnOutcomeRow(id, outcome), isOwn = false,
    )

    /// Records every call; optionally suspends on [gate] (standing in for an
    /// answer still on the wire) and/or throws [error] once released.
    private class RecordingSpawnAnswerer(
        private val error: Throwable? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : AgentSpawnAnswering {
        val calls = mutableListOf<Pair<String, AgentSpawnDecision>>()

        override suspend fun answerAgentSpawn(requestId: String, decision: AgentSpawnDecision) {
            calls.add(requestId to decision)
            gate?.await()
            error?.let { throw it }
        }
    }

    /// Throws once, then succeeds — pins the "a failure leaves the card
    /// answerable again" contract.
    private class FlakyOnceSpawnAnswerer : AgentSpawnAnswering {
        var callCount = 0
            private set

        override suspend fun answerAgentSpawn(requestId: String, decision: AgentSpawnDecision) {
            callCount += 1
            if (callCount == 1) throw JournalApiError.Transport("boom")
        }
    }

    @Test
    fun agentSpawnState_idleWhenAnswererWiredAndNothingResolvedOrInFlight() = vmTest { scope ->
        val vm = ChatViewModel(
            "!r:s", FakeTimelineService(), FakeMediaService(), scope,
            InMemoryKeyValueStore(), Haptics.None, null, RecordingSpawnAnswerer(),
        )
        assertEquals(AgentSpawnCardState.Idle, vm.agentSpawnState("10", spawnRequest("spawn-1")))
    }

    /// No answerer wired (previews, tests, or a screen that never wires one):
    /// the card renders read-only rather than offering buttons that would do
    /// nothing — same convention as `agentChatState`'s nil-resolver case.
    /// `Unavailable`, not a synthetic `Resolved("expired")`: no durable
    /// journal event backs this, so it must not show the journal's own
    /// "expired" copy (Global Constraint: "request no longer waiting").
    @Test
    fun agentSpawnState_readOnlyResolvedDefaultWhenNoAnswererWired() = vmTest { scope ->
        val vm = ChatViewModel("!r:s", FakeTimelineService(), FakeMediaService(), scope, InMemoryKeyValueStore())
        assertEquals(AgentSpawnCardState.Unavailable, vm.agentSpawnState("10", spawnRequest("spawn-1")))
    }

    /// The durable `spawn_outcome` event wins over everything, including a
    /// still in-flight `Sending` transient for the same card — once the
    /// journal record lands there is nothing left to answer.
    @Test
    fun agentSpawnState_derivedOutcomeWinsOverAnInFlightTransient() = vmTest { scope ->
        val request = spawnRequest("spawn-1")
        val gate = CompletableDeferred<Unit>()
        val answerer = RecordingSpawnAnswerer(gate = gate)
        val fake = FakeTimelineService().apply {
            snapshotsToEmit = listOf(listOf(spawnCardItem("10", request)))
        }
        val vm = ChatViewModel("!r:s", fake, FakeMediaService(), scope, InMemoryKeyValueStore(), Haptics.None, null, answerer)
        vm.start().join()

        vm.answerAgentSpawn("10", request, AgentSpawnDecision.APPROVE)
        assertTrue(vm.agentSpawnState("10", request) is AgentSpawnCardState.Sending)

        // The outcome event lands on a later snapshot while the answer is
        // still in flight.
        fake.snapshotsToEmit = listOf(
            listOf(spawnCardItem("10", request)),
            listOf(spawnCardItem("10", request), spawnOutcomeItem("11", SpawnOutcome(requestId = "spawn-1", outcome = "started", roomId = "room-9"))),
        )
        vm.start().join()

        val state = vm.agentSpawnState("10", request)
        assertTrue(state is AgentSpawnCardState.Resolved)
        assertEquals("started", (state as AgentSpawnCardState.Resolved).outcome.outcome)
        assertEquals("room-9", state.outcome.roomId)
        gate.complete(Unit) // release the still-pending answerer coroutine
    }

    /// 409 -> `Unavailable`, not a synthetic `Resolved("expired")` — the
    /// Global Constraint's copy for this transient is "request no longer
    /// waiting", not the journal's own "expired" outcome copy, which stays
    /// reserved for a real `spawn_outcome` event (M1 in the task-2 review).
    @Test
    fun answerAgentSpawn_conflictBecomesUnavailableAndBlocksReanswer() = vmTest { scope ->
        val request = spawnRequest("spawn-1")
        val answerer = RecordingSpawnAnswerer(error = JournalApiError.Conflict)
        val vm = ChatViewModel("!r:s", FakeTimelineService(), FakeMediaService(), scope, InMemoryKeyValueStore(), Haptics.None, null, answerer)

        vm.answerAgentSpawn("10", request, AgentSpawnDecision.APPROVE)
        waitUntil { vm.agentSpawnState("10", request) == AgentSpawnCardState.Unavailable }
        assertEquals(AgentSpawnCardState.Unavailable, vm.agentSpawnState("10", request))
        assertEquals(1, answerer.calls.size)
        assertEquals("spawn-1" to AgentSpawnDecision.APPROVE, answerer.calls.single())

        // Already settled (even without a durable event) — a second tap is a no-op.
        vm.answerAgentSpawn("10", request, AgentSpawnDecision.DENY)
        assertEquals(1, answerer.calls.size)
    }

    @Test
    fun answerAgentSpawn_notFoundBecomesFailedWithServerGoneCopy() = vmTest { scope ->
        val request = spawnRequest("spawn-1")
        val answerer = RecordingSpawnAnswerer(error = JournalApiError.NotFound)
        val vm = ChatViewModel("!r:s", FakeTimelineService(), FakeMediaService(), scope, InMemoryKeyValueStore(), Haptics.None, null, answerer)

        vm.answerAgentSpawn("10", request, AgentSpawnDecision.APPROVE)
        waitUntil { vm.agentSpawnState("10", request) is AgentSpawnCardState.Failed }
        assertEquals(
            "That request is no longer on the server.",
            (vm.agentSpawnState("10", request) as AgentSpawnCardState.Failed).message,
        )
    }

    /// A transport failure leaves the card `Failed`, not stuck — retrying
    /// answers it again, and the retry can succeed.
    @Test
    fun answerAgentSpawn_transportFailureIsAnswerableAgainOnRetry() = vmTest { scope ->
        val request = spawnRequest("spawn-1")
        val answerer = FlakyOnceSpawnAnswerer()
        val vm = ChatViewModel("!r:s", FakeTimelineService(), FakeMediaService(), scope, InMemoryKeyValueStore(), Haptics.None, null, answerer)

        vm.answerAgentSpawn("10", request, AgentSpawnDecision.APPROVE)
        waitUntil { vm.agentSpawnState("10", request) is AgentSpawnCardState.Failed }
        assertEquals(
            "Couldn't reach the server — check your connection and try again.",
            (vm.agentSpawnState("10", request) as AgentSpawnCardState.Failed).message,
        )

        vm.answerAgentSpawn("10", request, AgentSpawnDecision.APPROVE)
        waitUntil { answerer.callCount == 2 }
        // `waitUntil` returns silently on timeout — without this, a retry that
        // never dispatches would still read `Sending` from the synchronous
        // marker and pass for the wrong reason.
        assertEquals(2, answerer.callCount)
        assertTrue(vm.agentSpawnState("10", request) is AgentSpawnCardState.Sending)
    }

    /// The `Sending` marker set synchronously before the call is dispatched
    /// (not inside the launched coroutine) is what makes a second tap while
    /// the first is still on the wire a guaranteed no-op, not a race.
    @Test
    fun answerAgentSpawn_doubleSendGuarded() = vmTest { scope ->
        val gate = CompletableDeferred<Unit>()
        val answerer = RecordingSpawnAnswerer(gate = gate)
        val vm = ChatViewModel("!r:s", FakeTimelineService(), FakeMediaService(), scope, InMemoryKeyValueStore(), Haptics.None, null, answerer)
        val request = spawnRequest("spawn-1")

        vm.answerAgentSpawn("10", request, AgentSpawnDecision.APPROVE)
        vm.answerAgentSpawn("10", request, AgentSpawnDecision.APPROVE) // tapped again mid-send
        gate.complete(Unit)

        waitUntil { answerer.calls.isNotEmpty() }
        assertEquals(1, answerer.calls.size)
    }

    /// Pins the VM -> API argument mapping directly: `request.requestId`
    /// (not `eventID`) and the exact tapped decision must reach the
    /// answerer. The tap -> decision half of this path
    /// (`TimelineItemView`'s `onApprove`/`onDeny` closures) is untested by
    /// the Compose-is-logic-free rule, so this is the only place either
    /// direction can be caught (task-2 review finding I2).
    @Test
    fun answerAgentSpawn_sendsTheTappedDecisionAndTheRequestIdNotTheEventId() = vmTest { scope ->
        val request = spawnRequest("spawn-1")

        val approveAnswerer = RecordingSpawnAnswerer()
        val approveVM = ChatViewModel(
            "!r:s", FakeTimelineService(), FakeMediaService(), scope,
            InMemoryKeyValueStore(), Haptics.None, null, approveAnswerer,
        )
        approveVM.answerAgentSpawn("event-id-not-request-id", request, AgentSpawnDecision.APPROVE)
        waitUntil { approveAnswerer.calls.isNotEmpty() }
        assertEquals("spawn-1" to AgentSpawnDecision.APPROVE, approveAnswerer.calls.single())

        val denyAnswerer = RecordingSpawnAnswerer()
        val denyVM = ChatViewModel(
            "!r:s", FakeTimelineService(), FakeMediaService(), scope,
            InMemoryKeyValueStore(), Haptics.None, null, denyAnswerer,
        )
        denyVM.answerAgentSpawn("event-id-not-request-id", request, AgentSpawnDecision.DENY)
        waitUntil { denyAnswerer.calls.isNotEmpty() }
        assertEquals("spawn-1" to AgentSpawnDecision.DENY, denyAnswerer.calls.single())
    }

    /// A card already resolved by a real journal event must never be
    /// answered — the row rendering it is about to disappear as an
    /// AmbientNotice/resolved card, not offer buttons.
    @Test
    fun answerAgentSpawn_noOpWhenAlreadyResolvedByOutcomeEvent() = vmTest { scope ->
        val request = spawnRequest("spawn-1")
        val answerer = RecordingSpawnAnswerer()
        val fake = FakeTimelineService().apply {
            snapshotsToEmit = listOf(
                listOf(spawnCardItem("10", request), spawnOutcomeItem("11", SpawnOutcome(requestId = "spawn-1", outcome = "declined"))),
            )
        }
        val vm = ChatViewModel("!r:s", fake, FakeMediaService(), scope, InMemoryKeyValueStore(), Haptics.None, null, answerer)
        vm.start().join()

        vm.answerAgentSpawn("10", request, AgentSpawnDecision.APPROVE)
        assertTrue(answerer.calls.isEmpty())
        val state = vm.agentSpawnState("10", request) as AgentSpawnCardState.Resolved
        assertEquals("declined", state.outcome.outcome)
    }

    /// Same VM-scope rationale as `answerAgentChat_outlivesTheRowThatTriggeredIt`:
    /// the card is a row in a lazy list, so answering must not run on that
    /// row's own coroutine scope — scrolling it away or leaving the chat
    /// would cancel the request in flight and leave the card stuck on
    /// `Sending` forever, permanently unanswerable.
    @Test
    fun answerAgentSpawn_outlivesTheRowThatTriggeredIt() = vmTest { scope ->
        val gate = CompletableDeferred<Unit>()
        val answerer = RecordingSpawnAnswerer(gate = gate)
        val vm = ChatViewModel("!r:s", FakeTimelineService(), FakeMediaService(), scope, InMemoryKeyValueStore(), Haptics.None, null, answerer)
        val request = spawnRequest("spawn-1")
        val rowScope = CoroutineScope(coroutineContext + Job())

        rowScope.launch {
            vm.answerAgentSpawn("10", request, AgentSpawnDecision.APPROVE)
        }.join()
        rowScope.cancel() // the card scrolls out of view, mid-request
        gate.complete(Unit)

        waitUntil { answerer.calls.size == 1 }
        // `waitUntil` returns silently on timeout, and `Sending` alone is set
        // synchronously before the coroutine ever dispatches — so the call
        // count must be asserted explicitly, or a coroutine cancelled before
        // it started (the exact bug this test exists to catch) would still
        // read `Sending` and pass.
        assertEquals(1, answerer.calls.size)
        // The call ran to completion on the VM's own scope: still `Sending`
        // (no journal event has resolved it yet), not dropped.
        assertTrue(vm.agentSpawnState("10", request) is AgentSpawnCardState.Sending)
    }

    /// The whole chat going away (the VM's own scope is cancelled) mid-send
    /// drops the transient marker instead of leaving it stuck on `Sending` —
    /// contrast with the row-scope cancellation above, which must NOT affect
    /// the in-flight call at all.
    @Test
    fun answerAgentSpawn_cancellationOfTheVMsOwnScopeDropsTransientAndRethrows() = vmTest { scope ->
        val gate = CompletableDeferred<Unit>()
        val answerer = RecordingSpawnAnswerer(gate = gate)
        val vm = ChatViewModel("!r:s", FakeTimelineService(), FakeMediaService(), scope, InMemoryKeyValueStore(), Haptics.None, null, answerer)
        val request = spawnRequest("spawn-1")

        vm.answerAgentSpawn("10", request, AgentSpawnDecision.APPROVE)
        assertTrue(vm.agentSpawnState("10", request) is AgentSpawnCardState.Sending)
        // Let the launched coroutine actually start and reach `gate.await()`
        // before cancelling — cancelling a not-yet-dispatched coroutine skips
        // its body (and so its catch block) entirely, which would pass this
        // test for the wrong reason.
        waitUntil { answerer.calls.isNotEmpty() }

        scope.cancel() // the whole chat/session is going away — cancels the
        // launched answer coroutine directly, since it's a child of this
        // scope (that's the whole point of running on it).
        gate.complete(Unit)

        waitUntil { vm.agentSpawnState("10", request) !is AgentSpawnCardState.Sending }
        // Dropped transient falls back to Idle (an answerer is still wired),
        // not stuck forever on the marker that blocks retries.
        assertEquals(AgentSpawnCardState.Idle, vm.agentSpawnState("10", request))
    }

    /// THE key divergence from agent-chat: resolution is derived purely from
    /// timeline items, never persisted. A brand-new view model for the same
    /// room — same [InMemoryKeyValueStore], never touched by either
    /// instance — resolves the very same card from the replayed snapshot
    /// alone.
    @Test
    fun spawnCardResolution_isDerivedFromItemsNotPersisted_freshVMAlsoResolves() = vmTest { scope ->
        val store = InMemoryKeyValueStore()
        val request = spawnRequest("spawn-1")
        val cardItem = spawnCardItem("10", request)
        val outcomeItem = spawnOutcomeItem("11", SpawnOutcome(requestId = "spawn-1", outcome = "started", roomId = "room-9"))

        val fake1 = FakeTimelineService().apply { snapshotsToEmit = listOf(listOf(cardItem, outcomeItem)) }
        val vm1 = ChatViewModel("!r:s", fake1, FakeMediaService(), scope, store)
        vm1.start().join()
        val state1 = vm1.agentSpawnState("10", request) as AgentSpawnCardState.Resolved
        assertEquals("started", state1.outcome.outcome)

        // A brand-new instance, same room, same store.
        val fake2 = FakeTimelineService().apply { snapshotsToEmit = listOf(listOf(cardItem, outcomeItem)) }
        val vm2 = ChatViewModel("!r:s", fake2, FakeMediaService(), scope, store)
        vm2.start().join()
        val state2 = vm2.agentSpawnState("10", request) as AgentSpawnCardState.Resolved
        assertEquals("started", state2.outcome.outcome)

        assertTrue("spawn resolution must never write to KeyValueStore", store.allKeys.isEmpty())
    }

    // MARK: - hasMultipleSenders (ports the apple #141 additions)

    private fun senderItem(
        id: String,
        sender: String,
        isOwn: Boolean = false,
        kind: TimelineItem.Kind = TimelineItem.Kind.Text("hi", null),
    ) = TimelineItem(
        id = id,
        sender = sender,
        timestamp = Instant.now(),
        kind = kind,
        isOwn = isOwn,
    )

    private suspend fun makeSenderVM(scope: CoroutineScope, items: List<TimelineItem>): ChatViewModel {
        val fake = FakeTimelineService()
        fake.snapshotsToEmit = listOf(items)
        val vm = makeVM(scope, timeline = fake)
        vm.start().join()
        return vm
    }

    /// Ports `testHasMultipleSenders_singleNonOwnSender_isFalse`: a 1:1 chat
    /// — everything from a single bot sender plus the user's own messages —
    /// must NOT flip the flag. Own messages are excluded from the
    /// distinct-sender count even though there's only one of them here; the
    /// point is the single bot sender alone isn't enough.
    @Test
    fun hasMultipleSenders_singleNonOwnSender_isFalse() = vmTest { scope ->
        val vm = makeSenderVM(scope, listOf(
            senderItem("1", "matron"),
            senderItem("2", "matron"),
            senderItem("3", "me", isOwn = true),
        ))
        assertFalse(vm.hasMultipleSenders.value)
    }

    /// Ports `testHasMultipleSenders_noItems_isFalse`: the degenerate
    /// 0-sender case must not crash or false-positive.
    @Test
    fun hasMultipleSenders_noItems_isFalse() = vmTest { scope ->
        val vm = makeSenderVM(scope, emptyList())
        assertFalse(vm.hasMultipleSenders.value)
    }

    /// Ports `testHasMultipleSenders_twoDistinctNonOwnSenders_isTrue`: the
    /// agent-chat case this whole feature exists for — two distinct non-own
    /// senders (e.g. "dev-2" and "dan-mac" both replying) must flip the flag.
    @Test
    fun hasMultipleSenders_twoDistinctNonOwnSenders_isTrue() = vmTest { scope ->
        val vm = makeSenderVM(scope, listOf(
            senderItem("1", "dev-2"),
            senderItem("2", "dan-mac"),
        ))
        assertTrue(vm.hasMultipleSenders.value)
    }

    /// Ports `testHasMultipleSenders_ownMessagesExcludedFromCount`: own
    /// messages must not count toward the distinct-sender total even when
    /// their `sender` string differs from the single bot's — the flag is
    /// specifically about attributing NON-own bubbles.
    @Test
    fun hasMultipleSenders_ownMessagesExcludedFromCount() = vmTest { scope ->
        val vm = makeSenderVM(scope, listOf(
            senderItem("1", "matron"),
            senderItem("2", "dan", isOwn = true),
        ))
        assertFalse(
            "the own sender must not count toward the distinct non-own total",
            vm.hasMultipleSenders.value,
        )
    }

    /// Ports `testHasMultipleSenders_ephemeralActivityAndToolStreamRows_areExcluded`:
    /// regression for the mid-turn false positive — an ordinary 1:1 chat
    /// (single real bot sender "matron") plus the ephemeral ActivityIndicator
    /// and ToolStreamLive overlay rows the mapper synthesises during a turn —
    /// both hardcode `sender = "agent"` — must NOT flip the flag. Only
    /// Text / Image / File durable kinds count.
    @Test
    fun hasMultipleSenders_ephemeralActivityAndToolStreamRows_areExcluded() = vmTest { scope ->
        val vm = makeSenderVM(scope, listOf(
            senderItem("1", "matron"),
            JournalTimelineMapper.activityItem("Thinking…", Instant.now()),
            JournalTimelineMapper.toolStreamItem("1", "ls", "", false, Instant.now()),
        ))
        assertFalse(
            "ActivityIndicator / ToolStreamLive ephemeral rows must not count as senders",
            vm.hasMultipleSenders.value,
        )
    }

    /// Ports `testHasMultipleSenders_ephemeralStreamingTextRow_isExcluded`:
    /// the mid-turn streaming placeholder row (`JournalTimelineMapper
    /// .streamingItem`) borrows the real Text kind so it renders as a normal
    /// bubble while a reply streams in — but it hardcodes `sender = "agent"`
    /// and its id is prefixed `"eph:"`. A pure kind filter would still
    /// miscount it; it must be excluded by id too.
    @Test
    fun hasMultipleSenders_ephemeralStreamingTextRow_isExcluded() = vmTest { scope ->
        val vm = makeSenderVM(scope, listOf(
            senderItem("1", "matron"),
            JournalTimelineMapper.streamingItem("1", "partial reply…", Instant.now()),
        ))
        assertFalse(
            "the 'eph:' streaming placeholder row must not count as a second sender",
            vm.hasMultipleSenders.value,
        )
    }

    /// Ports `testHasMultipleSenders_twoRealSendersPlusEphemerals_isTrue`:
    /// two REAL distinct bot text senders plus ephemeral overlay rows still
    /// flip the flag — the ephemeral exclusion must not mask a genuine
    /// agent-chat multi-sender room.
    @Test
    fun hasMultipleSenders_twoRealSendersPlusEphemerals_isTrue() = vmTest { scope ->
        val vm = makeSenderVM(scope, listOf(
            senderItem("1", "dev-2"),
            senderItem("2", "dan-mac"),
            JournalTimelineMapper.activityItem("Thinking…", Instant.now()),
            JournalTimelineMapper.toolStreamItem("1", "ls", "", false, Instant.now()),
        ))
        assertTrue(vm.hasMultipleSenders.value)
    }
}

/// Paged-history [TimelineService] fake for the focus(seq) tests: [items]
/// re-emits the loaded window, and each [paginateBackward] call prepends the
/// next queued page and re-emits, mirroring the Apple suites'
/// `PagingFakeTimelineService`. Hand-written like every other fake here.
private class PagingFakeTimelineService(
    loaded: List<TimelineItem>,
    private val olderPages: MutableList<List<TimelineItem>>,
) : chat.matron.android.chat.TimelineService {
    private val snapshots =
        kotlinx.coroutines.flow.MutableSharedFlow<List<TimelineItem>>(replay = 1)
    private var current: List<TimelineItem> = loaded

    init {
        snapshots.tryEmit(current)
    }

    override fun items(): kotlinx.coroutines.flow.Flow<List<TimelineItem>> = snapshots

    override suspend fun paginateBackward(requestSize: Int): Boolean {
        if (olderPages.isEmpty()) return false
        current = olderPages.removeAt(0) + current
        snapshots.tryEmit(current)
        return true
    }

    override suspend fun sendText(body: String, inReplyTo: String?) {}
    override suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String) {}
    override suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
    override suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
    override suspend fun markAsRead() {}
}

/// [PagingFakeTimelineService] whose paginate parks on a gate until the test
/// calls [release] — lets a test dismiss the search bar deterministically
/// WHILE a deep jump's pagination is in flight (apple #172).
private class BlockingPagingFakeTimelineService(
    loaded: List<TimelineItem>,
    private val olderPages: MutableList<List<TimelineItem>>,
) : chat.matron.android.chat.TimelineService {
    private val snapshots = kotlinx.coroutines.flow.MutableSharedFlow<List<TimelineItem>>(replay = 1)
    private var current: List<TimelineItem> = loaded
    private var gate = CompletableDeferred<Unit>()
    @Volatile var paginateStarted = false
        private set

    init { snapshots.tryEmit(current) }

    override fun items(): kotlinx.coroutines.flow.Flow<List<TimelineItem>> = snapshots

    override suspend fun paginateBackward(requestSize: Int): Boolean {
        paginateStarted = true
        gate.await()
        if (olderPages.isEmpty()) return false
        current = olderPages.removeAt(0) + current
        snapshots.tryEmit(current)
        return true
    }

    fun release() { gate.complete(Unit) }

    override suspend fun sendText(body: String, inReplyTo: String?) {}
    override suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String) {}
    override suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
    override suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
    override suspend fun markAsRead() {}
}

/// Yields an initial snapshot, then lets a test push appended/altered
/// snapshots through the SAME items() subscription — the shape a live stream
/// commit takes (apple #166).
private class AppendableFakeTimelineService : chat.matron.android.chat.TimelineService {
    private val snapshots = kotlinx.coroutines.flow.MutableSharedFlow<List<TimelineItem>>(replay = 1)
    fun emit(items: List<TimelineItem>) { snapshots.tryEmit(items) }
    override fun items(): kotlinx.coroutines.flow.Flow<List<TimelineItem>> = snapshots
    override suspend fun paginateBackward(requestSize: Int): Boolean = false
    override suspend fun sendText(body: String, inReplyTo: String?) {}
    override suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String) {}
    override suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
    override suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
    override suspend fun markAsRead() {}
}
