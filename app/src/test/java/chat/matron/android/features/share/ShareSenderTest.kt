package chat.matron.android.features.share

import chat.matron.android.models.AttachmentBatchTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShareSenderTest {
    private val transport = FakeShareTransport()
    private val sender = ShareSender(transport) { "batch-1" }

    @Test
    fun aMessageAloneIsSentAsText() = runBlocking {
        sender.send("c1", "  hello  ", emptyList()) {}
        assertEquals(listOf(FakeShareTransport.Sent("c1", null, "hello", null)), transport.sent)
        assertEquals(1, transport.finishes)
        // Sending again does nothing: it went.
        sender.send("c1", "hello", emptyList()) {}
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun oneFileCarriesTheMessageAndNoBatch() = runBlocking {
        sender.send("c1", "Look", listOf(sharedFile("a.png"))) {}
        assertEquals(listOf(FakeShareTransport.Sent("c1", "a.png", "Look", null)), transport.sent)
    }

    @Test
    fun severalFilesGoAsOneBatchWithTheMessageOnTheFirst() = runBlocking {
        val progress = mutableListOf<ShareProgress>()
        sender.send("c1", "", listOf(sharedFile("a.png"), sharedFile("b.pdf", mime = "application/pdf"))) { progress += it }
        assertEquals(
            listOf(
                FakeShareTransport.Sent("c1", "a.png", null, AttachmentBatchTag("batch-1", 1, 2)),
                FakeShareTransport.Sent("c1", "b.pdf", null, AttachmentBatchTag("batch-1", 2, 2)),
            ),
            transport.sent,
        )
        assertEquals(ShareProgress.Step.CONNECTING, progress.first().step)
        assertEquals("Uploading b.pdf (2 of 2)", progress.first { it.fileIndex == 2 }.statusText)
        assertEquals(1.0, progress.last().fraction, 0.0)
    }

    @Test
    fun aRetrySendsOnlyWhatDidNotGoUnderTheSameBatch() = runBlocking {
        val files = listOf(sharedFile("a.png"), sharedFile("b.png"), sharedFile("c.png"))
        transport.failOnce += "b.png"
        try {
            sender.send("c1", "Three pictures", files) {}
            fail("expected the second upload to fail")
        } catch (_: IllegalStateException) {
        }
        assertTrue(sender.hasSentAnything)
        sender.send("c1", "Three pictures", files) {}
        assertEquals(listOf("a.png", "b.png", "c.png"), transport.sent.map { it.name })
        assertEquals(listOf("Three pictures", null, null), transport.sent.map { it.text })
        assertEquals(listOf(1, 2, 3), transport.sent.map { it.batch?.index })
        assertTrue(transport.sent.all { it.batch?.id == "batch-1" && it.batch?.total == 3 })
    }

    @Test
    fun aFailedFirstFileStillCarriesTheMessageOnRetry() = runBlocking {
        val files = listOf(sharedFile("a.png"))
        transport.failOnce += "a.png"
        runCatching { sender.send("c1", "Caption", files) {} }
        assertFalse(sender.hasSentAnything)
        sender.send("c1", "Caption", files) {}
        assertEquals(listOf(FakeShareTransport.Sent("c1", "a.png", "Caption", null)), transport.sent)
    }

    @Test
    fun aConnectionThatWontComeUpSendsNothing() = runBlocking {
        transport.connectError = ShareConnectError()
        runCatching { sender.send("c1", "hi", emptyList()) {} }
        assertTrue(transport.sent.isEmpty())
        assertEquals(
            "Couldn't reach Matron's server. Check your connection and try again.",
            ShareViewModel.sendErrorMessage(ShareConnectError()),
        )
    }
}
