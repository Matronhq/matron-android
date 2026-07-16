package chat.matron.android.chat

import chat.matron.android.journal.JournalApi
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

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
}
