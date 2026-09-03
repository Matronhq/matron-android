package chat.matron.android.viewmodels

import chat.matron.android.chat.FakeTimelineService
import chat.matron.android.chat.SendGate
import chat.matron.android.models.ArgSuggestion
import chat.matron.android.models.BotCommand
import chat.matron.android.models.BotCommandCatalog
import chat.matron.android.models.SessionStatus
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/// Ported from matron-apple's `ComposerViewModelTests` plus the
/// `ComposerViewModel`-wiring half of `SentMessageHistoryTests` (the model half
/// lives in `SentMessageHistoryTest`). The Swift `[URL]` attach currency becomes
/// `File`s staged into an injected staging directory.
class ComposerViewModelTest {

    @Before
    fun setUp() {
        ComposerDraftMemory.resetForTesting()
    }

    private fun stagingDir(): File = Files.createTempDirectory("staging").toFile()

    private fun emptyRecentFolders() = RecentStartFolders(InMemoryKeyValueStore())

    private fun makeVM(
        roomID: String = "!test:s",
        timeline: FakeTimelineService = FakeTimelineService(),
        commands: List<BotCommand> = emptyList(),
        recentFolders: RecentStartFolders = emptyRecentFolders(),
    ) = ComposerViewModel(roomID, timeline, commands, recentFolders, stagingDir())

    private fun makeTempFile(named: String, contents: String = "hi"): File {
        val dir = Files.createTempDirectory("attach-test").toFile()
        val file = File(dir, named)
        file.writeText(contents)
        return file
    }

    // MARK: - palette + send basics

