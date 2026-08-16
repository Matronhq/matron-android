package chat.matron.android.viewmodels

import chat.matron.android.chat.MediaFetchOutcome
import chat.matron.android.chat.MediaService
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.MediaBrowserStoreReading
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Fake store with fixed query results — ported from apple #142's
/// `FakeBrowserStore`.
private class FakeBrowserStore(
    var attachments: List<JournalEvent> = emptyList(),
    var linkCandidates: List<JournalEvent> = emptyList(),
    var throwOnRead: Boolean = false,
) : MediaBrowserStoreReading {
    class Boom : Exception("boom")

    override suspend fun attachmentEvents(convoID: String): List<JournalEvent> {
        if (throwOnRead) throw Boom()
        return attachments
    }

    override suspend fun linkCandidateEvents(convoID: String): List<JournalEvent> {
        if (throwOnRead) throw Boom()
        return linkCandidates
    }
}

/// Media service whose outcome is fixed — mirrors FileAttachmentDownloadTest's
/// `FixedOutcomeMediaService` (private there, so re-declared). Ported from
/// apple #142's `FixedOutcomeMedia`.
private class FixedOutcomeMedia(private val outcome: MediaFetchOutcome) : MediaService {
    var requestCount = 0
        private set

    override suspend fun image(url: String): ByteArray? = null

    override suspend fun fetchOutcome(url: String): MediaFetchOutcome {
        synchronized(this) { requestCount++ }
        return outcome
    }
}

/// Media service whose `fetchOutcome` suspends until [release] — mirrors
/// FileAttachmentDownloadTest's `GatedMediaService`, adapted to gate
/// `fetchOutcome` and resume every waiter with the same fixed outcome, so
/// concurrent `thumbnail` calls can be pinned mid-flight. Ported from apple
/// #142's `GatedOutcomeMedia`.
private class GatedOutcomeMedia(private val outcome: MediaFetchOutcome) : MediaService {
    private val waiters = mutableListOf<CompletableDeferred<MediaFetchOutcome>>()
    var requestCount = 0
        private set

    override suspend fun image(url: String): ByteArray? = null

    override suspend fun fetchOutcome(url: String): MediaFetchOutcome {
        val gate = CompletableDeferred<MediaFetchOutcome>()
        synchronized(waiters) {
            requestCount++
            waiters.add(gate)
        }
        return gate.await()
    }

    /// Releases every suspended fetch with the stubbed outcome.
    fun release() {
        val resumed = synchronized(waiters) {
            val copy = waiters.toList()
            waiters.clear()
            copy
        }
        resumed.forEach { it.complete(outcome) }
    }
}

/// Pins the media browser's load mapping (payload contract, link dedup) and
/// thumbnail fetch state machine (404 → permanent expiry, transient retry,
/// cache, in-flight coalescing). Ported from matron-apple's
/// `MediaBrowserViewModelTests` (apple #142).
class MediaBrowserViewModelTest {

    private val server = "https://journal.example".toHttpUrl()
    private val mediaURL = "https://journal.example/media/b1"

    private fun event(seq: Long, type: String, payload: JsonObject): JournalEvent =
        JournalEvent(seq, "c1", Instant.ofEpochSecond(seq), "agent:dev-2", type, payload)

    private fun vmTest(body: suspend CoroutineScope.(CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            body(scope)
        } finally {
            scope.cancel()
        }
        Unit
    }

    private fun makeVM(
        scope: CoroutineScope,
        store: MediaBrowserStoreReading = FakeBrowserStore(),
        media: MediaService = FixedOutcomeMedia(MediaFetchOutcome.Failure),
    ) = MediaBrowserViewModel(store = store, convoID = "c1", serverURL = server, media = media, scope = scope)

    /// Ports apple #142 `test_load_mapsAttachmentsThroughPayloadContract`.
    @Test
    fun load_mapsAttachmentsThroughPayloadContract() = vmTest { scope ->
        val store = FakeBrowserStore(
            attachments = listOf(
                event(5, "image", buildJsonObject { put("expired", true) }),   // tombstone
                event(
                    3, "file",
                    buildJsonObject {
                        put("blob_ref", "b3"); put("name", "a.pdf")
                        put("size", 1234); put("caption", "the report")
                    },
                ),
                event(1, "image", buildJsonObject { put("blob_ref", "b1") }),
            ),
        )
        val vm = makeVM(scope, store = store)
        vm.load()
        assertEquals(listOf(5L, 1L), vm.mediaItems.value.map { it.id })
        assertTrue(vm.mediaItems.value[0].expired)
        assertNull("tombstone has no blob_ref → no URL", vm.mediaItems.value[0].url)
        assertEquals("https://journal.example/media/b1", vm.mediaItems.value[1].url)
        assertEquals(listOf(3L), vm.fileItems.value.map { it.id })
        assertEquals("a.pdf", vm.fileItems.value[0].name)
        assertEquals(1234L, vm.fileItems.value[0].sizeBytes)
        assertFalse(vm.loadFailed.value)
    }

