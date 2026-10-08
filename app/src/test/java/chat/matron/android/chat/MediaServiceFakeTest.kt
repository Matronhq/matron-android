package chat.matron.android.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Test double for [MediaService]. Records each requested URL and returns
/// pre-stubbed bytes. Ported from matron-apple's `FakeMediaService`.
class FakeMediaService : MediaService {
    val stubData = mutableMapOf<String, ByteArray>()
    val requested = mutableListOf<String>()

    override suspend fun image(url: String): ByteArray? {
        synchronized(requested) { requested.add(url) }
        return stubData[url]
    }
}

class MediaServiceFakeTest {
    @Test fun returnsStubbedDataForKnownURL() = runBlocking {
        val svc = FakeMediaService()
        val url = "mxc://example.com/abc"
        svc.stubData[url] = byteArrayOf(0x01, 0x02)
        assertArrayEquals(byteArrayOf(0x01, 0x02), svc.image(url))
        assertEquals(listOf(url), svc.requested)
    }

    @Test fun returnsNilForUnknownURL() = runBlocking {
        val svc = FakeMediaService()
        assertNull(svc.image("mxc://unknown/xyz"))
    }

    @Test fun recordsMultipleRequestsInOrder() = runBlocking {
        val svc = FakeMediaService()
        svc.image("mxc://a/1")
        svc.image("mxc://b/2")
        assertEquals(listOf("mxc://a/1", "mxc://b/2"), svc.requested)
    }

    /// The interface default (port of apple #139's `MediaService` extension):
    /// with no status information a null byte result is a plain retryable
    /// Failure — never NotFound — and bytes pass through as Data.
    @Test fun defaultFetchOutcomeMapsBytesToData_andNullToFailure() = runBlocking {
        val svc = FakeMediaService()
        val url = "mxc://example.com/abc"
        svc.stubData[url] = byteArrayOf(0x01)
        val hit = svc.fetchOutcome(url)
        assertTrue(hit is MediaFetchOutcome.Data)
        assertArrayEquals(byteArrayOf(0x01), (hit as MediaFetchOutcome.Data).bytes)
        assertEquals(MediaFetchOutcome.Failure, svc.fetchOutcome("mxc://unknown/xyz"))
    }
}
