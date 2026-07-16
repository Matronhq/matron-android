package chat.matron.android.chat

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/// Test double for [TimelineService], ported from matron-apple's
/// `FakeTimelineService`. Reused by the view-model stage.
class FakeTimelineService : TimelineService {
    data class SentMedia(val filename: String, val mime: String, val sizeBytes: Int, val caption: String?)

    var snapshotsToEmit: List<List<TimelineItem>> = emptyList()
    var streamError: Throwable? = null
    val sentText = mutableListOf<String>()
    val sentInReplyTo = mutableListOf<String?>()
    val sentButtonResponses = mutableListOf<Pair<List<String>, String>>()
    val sentImages = mutableListOf<SentMedia>()
    val sentFiles = mutableListOf<SentMedia>()
    var paginateCalls = 0
    var markReadCalls = 0

    override fun items(): Flow<List<TimelineItem>> = flow {
        snapshotsToEmit.forEach { emit(it) }
        streamError?.let { throw it }
    }

    override suspend fun sendText(body: String, inReplyTo: String?) {
        sentText.add(body)
        sentInReplyTo.add(inReplyTo)
    }

    override suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String) {
        sentButtonResponses.add(selectedValues to inReplyTo)
    }

    override suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?) {
        sentImages.add(SentMedia(filename, mimeType, data.size, caption))
    }

    override suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?) {
        sentFiles.add(SentMedia(filename, mimeType, data.size, caption))
    }

    override suspend fun paginateBackward(requestSize: Int): Boolean {
        paginateCalls++
        return false
    }

    override suspend fun markAsRead() {
        markReadCalls++
    }
}

class TimelineServiceFakeTest {
    @Test fun streamsSnapshotsInOrder() = runBlocking {
        val fake = FakeTimelineService()
        val t0 = Instant.EPOCH
        fake.snapshotsToEmit = listOf(
            listOf(TimelineItem("1", "@a:s", t0, TimelineItem.Kind.Text("hi", null), true)),
            listOf(
                TimelineItem("1", "@a:s", t0, TimelineItem.Kind.Text("hi", null), true),
                TimelineItem("2", "@b:s", t0, TimelineItem.Kind.Text("hello", null), false),
            ),
        )
        val received = fake.items().toList()
        assertEquals(2, received.size)
        assertEquals(1, received[0].size)
        assertEquals(2, received[1].size)
    }

    @Test fun sendTextRecordsCalls() = runBlocking {
        val fake = FakeTimelineService()
        fake.sendText("/start")
        fake.sendText("hello")
        assertEquals(listOf("/start", "hello"), fake.sentText)
        assertEquals(listOf<String?>(null, null), fake.sentInReplyTo)
    }

    @Test fun sendTextWithReplyRecordsInReplyTo() = runBlocking {
        val fake = FakeTimelineService()
        fake.sendText("yes", inReplyTo = "prompt-evt-1")
        assertEquals(listOf("yes"), fake.sentText)
        assertEquals(listOf<String?>("prompt-evt-1"), fake.sentInReplyTo)
    }

    @Test fun sendButtonResponseRecordsValuesAndTarget() = runBlocking {
        val fake = FakeTimelineService()
        fake.sendButtonResponse(listOf("interrupt"), inReplyTo = "buttons-1")
        assertEquals(1, fake.sentButtonResponses.size)
        assertEquals(listOf("interrupt"), fake.sentButtonResponses[0].first)
        assertEquals("buttons-1", fake.sentButtonResponses[0].second)
    }

    @Test fun sendImageRecordsFilenameMimeAndSize() = runBlocking {
        val fake = FakeTimelineService()
        fake.sendImage(ByteArray(42) { 0xAB.toByte() }, "pic.png", "image/png", null)
        assertEquals(1, fake.sentImages.size)
        assertEquals("pic.png", fake.sentImages[0].filename)
        assertEquals("image/png", fake.sentImages[0].mime)
        assertEquals(42, fake.sentImages[0].sizeBytes)
    }

    @Test fun sendFileRecordsFilenameMimeAndSize() = runBlocking {
        val fake = FakeTimelineService()
        fake.sendFile(ByteArray(7) { 0x01 }, "report.pdf", "application/pdf", null)
        assertEquals(1, fake.sentFiles.size)
        assertEquals("report.pdf", fake.sentFiles[0].filename)
        assertEquals("application/pdf", fake.sentFiles[0].mime)
        assertEquals(7, fake.sentFiles[0].sizeBytes)
    }

    @Test fun paginateAndMarkAsReadRecordCalls() = runBlocking {
        val fake = FakeTimelineService()
        fake.paginateBackward(20)
        fake.paginateBackward(20)
        fake.markAsRead()
        assertEquals(2, fake.paginateCalls)
        assertEquals(1, fake.markReadCalls)
    }
}
