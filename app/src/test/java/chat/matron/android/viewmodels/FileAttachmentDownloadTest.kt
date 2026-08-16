package chat.matron.android.viewmodels

import chat.matron.android.chat.FakeTimelineService
import chat.matron.android.chat.MediaFetchOutcome
import chat.matron.android.chat.MediaService
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Test-only [MediaService] whose fetch suspends until the test releases it —
/// lets the tests observe [ChatViewModel]'s in-flight download state (the chip
/// spinner + tap-dedup contract) deterministically instead of racing a fast
/// fake. Ported from apple #138's `GatedMediaService`.
private class GatedMediaService(private val result: ByteArray?) : MediaService {
    private val waiters = mutableListOf<CompletableDeferred<ByteArray?>>()
    var requestCount = 0
        private set

    override suspend fun image(url: String): ByteArray? {
        val gate = CompletableDeferred<ByteArray?>()
        synchronized(waiters) {
            requestCount++
            waiters.add(gate)
        }
        return gate.await()
    }

    /// Releases every suspended fetch with the stubbed result.
    fun release() {
        val resumed = synchronized(waiters) {
            val copy = waiters.toList()
            waiters.clear()
            copy
        }
        resumed.forEach { it.complete(result) }
    }
}

/// Test-only [MediaService] serving distinct stubbed bytes per URL, without the
/// gating — for tests where only the byte→path mapping matters, not in-flight
/// observation. Ported from apple #138's `DistinctBlobMediaService`.
private class DistinctBlobMediaService(private val blobs: Map<String, ByteArray>) : MediaService {
    override suspend fun image(url: String): ByteArray? = blobs[url]
}

/// Test-only [MediaService] whose fetch outcome is fixed — for pinning how
/// [ChatViewModel] maps a permanent 404 (reaped blob) vs a transient failure
/// into the unavailable-media state. Ported from apple #139's
/// `FixedOutcomeMediaService`.
private class FixedOutcomeMediaService(private val outcome: MediaFetchOutcome) : MediaService {
    var requestCount = 0
        private set

    // Unused after the attachment paths moved to fetchOutcome; kept minimal so
    // the fake still satisfies the interface's designated requirement.
    override suspend fun image(url: String): ByteArray? = null

    override suspend fun fetchOutcome(url: String): MediaFetchOutcome {
        synchronized(this) { requestCount++ }
        return outcome
    }
}

/// Pins the file-attachment download contract on [ChatViewModel]:
/// [ChatViewModel.writeTempFile] must expose an observable "downloading" flag
/// while the (possibly multi-second) blob fetch is in flight, ignore re-taps
/// for a URL that's already downloading, and serve repeat opens from the temp
/// file it already wrote instead of re-downloading. Ported from matron-apple's
/// `FileAttachmentDownloadTests` (apple #138).
class FileAttachmentDownloadTest {

    private val mxc = "https://journal.example/media/abc123"

