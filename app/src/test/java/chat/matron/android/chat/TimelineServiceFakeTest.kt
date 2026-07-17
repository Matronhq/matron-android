package chat.matron.android.chat

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/// Lets a test hold a send suspended mid-flight and release it on demand, so
/// "what does the composer look like DURING the round-trip" is deterministic.
/// Ported from the Swift `SendGate` actor; [CompletableDeferred] replaces the
/// `CheckedContinuation`.
class SendGate {
    private val startedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val openSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

    val started: Boolean get() = startedSignal.isCompleted

    fun markStarted() {
        if (!startedSignal.isCompleted) startedSignal.complete(Unit)
    }

    suspend fun await() = openSignal.await()

    fun open() {
        if (!openSignal.isCompleted) openSignal.complete(Unit)
    }
}

/// Test double for [TimelineService], ported from matron-apple's
/// `FakeTimelineService`. Reused by the view-model stage.
class FakeTimelineService : TimelineService {
    data class SentMedia(val filename: String, val mime: String, val sizeBytes: Int, val caption: String?)

    var snapshotsToEmit: List<List<TimelineItem>> = emptyList()
    var streamError: Throwable? = null
    /// Artificial latency on a send, so a concurrent second call can observe the
    /// first still in flight (used by AskUserSheet's double-submit guard tests).
    var sendDelayNanos: Long = 0
    /// One-shot error thrown by the next send (text or button or media);
    /// consumed so a subsequent retry succeeds.
    var nextSendError: Throwable? = null
    /// When set, holds a send suspended mid-flight until the test opens the
    /// gate — the deterministic "what does the composer look like DURING the
    /// round-trip" window (ComposerViewModel's optimistic-clear/restore races).
    var sendGate: SendGate? = null
    /// When set, media sends succeed this many times and every one after throws
    /// — pins a partial batch (first photo lands, second doesn't).
    var failSendsAfter: Int? = null
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
        if (sendDelayNanos > 0) kotlinx.coroutines.delay(sendDelayNanos / 1_000_000)
        sendGate?.let { it.markStarted(); it.await() }
        nextSendError?.let { nextSendError = null; throw it }
        sentText.add(body)
        sentInReplyTo.add(inReplyTo)
    }

    override suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String) {
        if (sendDelayNanos > 0) kotlinx.coroutines.delay(sendDelayNanos / 1_000_000)
        sendGate?.let { it.markStarted(); it.await() }
        nextSendError?.let { nextSendError = null; throw it }
        sentButtonResponses.add(selectedValues to inReplyTo)
    }

    override suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?) {
        sendGate?.let { it.markStarted(); it.await() }
        nextSendError?.let { nextSendError = null; throw it }
        failIfPastMediaLimit()
        sentImages.add(SentMedia(filename, mimeType, data.size, caption))
    }

    override suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?) {
        sendGate?.let { it.markStarted(); it.await() }
        nextSendError?.let { nextSendError = null; throw it }
        failIfPastMediaLimit()
        sentFiles.add(SentMedia(filename, mimeType, data.size, caption))
    }

    private fun failIfPastMediaLimit() {
        val limit = failSendsAfter ?: return
        if (sentImages.size + sentFiles.size >= limit) throw RuntimeException("test media send failure")
    }

    override suspend fun paginateBackward(requestSize: Int): Boolean {
        paginateCalls++
        return false
    }

    override suspend fun markAsRead() {
        markReadCalls++
    }

    /// Per-convo session-status stream tests drive via [emitStatus]. Ported from
    /// the Swift fake's `statusContinuation`/`statusPair`.
    val sessionStatusFlow =
        kotlinx.coroutines.flow.MutableSharedFlow<chat.matron.android.models.SessionStatusUpdate>(
            extraBufferCapacity = 64,
        )

    override fun sessionStatus() = sessionStatusFlow

    fun emitStatus(update: chat.matron.android.models.SessionStatusUpdate) {
        sessionStatusFlow.tryEmit(update)
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
