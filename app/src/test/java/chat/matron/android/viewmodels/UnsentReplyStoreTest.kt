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
    fun aSendLeftByAnEarlierRunIsStrandedUntilDropped() {
        val a = staged("a.png")
        val s = store()
        s.beginSend(item, "send-1", UnsentReply("sending this", listOf(a)), s.epoch)
        s.beginSend(item, "send-2", UnsentReply("being queued"), s.epoch)
        s.markQueuing(item, "send-2", s.epoch)
        assertTrue("this run's own sends aren't stranded", s.strandedSends(item).isEmpty())

        val next = store()
        val stranded = next.strandedSends(item)
        assertEquals(
            mapOf(
                "send-1" to (UnsentReply("sending this", listOf(a)) to false),
                "send-2" to (UnsentReply("being queued") to true),
            ),
            stranded.mapValues { it.value.reply to it.value.queuing },
        )
        assertTrue(stranded.values.all { it.startedAt > 0 })
        assertEquals("the box itself is untouched", "", next.reply(item).text)
        next.dropStranded(item, "send-1", next.epoch)
        next.dropStranded(item, "send-2", next.epoch)
        assertTrue(store().strandedSends(item).isEmpty())
        assertTrue(boxes.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aSendThatEndedIsNotStranded() {
        val s = store()
        s.beginSend(item, "send-1", UnsentReply("sent"), s.epoch)
        s.endSend(item, "send-1", s.epoch)
        assertTrue(store().strandedSends(item).isEmpty())
    }

    @Test
    fun aSendWhoseFilesWereDeletedOnQueueingIsNotStranded() {
        val a = staged("a.png")
        val s = store()
        s.beginSend(item, "send-1", UnsentReply("sent", listOf(a)), s.epoch)
        a.deleteStagedCopy()
        assertTrue(store().strandedSends(item).isEmpty())
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

    @Test
    fun mergeDoesNotRepeatTextTheBoxAlreadyHolds() {
        val merged = UnsentReplyStore.merge(UnsentReply("back"), UnsentReply("typed\n\nback"))
        assertEquals("typed\n\nback", merged.text)
        assertEquals("back", UnsentReplyStore.merge(UnsentReply("back"), UnsentReply("back")).text)
    }
}