    /// Ports apple #142 `test_load_extractsDedupesAndOrdersLinks`.
    @Test
    fun load_extractsDedupesAndOrdersLinks() = vmTest { scope ->
        val store = FakeBrowserStore(
            linkCandidates = listOf(   // store returns newest first
                event(9, "text", buildJsonObject { put("body", "again https://a.example/x\nsecond line") }),
                event(7, "text", buildJsonObject { put("body", "https://b.example and https://a.example/x") }),
            ),
        )
        val vm = makeVM(scope, store = store)
        vm.load()
        assertEquals(
            "dedup keeps the NEWEST occurrence; order is newest event first",
            listOf("https://a.example/x", "https://b.example"),
            vm.links.value.map { it.url },
        )
        assertEquals(
            "context is the first line of the containing message",
            "again https://a.example/x",
            vm.links.value[0].context,
        )
        assertEquals(Instant.ofEpochSecond(9), vm.links.value[0].timestamp)
    }

    /// Ports apple #142 `test_load_storeFailure_setsLoadFailed`.
    @Test
    fun load_storeFailure_setsLoadFailed() = vmTest { scope ->
        val vm = makeVM(scope, store = FakeBrowserStore(throwOnRead = true))
        vm.load()
        assertTrue(vm.loadFailed.value)
        assertEquals(emptyList<MediaBrowserViewModel.MediaEntry>(), vm.mediaItems.value)
    }

    /// Ports apple #142 `test_thumbnail_notFound_marksUnavailableAndExpires_withoutRefetch`.
    @Test
    fun thumbnail_notFound_marksUnavailableAndExpires_withoutRefetch() = vmTest { scope ->
        val media = FixedOutcomeMedia(MediaFetchOutcome.NotFound)
        val store = FakeBrowserStore(
            attachments = listOf(event(1, "image", buildJsonObject { put("blob_ref", "b1") })),
        )
        val vm = makeVM(scope, store = store, media = media)
        vm.load()
        assertNull(vm.thumbnail(mediaURL))
        assertTrue(vm.isUnavailable(mediaURL))
        assertTrue("a 404 flips the grid cell to expired", vm.mediaItems.value[0].expired)
        assertNull(vm.thumbnail(mediaURL))
        assertEquals("permanently-gone blob must not be re-fetched", 1, media.requestCount)
    }

    /// Ports apple #142 `test_thumbnail_transientFailure_staysRetryable`.
    @Test
    fun thumbnail_transientFailure_staysRetryable() = vmTest { scope ->
        val media = FixedOutcomeMedia(MediaFetchOutcome.Failure)
        val vm = makeVM(scope, media = media)
        assertNull(vm.thumbnail(mediaURL))
        assertFalse(vm.isUnavailable(mediaURL))
        assertNull(vm.thumbnail(mediaURL))
        assertEquals("transient failure retries", 2, media.requestCount)
    }

    /// Ports apple #142 `test_thumbnail_decodesAndCaches`. The Android VM
    /// caches the fetched bytes (Coil decodes at render), so this pins
    /// bytes-out and single-fetch caching rather than an image decode.
    @Test
    fun thumbnail_cachesBytes_withoutRefetch() = vmTest { scope ->
        val bytes = "png-bytes".toByteArray()
        val media = object : MediaService {
            var requestCount = 0
                private set

            override suspend fun image(url: String): ByteArray? = bytes
            override suspend fun fetchOutcome(url: String): MediaFetchOutcome {
                synchronized(this) { requestCount++ }
                return MediaFetchOutcome.Data(bytes)
            }
        }
        val vm = makeVM(scope, media = media)
        val first = vm.thumbnail(mediaURL)
        assertNotNull(first)
        assertTrue(bytes.contentEquals(first!!))
        vm.thumbnail(mediaURL)
        assertEquals("second ask must hit the cache", 1, media.requestCount)
    }

