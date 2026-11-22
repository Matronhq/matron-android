package chat.matron.android.chat

import chat.matron.android.journal.JournalApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/// Ported from matron-apple's `JournalMediaServiceTests`. The Apple suite stubs
/// `URLProtocol` (host-agnostic); here `MockWebServer` backs the [JournalApi],
/// so the media URL is built from the server's own base.
class JournalMediaServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: JournalMediaService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = JournalMediaService(JournalApi(server.url("/")))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test fun imageForKnownBlobReturnsBytes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("PNGDATA"))
        val result = service.image(server.url("/media/b1").toString())
        assertEquals("PNGDATA", result?.let { String(it) })
    }

    @Test fun imageForMissingBlobReturnsNil() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
        assertNull(service.image(server.url("/media/b1").toString()))
    }

    @Test fun imageForURLNotUnderMediaPathReturnsNilWithoutRequest() = runBlocking {
        assertNull(service.image("mxc://matrix.example.com/abc123"))
        assertEquals(0, server.requestCount)
    }

    // MARK: fetchOutcome (port of apple #139's JournalMediaService.fetchOutcome)

    @Test fun fetchOutcomeForKnownBlobReturnsData() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("PNGDATA"))
        val outcome = service.fetchOutcome(server.url("/media/b1").toString())
        assertTrue(outcome is MediaFetchOutcome.Data)
        assertEquals("PNGDATA", String((outcome as MediaFetchOutcome.Data).bytes))
    }

    /// The server's 404 is definitive — the blob was reaped and ids are
    /// immutable — so it must surface as NotFound, not a retryable failure.
    @Test fun fetchOutcomeSurfaces404AsNotFound() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
        assertEquals(MediaFetchOutcome.NotFound, service.fetchOutcome(server.url("/media/b1").toString()))
    }

    @Test fun fetchOutcomeMapsServerErrorToFailure() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertEquals(MediaFetchOutcome.Failure, service.fetchOutcome(server.url("/media/b1").toString()))
    }

    /// A URL outside this journal's media namespace is Failure, not NotFound —
    /// we know nothing definitive about it, and no request is made.
    @Test fun fetchOutcomeForForeignURLIsFailureWithoutRequest() = runBlocking {
        assertEquals(MediaFetchOutcome.Failure, service.fetchOutcome("mxc://matrix.example.com/abc123"))
        assertEquals(0, server.requestCount)
    }

    // MARK: disk cache

    @get:Rule val temp = TemporaryFolder()

    /// A second fetch, through a NEW service (a relaunch), is served from
    /// disk with no request.
    @Test fun aCachedBlobIsServedFromDiskWithoutARequest() = runBlocking {
        val dir = temp.newFolder("media")
        server.enqueue(MockResponse().setResponseCode(200).setBody("PNGDATA"))
        val url = server.url("/media/b1").toString()
        assertEquals("PNGDATA", JournalMediaService(JournalApi(server.url("/")), MediaDiskCache(dir)).image(url)?.let { String(it) })
        assertEquals(1, server.requestCount)
        val relaunched = JournalMediaService(JournalApi(server.url("/")), MediaDiskCache(dir))
        val outcome = relaunched.fetchOutcome(url)
        assertEquals("PNGDATA", String((outcome as MediaFetchOutcome.Data).bytes))
        assertEquals(1, server.requestCount)
    }

    @Test fun aMissingBlobIsNotCached() = runBlocking {
        val cache = MediaDiskCache(temp.newFolder("media"))
        val cached = JournalMediaService(JournalApi(server.url("/")), cache)
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("LATER"))
        val url = server.url("/media/b1").toString()
        assertEquals(MediaFetchOutcome.NotFound, cached.fetchOutcome(url))
        assertNull(cache.data("b1"))
        assertEquals("LATER", cached.image(url)?.let { String(it) })
        assertEquals(2, server.requestCount)
    }
}