    private fun vmTest(body: suspend CoroutineScope.(CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val root = java.nio.file.Files.createTempDirectory("file-attachment-download").toFile()
        try {
            tempRoot = root
            body(scope)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
        Unit
    }

    private lateinit var tempRoot: File

    private fun makeVM(scope: CoroutineScope, media: MediaService) =
        ChatViewModel("!r:s", FakeTimelineService(), media, scope, InMemoryKeyValueStore())

    private fun CoroutineScope.download(vm: ChatViewModel): Deferred<File?> =
        async { vm.writeTempFile(mxc, "a.pdf", tempRoot) }

    /// Ports apple #138 `test_writeTempFile_exposesDownloadingState_whileFetchInFlight`.
    @Test
    fun writeTempFile_exposesDownloadingState_whileFetchInFlight() = vmTest { scope ->
        val media = GatedMediaService(result = "pdf-bytes".toByteArray())
        val vm = makeVM(scope, media)
        assertFalse(vm.isDownloadingFile(mxc))

        val download = scope.download(vm)
        waitUntil { vm.isDownloadingFile(mxc) }
        assertTrue("flag must be up while the fetch is suspended", vm.isDownloadingFile(mxc))

        media.release()
        assertNotNull(download.await())
        assertFalse("flag must clear once the fetch completes", vm.isDownloadingFile(mxc))
    }

    /// Ports apple #138 `test_writeTempFile_secondTapWhileDownloading_isIgnored`.
    @Test
    fun writeTempFile_secondTapWhileDownloading_isIgnored() = vmTest { scope ->
        val media = GatedMediaService(result = "pdf-bytes".toByteArray())
        val vm = makeVM(scope, media)

        val first = scope.download(vm)
        waitUntil { vm.isDownloadingFile(mxc) }

        val second = vm.writeTempFile(mxc, "a.pdf", tempRoot)
        assertNull("re-tap during an in-flight download must be a no-op", second)
        assertEquals("re-tap must not start a second fetch", 1, media.requestCount)
        // The dedup no-op is not a failure — it must not raise the banner.
        assertNull(vm.attachmentError.value)

        media.release()
        assertNotNull(first.await())
    }

    /// Ports apple #138 `test_writeTempFile_repeatOpen_servedFromCache_withoutRefetch`.
    @Test
    fun writeTempFile_repeatOpen_servedFromCache_withoutRefetch() = vmTest { scope ->
        val media = GatedMediaService(result = "pdf-bytes".toByteArray())
        val vm = makeVM(scope, media)

        val download = scope.download(vm)
        waitUntil { vm.isDownloadingFile(mxc) }
        media.release()
        val firstFile = download.await()
        assertNotNull(firstFile)

        val secondFile = vm.writeTempFile(mxc, "a.pdf", tempRoot)
        assertEquals("second open must reuse the written temp file", firstFile, secondFile)
        assertEquals("second open must not re-download the blob", 1, media.requestCount)

        // The OS reaps the cache dir under storage pressure — a cache entry
        // whose file is gone must fall through to a fresh download, not
        // return the dead path (CodeRabbit on apple #138).
        assertTrue(firstFile!!.delete())
        val redownload = scope.download(vm)
        waitUntil { media.requestCount == 2 }
        assertEquals("reaped temp file must trigger a re-download", 2, media.requestCount)
        media.release()
        val replacement = redownload.await()
        assertNotNull(replacement)
        assertTrue("pdf-bytes".toByteArray().contentEquals(replacement!!.readBytes()))
    }

    /// Ports apple #138 `test_writeTempFile_sameFilenameDifferentURLs_dontCollide`.
    @Test
    fun writeTempFile_sameFilenameDifferentURLs_dontCollide() = vmTest { scope ->
        // Two attachments can share a display filename ("report.pdf" from two
        // rooms). The temp path must be unique per attachment or the second
        // write clobbers the first and a later cache hit serves the wrong
        // bytes (Bugbot on apple #138).
        val urlA = "https://journal.example/media/blob-a"
        val urlB = "https://journal.example/media/blob-b"
        val media = DistinctBlobMediaService(
            mapOf(
                urlA to "bytes-A".toByteArray(),
                urlB to "bytes-B".toByteArray(),
            ),
        )
        val vm = makeVM(scope, media)

        val pathA = vm.writeTempFile(urlA, "report.pdf", tempRoot)
        val pathB = vm.writeTempFile(urlB, "report.pdf", tempRoot)
        val reopenedA = vm.writeTempFile(urlA, "report.pdf", tempRoot)

        assertNotEquals("same display name must not share a temp path", pathA, pathB)
        assertEquals(pathA, reopenedA)
        assertTrue(
            "re-opening A after downloading B must serve A's bytes",
            "bytes-A".toByteArray().contentEquals(reopenedA!!.readBytes()),
        )
        assertTrue("bytes-B".toByteArray().contentEquals(pathB!!.readBytes()))
    }

    /// Ports apple #139 `test_writeTempFile_notFound_marksFileUnavailable_andStopsRefetching`.
    @Test
    fun writeTempFile_notFound_marksFileUnavailable_andStopsRefetching() = vmTest { scope ->
        // A 404 means the blob was reaped server-side (journal media reaper) —
        // permanent, since blob ids are immutable. The chip must flip to
        // Expired (isMediaUnavailable) and later taps must not re-request.
        val media = FixedOutcomeMediaService(MediaFetchOutcome.NotFound)
        val vm = makeVM(scope, media)
        assertFalse(vm.isMediaUnavailable(mxc))

        assertNull(vm.writeTempFile(mxc, "a.pdf", tempRoot))
        assertTrue("404 must mark the file unavailable", vm.isMediaUnavailable(mxc))
        assertFalse(vm.isDownloadingFile(mxc))
        // No banner either — the chip's Expired state is the feedback.
        assertNull(vm.attachmentError.value)

        assertNull(vm.writeTempFile(mxc, "a.pdf", tempRoot))
        assertEquals("a permanently-gone blob must not be re-fetched", 1, media.requestCount)
    }

    /// Ports apple #139 `test_imageFetch404_marksMediaUnavailable_andStopsRefetching`.
    @Test
    fun imageFetch404_marksMediaUnavailable_andStopsRefetching() = vmTest { scope ->
        // Images have their own resolution path (image(url) → resolved/failed
        // LRUs) — a reaped image must reach the Expired state through it, not
        // just via the file tap path (Bugbot on apple #139).
        val media = FixedOutcomeMediaService(MediaFetchOutcome.NotFound)
        val vm = makeVM(scope, media)

        assertNull(vm.image(mxc))
        waitUntil { vm.isMediaUnavailable(mxc) }
        assertTrue("image 404 must mark the URL unavailable", vm.isMediaUnavailable(mxc))

        assertNull(vm.image(mxc))
        kotlinx.coroutines.delay(50)
        assertEquals("a permanently-gone image must not be re-fetched", 1, media.requestCount)
    }

    /// Ports apple #139 `test_imageFetchTransientFailure_doesNotMarkMediaUnavailable`.
    @Test
    fun imageFetchTransientFailure_doesNotMarkMediaUnavailable() = vmTest { scope ->
        val media = FixedOutcomeMediaService(MediaFetchOutcome.Failure)
        val vm = makeVM(scope, media)
        assertNull(vm.image(mxc))
        waitUntil { media.requestCount == 1 }
        kotlinx.coroutines.delay(50)
        assertFalse("transient image failure must not read as expired", vm.isMediaUnavailable(mxc))
        assertEquals(1, vm.failedRequestCount)
    }

    /// Ports apple #139 `test_writeTempFile_transientFailure_doesNotMarkUnavailable`.
    @Test
    fun writeTempFile_transientFailure_doesNotMarkUnavailable() = vmTest { scope ->
        // Network blips must stay retryable — only a definitive 404 flips the
        // permanent state.
        val media = FixedOutcomeMediaService(MediaFetchOutcome.Failure)
        val vm = makeVM(scope, media)

        assertNull(vm.writeTempFile(mxc, "a.pdf", tempRoot))
        assertFalse("transient failure must not read as expired", vm.isMediaUnavailable(mxc))

        vm.writeTempFile(mxc, "a.pdf", tempRoot)
        assertEquals("retry after transient failure must re-fetch", 2, media.requestCount)
    }

    /// Ports apple #138 `test_writeTempFile_failedFetch_returnsNil_andClearsDownloading`.
    @Test
    fun writeTempFile_failedFetch_returnsNull_andClearsDownloading() = vmTest { scope ->
        val media = GatedMediaService(result = null)
        val vm = makeVM(scope, media)

        val download = scope.download(vm)
        waitUntil { vm.isDownloadingFile(mxc) }
        media.release()

        assertNull(download.await())
        assertFalse("a failed fetch must clear the flag so a retry tap works", vm.isDownloadingFile(mxc))

        // And the retry actually retries (no poisoned in-flight state).
        val retry = scope.download(vm)
        waitUntil { media.requestCount == 2 }
        assertEquals(2, media.requestCount)
        media.release()
        retry.await()
    }
}