    /// Bugbot (PR #45): the grid keys its cell loaders on [MediaBrowserViewModel.cacheVersion]
    /// so a transiently-failed cell retries once a later fetch succeeds.
    /// Success bumps it; failures and cache hits must not (a failure bump
    /// would re-key the loaders into a retry loop).
    @Test
    fun cacheVersion_bumpsOnNewBytesOnly() = vmTest { scope ->
        val failing = makeVM(scope, media = FixedOutcomeMedia(MediaFetchOutcome.Failure))
        assertNull(failing.thumbnail(mediaURL))
        assertEquals("a transient failure must not bump the version", 0, failing.cacheVersion.value)

        val reaped = makeVM(scope, media = FixedOutcomeMedia(MediaFetchOutcome.NotFound))
        assertNull(reaped.thumbnail(mediaURL))
        assertEquals("a 404 must not bump the version", 0, reaped.cacheVersion.value)

        val succeeding = makeVM(scope, media = FixedOutcomeMedia(MediaFetchOutcome.Data("png".toByteArray())))
        assertNotNull(succeeding.thumbnail(mediaURL))
        assertEquals("new cache bytes bump the version", 1, succeeding.cacheVersion.value)
        succeeding.thumbnail(mediaURL)
        assertEquals("a cache hit lands no new bytes — no bump", 1, succeeding.cacheVersion.value)
    }

    /// Bugbot (PR #45): a media-cell tap that hit a transient fetch failure
    /// used to clear its spinner and stop — no viewer, no error. [openMedia]
    /// must surface the failure the way `writeTempFile` does for file taps.
    @Test
    fun openMedia_transientFailure_setsAttachmentError() = vmTest { scope ->
        val vm = makeVM(scope, media = FixedOutcomeMedia(MediaFetchOutcome.Failure))
        assertNull(vm.attachmentError.value)
        assertNull(vm.openMedia(mediaURL))
        assertEquals(
            "Couldn't open image — check your connection and try again.",
            vm.attachmentError.value,
        )
        vm.dismissAttachmentError()
        assertNull(vm.attachmentError.value)
    }

    /// A 404 is not a banner case: the cell flips to Expired and that is the
    /// feedback — the same permanent/transient split as `writeTempFile`'s.
    @Test
    fun openMedia_notFound_expiresWithoutError() = vmTest { scope ->
        val vm = makeVM(scope, media = FixedOutcomeMedia(MediaFetchOutcome.NotFound))
        assertNull(vm.openMedia(mediaURL))
        assertTrue(vm.isUnavailable(mediaURL))
        assertNull("a permanent expiry must not raise the transient banner", vm.attachmentError.value)
    }

    @Test
    fun openMedia_success_returnsBytes_withoutError() = vmTest { scope ->
        val media = FixedOutcomeMedia(MediaFetchOutcome.Data("png-bytes".toByteArray()))
        val vm = makeVM(scope, media = media)
        assertNotNull(vm.openMedia(mediaURL))
        assertNull(vm.attachmentError.value)
    }

    /// Ports apple #142 `test_thumbnail_overlappingCalls_coalesceToOneFetch_data`.
    @Test
    fun thumbnail_overlappingCalls_coalesceToOneFetch_data() = vmTest { scope ->
        val media = GatedOutcomeMedia(MediaFetchOutcome.Data("png-bytes".toByteArray()))
        val vm = makeVM(scope, media = media)

        val first = scope.async { vm.thumbnail(mediaURL) }
        val second = scope.async { vm.thumbnail(mediaURL) }
        waitUntil { media.requestCount == 1 }
        assertEquals("second caller must join the in-flight fetch, not start a new one", 1, media.requestCount)

        media.release()
        assertNotNull(first.await())
        assertNotNull(second.await())
        assertEquals("exactly one fetchOutcome call for two overlapping requests", 1, media.requestCount)
    }

    /// Ports apple #142 `test_thumbnail_overlappingCalls_coalesceToOneFetch_notFound` —
    /// pins the side-effect ordering: a joiner awaiting the shared fetch must
    /// not resume until the 404's `markExpired` has already applied, so both
    /// callers see `isUnavailable == true` alongside their `null`.
    @Test
    fun thumbnail_overlappingCalls_coalesceToOneFetch_notFound() = vmTest { scope ->
        val media = GatedOutcomeMedia(MediaFetchOutcome.NotFound)
        val vm = makeVM(scope, media = media)

        val first = scope.async { vm.thumbnail(mediaURL) }
        val second = scope.async { vm.thumbnail(mediaURL) }
        waitUntil { media.requestCount == 1 }
        assertEquals("second caller must join the in-flight fetch, not start a new one", 1, media.requestCount)

        media.release()
        assertNull(first.await())
        assertNull(second.await())
        assertTrue(
            "the side effect of the shared fetch must be visible to both awaiters",
            vm.isUnavailable(mediaURL),
        )
        assertEquals("exactly one fetchOutcome call for two overlapping requests", 1, media.requestCount)
    }
}