    @Test
    fun palette_isShownWhenInputStartsWithSlash() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.input = "/sta"
        assertTrue(vm.showPalette)
        assertTrue(vm.filteredCommands.any { it.trigger == "/start" })
    }

    @Test
    fun palette_isHiddenForRegularInput() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.input = "hello"
        assertFalse(vm.showPalette)
    }

    @Test
    fun palette_isHiddenAfterSpace() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.input = "/start workdir"
        assertFalse(vm.showPalette)
    }

    @Test
    fun selectingCommand_replacesInput_andClosesPalette() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.input = "/sta"
        vm.palettePinnedOpen = true
        vm.selectCommand(BotCommand("/start", "x", "[workdir]"))
        assertEquals("/start ", vm.input)
        assertFalse(vm.palettePinnedOpen)
    }

    @Test
    fun send_sendsTrimmedAndClearsInput() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.input = "  hello world  "
        vm.send()
        assertEquals(listOf("hello world"), fake.sentText)
        assertEquals("", vm.input)
        assertNull(vm.sendError.value)
    }

    @Test
    fun send_clearsInputImmediately_notAfterTheRoundTrip() = runBlocking {
        val fake = FakeTimelineService()
        val gate = SendGate()
        fake.sendGate = gate
        val vm = makeVM(timeline = fake)
        vm.input = "ok merge pull and restart"

        val send = launch { vm.send() }
        waitUntil { gate.started }

        assertEquals("", vm.input)
        assertTrue(fake.sentText.isEmpty())

        gate.open()
        send.join()
        assertEquals(listOf("ok merge pull and restart"), fake.sentText)
        assertEquals("", vm.input)
        assertNull(vm.sendError.value)
    }

    @Test
    fun send_restoresInput_andDraft_whenTheRoundTripFails() = runBlocking {
        val fake = FakeTimelineService()
        fake.nextSendError = RuntimeException("boom")
        val vm = makeVM(roomID = "!room:s", timeline = fake)
        vm.input = "keep me"

        vm.send()

        assertEquals("keep me", vm.input)
        assertEquals("boom", vm.sendError.value)
        assertEquals("keep me", ComposerDraftMemory.retrieve("!room:s"))
    }

    @Test
    fun send_doesNotClobberNewTyping_whenTheRoundTripFails() = runBlocking {
        val fake = FakeTimelineService()
        fake.nextSendError = RuntimeException("boom")
        val gate = SendGate()
        fake.sendGate = gate
        val vm = makeVM(roomID = "!room:s", timeline = fake)
        vm.input = "first message"

        val send = launch { vm.send() }
        waitUntil { gate.started }
        vm.input = "second message"
        gate.open()
        send.join()

        assertEquals("second message", vm.input)
        assertEquals("boom", vm.sendError.value)
    }

    @Test
    fun send_doesNothing_forEmptyInput() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.input = "   "
        vm.send()
        assertTrue(fake.sentText.isEmpty())
    }

    @Test
    fun send_recordsSendError_whenServiceThrows() = runBlocking {
        val fake = FakeTimelineService()
        fake.nextSendError = RuntimeException("boom")
        val vm = makeVM(timeline = fake)
        vm.input = "hi"
        vm.send()
        assertEquals("boom", vm.sendError.value)
        assertEquals("hi", vm.input)
    }

    @Test
    fun palette_staysClosed_afterCommandSelection() {
        // Pinned against /stop — a command with no argument suggestions —
        // because commands WITH suggestions (e.g. /start) now deliberately
        // reopen the palette in argument mode (apple #161).
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.selectCommand(BotCommand("/stop", "x"))
        assertEquals("/stop ", vm.input)
        assertFalse(vm.showPalette)
    }

    @Test
    fun palette_isHiddenForCommandWithTrailingSpace() {
        // A suggestion-free command followed by a space hides the palette so
        // it doesn't cover the next character; commands with suggestions keep
        // it open in argument mode instead.
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.input = "/stop "
        assertFalse(vm.showPalette)
    }

    @Test
    fun send_doesNothing_forWhitespaceOnlyInput_andSendErrorStaysNil() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.input = "   \t\n  "
        vm.send()
        assertTrue(fake.sentText.isEmpty())
        assertNull(vm.sendError.value)
    }

    @Test
    fun sendCommand_sendsTextVerbatimThroughTimeline() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.sendCommand("/compact")
        assertEquals(listOf("/compact"), fake.sentText)
        assertNull(vm.sendError.value)
    }

    @Test
    fun sendCommand_recordsSendError_whenServiceThrows() = runBlocking {
        val fake = FakeTimelineService()
        fake.nextSendError = RuntimeException("boom")
        val vm = makeVM(timeline = fake)
        vm.sendCommand("/compact")
        assertEquals("boom", vm.sendError.value)
    }

    @Test
    fun sendCommand_doesNotTouchComposerInput() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.input = "half-typed draft"
        vm.sendCommand("/compact")
        assertEquals("half-typed draft", vm.input)
        assertEquals(listOf("/compact"), fake.sentText)
    }

    @Test
    fun sendCommand_ignoresConcurrentTap_whileFirstInFlight() = runBlocking {
        val fake = FakeTimelineService()
        val gate = SendGate()
        fake.sendGate = gate
        val vm = makeVM(timeline = fake)
        // First tap: parks at the gate mid-send, marking the command in flight.
        val first = launch { vm.sendCommand("/compact") }
        yield()
        // Second tap while the first is still in flight must be ignored.
        val second = launch { vm.sendCommand("/compact") }
        yield()
        gate.open()
        first.join()
        second.join()
        assertEquals(listOf("/compact"), fake.sentText)
    }

    @Test
    fun sendCommand_success_doesNotClearExistingSendError() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        // Seed a composer send() error the way a failed message would.
        fake.nextSendError = RuntimeException("compose boom")
        vm.input = "hello"
        vm.send()
        assertEquals("compose boom", vm.sendError.value)
        // A successful banner /compact must not wipe the composer's error.
        vm.sendCommand("/compact")
        assertEquals("compose boom", vm.sendError.value)
        assertEquals(listOf("/compact"), fake.sentText)
    }

    @Test
    fun reportAttachmentError_recordsSendError() {
        val vm = makeVM()
        vm.reportAttachmentError("boom")
        assertEquals("boom", vm.sendError.value)
    }

    /// ComposerViewModel instances are cached per-room (ChatVMCache) and
    /// reused on revisit, so ComposerView's room-keyed DisposableEffect calls
    /// dismissError() on (re-)entry — this is the model-side half of that
    /// fix: a stale error from a prior visit must not linger once cleared.
    @Test
    fun dismissError_clearsSendError() {
        val vm = makeVM()
        vm.reportAttachmentError("boom")
        assertEquals("boom", vm.sendError.value)

        vm.dismissError()

        assertNull(vm.sendError.value)
    }

    @Test
    fun sendVoiceNote_sendsAudioFileAndDeletesTemp() = runBlocking {
        val tmp = makeTempFile("voice.m4a", "AUDIO")
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.reportAttachmentError("old failure")

        vm.sendVoiceNote(tmp, 2.seconds)

        assertEquals(1, fake.sentFiles.size)
        assertEquals("voice-note.m4a", fake.sentFiles.first().filename)
        assertEquals("audio/mp4", fake.sentFiles.first().mime)
        assertEquals(5, fake.sentFiles.first().sizeBytes)
        assertFalse(tmp.exists())
        assertNull(vm.sendError.value)
    }

    @Test
    fun sendVoiceNote_recordsError_andKeepsTheFileForRetry_whenSendFails() = runBlocking {
        val tmp = makeTempFile("voice.m4a", "AUDIO")
        val fake = FakeTimelineService()
        fake.nextSendError = RuntimeException("boom")
        val vm = makeVM(timeline = fake)

        vm.sendVoiceNote(tmp, 1.seconds)

        assertEquals("boom", vm.sendError.value)
        assertTrue(tmp.exists())
    }

    @Test
    fun filteredCommands_stripsLeadingWhitespace() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.input = "  /sta"
        assertTrue(vm.showPalette)
        assertFalse(vm.filteredCommands.isEmpty())
        assertTrue(vm.filteredCommands.any { it.trigger == "/start" })
    }

    // MARK: - staged attachments

    @Test
    fun attachFiles_stagesTheFile_andSendsNothingYet() = runBlocking {
        val file = makeTempFile("hello.txt")
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)

        vm.attachFiles(listOf(file))

        assertEquals(1, vm.stagedAttachments.value.size)
        assertEquals("hello.txt", vm.stagedAttachments.value.first().filename)
        assertEquals(2L, vm.stagedAttachments.value.first().sizeBytes)
        assertTrue(fake.sentFiles.isEmpty())
        assertNull(vm.sendError.value)
    }

    @Test
    fun stagedAttachment_survivesTheOriginalBeingDeleted() = runBlocking {
        val file = makeTempFile("doomed.txt")
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(file))

        file.delete()
        vm.send()

        assertEquals(1, fake.sentFiles.size)
        assertEquals(2, fake.sentFiles.first().sizeBytes)
        assertNull(vm.sendError.value)
    }

    @Test
    fun send_withAnImageAndText_sendsTheTextAsTheCaption() = runBlocking {
        val file = makeTempFile("shot.png")
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(file))
        vm.input = "what's wrong with this?"

        vm.send()

        assertEquals(1, fake.sentImages.size)
        assertEquals("what's wrong with this?", fake.sentImages.first().caption)
        assertTrue(fake.sentText.isEmpty())
        assertTrue(vm.input.isEmpty())
        assertTrue(vm.stagedAttachments.value.isEmpty())
    }

    @Test
    fun send_attachmentUpload_reportsProgressAndClearsWhenDone() = runBlocking {
        val file = makeTempFile("shot.png")
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(file))

        vm.send()

        assertEquals(
            "uploads must go through the progress-capable send",
            listOf(true), fake.mediaSendsWithProgressHandler,
        )
        assertNull("progress strip clears once the batch finishes", vm.uploadProgress.value)
    }

    @Test
    fun uploadProgress_labels() {
        val single = ComposerViewModel.UploadProgress("shot.png", index = 1, count = 1, fraction = 0.2)
        assertEquals("Uploading shot.png…", single.label)
        val batch = ComposerViewModel.UploadProgress("b.png", index = 2, count = 3, fraction = 0.7)
        assertEquals("Uploading 2 of 3…", batch.label)
    }

    @Test
    fun send_withAttachmentAndNoText_sendsWithNoCaption() = runBlocking {
        val file = makeTempFile("shot.png")
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(file))

        assertTrue(vm.canSend)
        vm.send()

        assertEquals(1, fake.sentImages.size)
        assertNull(fake.sentImages.first().caption)
    }

    @Test
    fun send_withSeveralAttachments_captionsOnlyTheFirst() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(makeTempFile("a.png"), makeTempFile("b.png")))
        vm.input = "compare these"

        vm.send()

        assertEquals(listOf("a.png", "b.png"), fake.sentImages.map { it.filename })
        assertEquals("compare these", fake.sentImages.first().caption)
        assertNull(fake.sentImages.last().caption)
    }

    /// Ported from matron-apple's `ComposerViewModelTests.
    /// test_send_withSeveralAttachments_stampsOneSharedBatchTag`. A
    /// multi-attachment send stamps every upload with the same batch id and
    /// its 1-based place, so the bridge can gather the frames back into the
    /// one message the user wrote instead of injecting the first image and
    /// busy-queueing the rest.
    @Test
    fun send_withSeveralAttachments_stampsOneSharedBatchTag() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(makeTempFile("a.png"), makeTempFile("b.png"), makeTempFile("c.png")))

        vm.send()

        val tags = fake.mediaSendBatchTags.filterNotNull()
        assertEquals("every attachment of a batch send must carry the tag", 3, tags.size)
        assertEquals("one send = one batch id", 1, tags.map { it.id }.toSet().size)
        assertEquals(listOf(1, 2, 3), tags.map { it.index })
        assertEquals(listOf(3, 3, 3), tags.map { it.total })
    }

    /// Ported from matron-apple's `ComposerViewModelTests.
    /// test_retry_afterAMidBatchFailure_reusesTheOriginalBatchTag`
    /// (matron-apple#157). A retried attachment must rejoin the batch it
    /// fell out of. The bridge gathers frames by batch_id: retried under
    /// the ORIGINAL id, the frame either deposits into the still-open
    /// gather (completing the user's one message) or — batch already
    /// finalized — takes the per-frame path. A fresh id could do neither:
    /// the frame would wait forever for siblings that already went out,
    /// and the message would arrive fractured.
    @Test
    fun retry_afterAMidBatchFailure_reusesTheOriginalBatchTag() = runBlocking {
        val fake = FakeTimelineService()
        fake.failSendsAfter = 1
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(makeTempFile("a.png"), makeTempFile("b.png"), makeTempFile("c.png")))

        vm.send()

        assertEquals("precondition: only a.png landed", listOf("a.png"), fake.sentImages.map { it.filename })
        assertEquals(
            "precondition: the unsent pair came back to the tray",
            listOf("b.png", "c.png"), vm.stagedAttachments.value.map { it.filename },
        )
        val original = fake.mediaSendBatchTags.filterNotNull().first()

        // The network comes back and the user hits send again.
        fake.failSendsAfter = null
        vm.send()

        assertEquals(listOf("a.png", "b.png", "c.png"), fake.sentImages.map { it.filename })
        val retried = fake.mediaSendBatchTags.takeLast(2).filterNotNull()
        assertEquals(
            "retried frames must carry the ORIGINAL batch id, not a fresh one",
            listOf(original.id, original.id), retried.map { it.id },
        )
        assertEquals("…in their original 1-based places", listOf(2, 3), retried.map { it.index })
        assertEquals("…still completing a batch of three", listOf(3, 3), retried.map { it.total })

        // The preserved tag must not leak past the attachments that failed
        // out of it: the next send is its own batch under a new id.
        vm.attachFiles(listOf(makeTempFile("d.png"), makeTempFile("e.png")))
        vm.send()

        val fresh = fake.mediaSendBatchTags.takeLast(2).filterNotNull()
        assertEquals("a fresh multi-attachment send is tagged as usual", 2, fresh.size)
        assertTrue("a new send mints a new batch id", fresh.first().id != original.id)
        assertEquals(listOf(1, 2), fresh.map { it.index })
        assertEquals(listOf(2, 2), fresh.map { it.total })
    }

    /// Ported from matron-apple's `ComposerViewModelTests.
    /// test_retry_ofTheLastRemainingAttachment_isNotDemotedToAnUntaggedSend`
    /// (matron-apple#157). The drops-it half of the retry bug: with only
    /// ONE attachment left to retry, the re-send used to look like a fresh
    /// single-attachment send and went out untagged — leaving the bridge
    /// no way to connect the frame to the batch its sibling had already
    /// opened.
    @Test
    fun retry_ofTheLastRemainingAttachment_isNotDemotedToAnUntaggedSend() = runBlocking {
        val fake = FakeTimelineService()
        fake.failSendsAfter = 1
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(makeTempFile("a.png"), makeTempFile("b.png")))

        vm.send()

        assertEquals(
            "precondition: b.png failed and came back alone",
            listOf("b.png"), vm.stagedAttachments.value.map { it.filename },
        )
        val original = fake.mediaSendBatchTags.filterNotNull().first()

        fake.failSendsAfter = null
        vm.send()

        val retried = fake.mediaSendBatchTags.last()
        assertNotNull("the lone retry must still carry its batch tag", retried)
        assertEquals(original.id, retried!!.id)
        assertEquals(2, retried.index)
        assertEquals(2, retried.total)
    }

    /// Ported from matron-apple's `ComposerViewModelTests.
    /// test_send_withOneAttachment_carriesNoBatchTag`. A single attachment
    /// goes untagged — its frame must stay byte-identical to what an older
    /// bridge already understands.
    @Test
    fun send_withOneAttachment_carriesNoBatchTag() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(makeTempFile("shot.png")))

        vm.send()

        assertEquals(listOf<chat.matron.android.models.AttachmentBatchTag?>(null), fake.mediaSendBatchTags)
    }

    @Test
    fun send_withTextOnly_stillSendsPlainText() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.input = "just talking"
        vm.send()
        assertEquals(listOf("just talking"), fake.sentText)
        assertTrue(fake.sentImages.isEmpty())
    }

    @Test
    fun send_withNothing_doesNothing() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        assertFalse(vm.canSend)
        vm.send()
        assertTrue(fake.sentText.isEmpty())
        assertTrue(fake.sentFiles.isEmpty())
    }

    @Test
    fun removeAttachment_dropsItFromTheTray() = runBlocking {
        val vm = makeVM()
        vm.attachFiles(listOf(makeTempFile("keep.png"), makeTempFile("drop.png")))
        val doomed = vm.stagedAttachments.value.last()

        vm.removeAttachment(doomed.id)

        assertEquals(listOf("keep.png"), vm.stagedAttachments.value.map { it.filename })
        assertFalse(doomed.file.exists())
    }

    @Test
    fun send_whenTheAttachmentFails_keepsItStagedAndRestoresTheText() = runBlocking {
        val fake = FakeTimelineService()
        fake.nextSendError = RuntimeException("boom")
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(makeTempFile("shot.png")))
        vm.input = "look at this"

        vm.send()

        assertNotNull(vm.sendError.value)
        assertEquals(listOf("shot.png"), vm.stagedAttachments.value.map { it.filename })
        assertEquals("look at this", vm.input)
    }

    @Test
    fun send_whenALaterAttachmentFails_doesNotRestoreTheDeliveredCaption() = runBlocking {
        val fake = FakeTimelineService()
        fake.failSendsAfter = 1
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(makeTempFile("a.png"), makeTempFile("b.png")))
        vm.input = "compare these"

        vm.send()

        assertEquals(listOf("a.png"), fake.sentImages.map { it.filename })
        assertEquals(listOf("b.png"), vm.stagedAttachments.value.map { it.filename })
        assertTrue(vm.input.isEmpty())
        assertNotNull(vm.sendError.value)
    }

    @Test
    fun send_failedAttachment_doesNotOverwriteNewTyping() = runBlocking {
        val fake = FakeTimelineService()
        val gate = SendGate()
        fake.sendGate = gate
        fake.nextSendError = RuntimeException("boom")
        val vm = makeVM(timeline = fake)
        vm.attachFiles(listOf(makeTempFile("shot.png")))
        vm.input = "first message"

        val sending = launch { vm.send() }
        waitUntil { gate.started }
        vm.input = "second message"
        gate.open()
        sending.join()

        assertEquals("second message", vm.input)
        assertNotNull(vm.sendError.value)
    }

    @Test
    fun discardAttachments_emptiesTheTrayAndDeletesTheCopies() = runBlocking {
        val vm = makeVM()
        vm.attachFiles(listOf(makeTempFile("shot.png")))
        val staged = vm.stagedAttachments.value.first()

        vm.discardAttachments()

        assertTrue(vm.stagedAttachments.value.isEmpty())
        assertFalse(staged.file.exists())
    }

    @Test
    fun attachFiles_unreadableURL_reportsTheErrorAndStagesNothing() = runBlocking {
        val vm = makeVM()
        vm.attachFiles(listOf(File("/nonexistent/nope.png")))
        assertTrue(vm.stagedAttachments.value.isEmpty())
        assertNotNull(vm.sendError.value)
    }

    // MARK: - recent-folder completion

    @Test
    fun recentFolderArgument_extractsPath() {
        assertEquals("~/x", ComposerViewModel.recentFolderArgument("/start ~/x"))
    }

    @Test
    fun recentFolderArgument_skipsLeadingFlags() {
        assertEquals("~/x", ComposerViewModel.recentFolderArgument("/start --browser ~/x"))
    }

    @Test
    fun recentFolderArgument_workdirAbsolutePath() {
        assertEquals("/abs/path", ComposerViewModel.recentFolderArgument("/workdir /abs/path"))
    }

    @Test
    fun recentFolderArgument_flagOnly_isNil() {
        assertNull(ComposerViewModel.recentFolderArgument("/start --claude"))
    }

    @Test
    fun recentFolderArgument_acceptsBangPrefix() {
        assertEquals("~/x", ComposerViewModel.recentFolderArgument("!start ~/x"))
    }

    @Test
    fun recentFolderArgument_plainText_isNil() {
        assertNull(ComposerViewModel.recentFolderArgument("just a message"))
    }

    @Test
    fun recentFolderArgument_commandWithoutArg_isNil() {
        assertNull(ComposerViewModel.recentFolderArgument("/start"))
    }

    @Test
    fun send_recordsStartFolder_thenSuggested() = runBlocking {
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge)
        vm.input = "/start ~/yearbook-app"
        vm.send()

        vm.input = "/start ~/y"
        assertTrue(vm.showPalette)
        assertEquals(listOf("~/yearbook-app"), vm.folderSuggestions)
    }

    @Test
    fun folderSuggestions_emptyPartial_returnsAllRecents() {
        val store = emptyRecentFolders()
        store.record("~/one")
        store.record("~/two")
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge, recentFolders = store)
        vm.input = "/workdir "
        assertTrue(vm.showPalette)
        assertEquals(listOf("~/two", "~/one"), vm.folderSuggestions)
    }

    @Test
    fun folderSuggestions_gatedToStartAndWorkdir() {
        val store = emptyRecentFolders()
        store.record("~/proj")
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge, recentFolders = store)
        vm.input = "/status ~/p"
        assertTrue(vm.folderSuggestions.isEmpty())
        // A non-flag token before the partial doesn't qualify — the path
        // slot is already filled (flags ahead of the path are fine now).
        vm.input = "/start ~/other ~/p"
        assertTrue(vm.folderSuggestions.isEmpty())
    }

    @Test
    fun selectFolder_replacesTrailingPartial_noTrailingSpace() {
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge)
        vm.input = "/start ~/y"
        vm.selectFolder("~/yearbook-app")
        assertEquals("/start ~/yearbook-app", vm.input)
    }

    @Test
    fun selectFolder_emptyPartial_appendsPath() {
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge)
        vm.input = "/workdir "
        vm.selectFolder("/srv/app")
        assertEquals("/workdir /srv/app", vm.input)
    }

    @Test
    fun selectFolder_dismissesPalette() {
        val store = emptyRecentFolders()
        store.record("~/yearbook-app")
        store.record("~/yearbook-api")
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge, recentFolders = store)
        vm.input = "/start ~/y"
        assertFalse(vm.folderSuggestions.isEmpty())

        vm.selectFolder("~/yearbook-app")
        assertTrue(vm.folderSuggestions.isEmpty())
        assertFalse(vm.showPalette)

        vm.input = "/start ~/year"
        assertFalse(vm.folderSuggestions.isEmpty())
    }

    @Test
    fun folderSuppression_clearsOnSendAndOnEdit() = runBlocking {
        val store = emptyRecentFolders()
        store.record("~/yearbook-app")
        store.record("~/yearbook-app-v2")
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge, recentFolders = store)
        vm.input = "/start ~/year"
        vm.selectFolder("~/yearbook-app")
        assertTrue(vm.folderSuggestions.isEmpty())
        vm.send()

        vm.input = "/start ~/yearbook-app"
        vm.handleInputChange()
        assertEquals(listOf("~/yearbook-app-v2"), vm.folderSuggestions)

        vm.selectFolder("~/yearbook-app-v2")
        assertTrue(vm.folderSuggestions.isEmpty())
        vm.input = "/start ~/yearbook-app"
        vm.handleInputChange()
        assertFalse(vm.folderSuggestions.isEmpty())
    }

    @Test
    fun folderSuggestions_omitFullyTypedPath() {
        val store = emptyRecentFolders()
        store.record("~/yearbook-app")
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge, recentFolders = store)
        vm.input = "/start ~/YEARBOOK-APP"
        assertTrue(vm.folderSuggestions.isEmpty())
        assertFalse(vm.showPalette)
    }

    // MARK: - palette keyboard navigation

    @Test
    fun paletteSelection_startsNilAndMovesWithinBounds() {
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge)
        vm.input = "/"
        assertNull(vm.paletteSelection.value)
        val count = vm.paletteItemCount
        assertTrue(count > 1)

        vm.paletteMoveDown()
        assertEquals(0, vm.paletteSelection.value)
        repeat(count + 3) { vm.paletteMoveDown() }
        assertEquals(count - 1, vm.paletteSelection.value)

        repeat(count + 3) { vm.paletteMoveUp() }
        assertEquals(0, vm.paletteSelection.value)
    }

    @Test
    fun paletteMoveUp_fromNoSelection_startsAtLastRow() {
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge)
        vm.input = "/"
        vm.paletteMoveUp()
        assertEquals(vm.paletteItemCount - 1, vm.paletteSelection.value)
    }

    @Test
    fun confirmPaletteSelection_picksHighlightedCommand() {
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge)
        vm.input = "/sta"
        val expected = vm.filteredCommands[0].trigger
        vm.paletteMoveDown()
        assertTrue(vm.confirmPaletteSelection())
        assertEquals("$expected ", vm.input)
        assertNull(vm.paletteSelection.value)
    }

    @Test
    fun confirmPaletteSelection_withoutHighlight_fallsThroughToSend() {
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge)
        vm.input = "/start"
        assertFalse(vm.confirmPaletteSelection())
        assertEquals("/start", vm.input)
    }

    @Test
    fun confirmPaletteSelection_picksHighlightedFolder() {
        val store = emptyRecentFolders()
        store.record("~/one")
        store.record("~/two")
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge, recentFolders = store)
        // "~" filters the palette to folder rows alone (no flag matches),
        // most-recent-first: row 0 is "~/two", row 1 is "~/one".
        vm.input = "/start ~"
        vm.paletteMoveDown()
        vm.paletteMoveDown()
        assertTrue(vm.confirmPaletteSelection())
        assertEquals("/start ~/one", vm.input)
        assertNull(vm.paletteSelection.value)
    }

    @Test
    fun paletteSelection_resetsOnUserEdit() {
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge)
        vm.input = "/"
        vm.paletteMoveDown()
        vm.paletteMoveDown()
        assertEquals(1, vm.paletteSelection.value)

        vm.input = "/sta"
        vm.handleInputChange()
        assertNull(vm.paletteSelection.value)
    }

    @Test
    fun paletteMove_noOpDuringHistoryWalk() = runBlocking {
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge)
        vm.input = "/start"
        vm.send()
        vm.recallOlder()
        assertTrue(vm.isNavigatingHistory.value)
        assertEquals("/start", vm.input)
        assertTrue(vm.showPalette)

        vm.paletteMoveDown()
        vm.paletteMoveUp()
        assertNull(vm.paletteSelection.value)
    }

    @Test
    fun paletteMove_noOpWhenPaletteHidden() {
        val vm = makeVM(roomID = "!r", commands = BotCommandCatalog.claudeBridge)
        vm.input = "plain message"
        vm.paletteMoveDown()
        assertNull(vm.paletteSelection.value)
        assertFalse(vm.confirmPaletteSelection())
    }

    // MARK: - sent-message recall wiring (ComposerViewModel half of SentMessageHistoryTests)

    @Test
    fun send_recordsIntoHistory_recallableViaUp() = runBlocking {
        val vm = makeVM(roomID = "!r")
        vm.input = "  hello  "
        vm.send()
        assertEquals("", vm.input)
        assertFalse(vm.isNavigatingHistory.value)

        vm.recallOlder()
        assertEquals("hello", vm.input)
        assertTrue(vm.isNavigatingHistory.value)
    }

    @Test
    fun recallOlder_onEmptyHistory_isNoOp() {
        val vm = makeVM(roomID = "!r")
        vm.recallOlder()
        assertEquals("", vm.input)
        assertFalse(vm.isNavigatingHistory.value)
    }

    @Test
    fun recallUpDown_walksAndRestoresInProgressDraft() = runBlocking {
        val vm = makeVM(roomID = "!r")
        vm.input = "first"; vm.send()
        vm.input = "second"; vm.send()

        vm.input = "wip"
        vm.handleInputChange()
        vm.recallOlder()
        assertEquals("second", vm.input)
        vm.recallOlder()
        assertEquals("first", vm.input)

        vm.recallNewer()
        assertEquals("second", vm.input)
        vm.recallNewer()
        assertEquals("wip", vm.input)
        assertFalse(vm.isNavigatingHistory.value)
    }

    @Test
    fun userEdit_exitsNavigation() = runBlocking {
        val vm = makeVM(roomID = "!r")
        vm.input = "sent"; vm.send()

        vm.recallOlder()
        assertEquals("sent", vm.input)
        assertTrue(vm.isNavigatingHistory.value)

        vm.input = "sentX"
        vm.handleInputChange()
        assertFalse(vm.isNavigatingHistory.value)

        vm.recallNewer()
        assertEquals("sentX", vm.input)
    }

    @Test
    fun programmaticRecallWrite_doesNotExitNavigation() = runBlocking {
        val vm = makeVM(roomID = "!r")
        vm.input = "a"; vm.send()
        vm.input = "b"; vm.send()

        vm.recallOlder() // input -> "b"
        vm.handleInputChange() // deferred onChange for the recall write
        assertTrue(vm.isNavigatingHistory.value)
        vm.recallOlder() // still navigating -> "a"
        assertEquals("a", vm.input)
    }

    @Test
    fun exitHistoryNavigation_restoresDraft_forPersistence() = runBlocking {
        val vm = makeVM(roomID = "!r")
        vm.input = "sent"; vm.send()

        vm.input = "half-typed draft"
        vm.handleInputChange()
        vm.recallOlder()
        assertEquals("sent", vm.input)

        vm.exitHistoryNavigation()
        assertEquals("half-typed draft", vm.input)
        assertFalse(vm.isNavigatingHistory.value)

        vm.exitHistoryNavigation()
        assertEquals("half-typed draft", vm.input)
    }

    // MARK: - Argument suggestions (apple #161, 2026-08-10 spec phase 1)

    private fun recentsWith(vararg paths: String) = emptyRecentFolders().also { store -> paths.forEach { store.record(it) } }

    /// Convenience: the `Argument` values currently offered.
    private fun argumentValues(vm: ComposerViewModel) =
        vm.paletteSuggestions.mapNotNull { (it as? PaletteSuggestion.Argument)?.suggestion?.value }

    @Test
    fun recentFolderArgument_uppercaseCommand_stillExtracts() {
        // Folder completion matches /START case-insensitively, so a sent
        // uppercase line must still record the path — one case rule.
        assertEquals("~/x", ComposerViewModel.recentFolderArgument("/START ~/x"))
    }

    @Test
    fun recentFolderArgument_skipsSmartDashedFlags() {
        // "—browser" (em dash) is a flag the bridge normalizes, not a folder —
        // recording it would poison the recents list.
        assertEquals("~/x", ComposerViewModel.recentFolderArgument("/start \u2014browser ~/x"))
    }

    @Test
    fun folderSuggestions_uppercaseCommand_stillCompletes() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge, recentFolders = recentsWith("~/proj"))
        vm.input = "/START ~"
        assertEquals(listOf("~/proj"), vm.folderSuggestions)
    }

    @Test
    fun folderSuggestions_allowedAfterFlags() {
        // The bridge's grammar is `/start [flags] [workdir]`, so folder
        // completion must survive flag tokens ahead of the path.
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge, recentFolders = recentsWith("~/proj"))
        vm.input = "/start --browser ~/p"
        assertEquals(listOf("~/proj"), vm.folderSuggestions)
    }

    @Test
    fun folderSuggestions_allowedAfterSmartDashedFlag() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge, recentFolders = recentsWith("~/proj"))
        vm.input = "/start \u2014browser ~/p"
        assertEquals(listOf("~/proj"), vm.folderSuggestions)
    }

    @Test
    fun folderSuggestions_notOfferedOncePathSlotFilled() {
        // A non-flag token before the partial doesn't qualify — the path slot
        // is already filled.
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge, recentFolders = recentsWith("~/proj"))
        vm.input = "/start ~/other ~/p"
        assertTrue(vm.folderSuggestions.isEmpty())
    }

    @Test
    fun completedCommand_opensPaletteInArgumentMode() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.input = "/restart "
        assertTrue(vm.showPalette)
        assertEquals(listOf("--force", "--browser"), argumentValues(vm))
        assertEquals(2, vm.paletteItemCount)
    }

    @Test
    fun paletteSuggestions_argumentsPrecedeFolders() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge, recentFolders = recentsWith("~/proj"))
        vm.input = "/start "
        val start = BotCommandCatalog.claudeBridge.first { it.trigger == "/start" }
        assertEquals(
            start.argSuggestions.map { PaletteSuggestion.Argument(it) } + PaletteSuggestion.Folder("~/proj"),
            vm.paletteSuggestions,
        )
    }

    @Test
    fun selectSuggestion_argument_insertsValueAndSpace_thenOffersRest() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.input = "/restart --f"
        vm.selectSuggestion(PaletteSuggestion.Argument(ArgSuggestion("--force")))
        assertEquals("/restart --force ", vm.input)
        assertEquals(listOf("--browser"), argumentValues(vm))
        assertTrue(vm.showPalette)
    }

    @Test
    fun selectSuggestion_lastValue_dismissesPalette() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.input = "/switch "
        vm.selectSuggestion(PaletteSuggestion.Argument(ArgSuggestion("claude")))
        assertEquals("/switch claude ", vm.input)
        assertFalse("a value fills the single slot, so nothing is left to offer", vm.showPalette)
    }

    @Test
    fun selectSuggestion_folder_delegatesToFolderPick() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge)
        vm.input = "/start ~/y"
        vm.selectSuggestion(PaletteSuggestion.Folder("~/yearbook-app"))
        assertEquals("/start ~/yearbook-app", vm.input)
    }

    @Test
    fun confirmPaletteSelection_picksFromUnifiedList() {
        val vm = makeVM(commands = BotCommandCatalog.claudeBridge, recentFolders = recentsWith("~/proj"))
        vm.input = "/start "
        // Walk the highlight to the folder row: 3 argument rows precede it.
        repeat(4) { vm.paletteMoveDown() }
        assertTrue(vm.confirmPaletteSelection())
        assertEquals("/start ~/proj", vm.input)
    }

    // MARK: - Session-derived argument suggestions (apple #163)

    /// Stands in for the `ChatViewModel` the composer reads its session status
    /// from — the pair is created together by the chat-list VM cache, and the
    /// composer holds a closure back to the chat half.
    private class StatusHolder(var status: SessionStatus?) {
        var reads = 0
            private set
        fun read(): SessionStatus? { reads++; return status }
    }

    private fun composer(holder: StatusHolder) = ComposerViewModel(
        "!r", FakeTimelineService(), BotCommandCatalog.claudeBridge, emptyRecentFolders(), stagingDir(),
        sessionStatus = { holder.read() },
    )

    /// The status is read only once a session-derived command has matched —
    /// reading it eagerly would have the composer observe every status frame
    /// while the user types ordinary prose.
    @Test
    fun ordinaryTypingDoesNotReadTheSessionStatus() {
        val holder = StatusHolder(SessionStatus(modelOptions = listOf(SessionStatus.Option("opus", "Opus"))))
        val vm = composer(holder)
        vm.input = "just chatting about /model"
        vm.showPalette
        vm.input = "/restart --f"
        vm.showPalette
        assertEquals(0, holder.reads)
        vm.input = "/model "
        vm.showPalette
        assertTrue(holder.reads > 0)
    }

    @Test
    fun modelAndEffort_offerTheSessionsLists() {
        val holder = StatusHolder(SessionStatus(
            modelOptions = listOf(SessionStatus.Option("opus", "Opus"), SessionStatus.Option("sonnet", "Sonnet")),
            effortLevels = listOf(SessionStatus.Option("high", "High")),
        ))
        val vm = composer(holder)
        vm.input = "/model "
        assertTrue(vm.showPalette)
        assertEquals(listOf("opus", "sonnet"), argumentValues(vm))
        assertEquals(2, vm.paletteItemCount)
        vm.input = "/effort "
        assertEquals(listOf("high"), argumentValues(vm))
    }

    /// The composer reads the status live rather than holding a copy, so a
    /// later status frame reaches the palette without anything pushing it.
    @Test
    fun laterStatusFrame_reachesThePalette() {
        val holder = StatusHolder(null)
        val vm = composer(holder)
        vm.input = "/model "
        assertEquals(emptyList<String>(), argumentValues(vm))
        holder.status = SessionStatus(modelOptions = listOf(SessionStatus.Option("opus", "Opus")))
        assertEquals(listOf("opus"), argumentValues(vm))
    }

    @Test
    fun selectingASessionValue_insertsItAndDismisses() {
        val holder = StatusHolder(SessionStatus(modelOptions = listOf(SessionStatus.Option("opus", "Opus"))))
        val vm = composer(holder)
        vm.input = "/model op"
        vm.selectSuggestion(PaletteSuggestion.Argument(ArgSuggestion("opus", label = "Opus")))
        assertEquals("/model opus ", vm.input)
        assertFalse(vm.showPalette)
    }

    /// Keyboard selection walks the same rows as a tap.
    @Test
    fun confirmPaletteSelection_picksASessionValue() {
        val holder = StatusHolder(SessionStatus(
            modelOptions = listOf(SessionStatus.Option("opus", "Opus"), SessionStatus.Option("sonnet", "Sonnet")),
        ))
        val vm = composer(holder)
        vm.input = "/model "
        vm.paletteMoveDown()
        vm.paletteMoveDown()
        assertTrue(vm.confirmPaletteSelection())
        assertEquals("/model sonnet ", vm.input)
    }
}
