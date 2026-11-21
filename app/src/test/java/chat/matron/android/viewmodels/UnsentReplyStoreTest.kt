package chat.matron.android.viewmodels

import chat.matron.android.models.AttachmentBatchTag
import chat.matron.android.models.StagedAttachment
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsentReplyStoreTest {
    private val root: File = Files.createTempDirectory("unsent-").toFile()
    private val boxes = File(root, "boxes")
    private val staging = File(root, "staging")
    private val item = UnsentReplyStore.Place.Item("it_1")
    private val convo = UnsentReplyStore.Place.Conversation("c1")

    private fun store() = UnsentReplyStore(boxes, staging)

    private fun staged(name: String, contents: String = "x"): StagedAttachment {
        val source = File(Files.createTempDirectory("src-").toFile(), name).apply { writeText(contents) }
        return StagedAttachment.stage(source, staging)
    }

    @Test
    fun aSavedReplySurvivesANewStore() {
        val a = staged("a.png").carrying(AttachmentBatchTag("b1", 1, 2))
        val first = store()
        first.save(item, UnsentReply("half a thought", listOf(a)), first.epoch)

        val reloaded = store().reply(item)
        assertEquals("half a thought", reloaded.text)
        assertEquals(listOf(a), reloaded.attachments)
    }

    @Test
    fun placesAreKeptApart() {
        val s = store()
        s.save(item, UnsentReply("for the item"), s.epoch)
        s.save(convo, UnsentReply("for the chat"), s.epoch)
        assertEquals("for the item", store().reply(item).text)
        assertEquals("for the chat", store().reply(convo).text)
    }

    @Test
    fun anEmptyBoxLeavesNoFile() {
        val s = store()
        s.save(item, UnsentReply("x"), s.epoch)
        s.save(item, UnsentReply("  "), s.epoch)
        assertTrue(boxes.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun anAttachmentWhoseFileHasGoneIsDropped() {
        val a = staged("a.png")
        val s = store()
        s.save(item, UnsentReply("", listOf(a)), s.epoch)
        a.deleteStagedCopy()
        assertTrue(store().reply(item).attachments.isEmpty())
    }

    @Test
    fun aSendStillInFlightWhenTheAppStoppedComesBack() {
        val a = staged("a.png")
        val s = store()
        s.beginSend(item, "send-1", UnsentReply("sending this", listOf(a)), s.epoch)
        s.save(item, UnsentReply("typed after"), s.epoch)

        val back = store().reply(item)
        assertEquals("typed after\n\nsending this", back.text)
        assertEquals(listOf(a), back.attachments)
    }

    @Test
    fun aSendThatEndedIsNotPutBack() {
        val s = store()
        s.beginSend(item, "send-1", UnsentReply("sent"), s.epoch)
        s.endSend(item, "send-1", s.epoch)
        assertEquals("", store().reply(item).text)
    }

    @Test
    fun aSendWhoseFilesWereDeletedOnQueueingIsNotPutBack() {
        val a = staged("a.png")
        val s = store()
        s.beginSend(item, "send-1", UnsentReply("sent", listOf(a)), s.epoch)
        a.deleteStagedCopy()
        assertEquals(UnsentReply(), store().reply(item))
    }

    @Test
    fun wipeAllDeletesBoxesAndFilesAndIgnoresLateWrites() {
        val a = staged("a.png")
        val s = store()
        val epoch = s.epoch
        s.save(item, UnsentReply("secret", listOf(a)), epoch)
        s.wipeAll()
        assertFalse(a.file.exists())
        s.save(item, UnsentReply("late"), epoch)
        assertEquals(UnsentReply(), s.reply(item))
        assertEquals(UnsentReply(), store().reply(item))
    }

    @Test
    fun sweepOrphansKeepsOnlyFilesASavedBoxNames() {
        val kept = staged("kept.png")
        val sending = staged("sending.png")
        val orphan = staged("orphan.png")
        val s = store()
        s.save(item, UnsentReply("", listOf(kept)), s.epoch)
        s.beginSend(convo, "send-1", UnsentReply("", listOf(sending)), s.epoch)

        store().sweepOrphans()
        assertTrue(kept.file.exists())
        assertTrue(sending.file.exists())
        assertFalse(orphan.file.exists())
    }

    @Test
    fun idsAreEscapedIntoSafeFileNames() {
        val odd = UnsentReplyStore.Place.Conversation("../a/b c")
        assertEquals("conversation-%2E%2E%2Fa%2Fb%20c.json", odd.fileName)
        val s = store()
        s.save(odd, UnsentReply("ok"), s.epoch)
        assertEquals("ok", store().reply(odd).text)
        assertTrue(File(boxes, odd.fileName).exists())
    }

    @Test
    fun restoreRefusesAPathOutsideItsStagingDirectory() {
        File(staging, "id1").mkdirs()
        val outside = File(root, "elsewhere.txt").apply { writeText("x") }
        assertNull(StagedAttachment.restore("id1", outside, "elsewhere.txt", "text/plain", 1, null, staging))
        val sneaky = File(File(staging, "id1"), "../../elsewhere.txt")
        assertNull(StagedAttachment.restore("id1", sneaky, "x", "text/plain", 1, null, staging))
    }

    @Test
    fun mergePutsTheReturnedReplyFirstWithoutDuplicates() {
        val a = staged("a.png"); val b = staged("b.png")
        val merged = UnsentReplyStore.merge(UnsentReply("back", listOf(a)), UnsentReply("typed", listOf(a, b)))
        assertEquals("typed\n\nback", merged.text)
        assertEquals(listOf(a, b), merged.attachments)
    }
}
