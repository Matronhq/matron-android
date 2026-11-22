package chat.matron.android.journal

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// An unlock swaps this device's token, and the old one is refused from the
/// moment the journal unlocks: those refusals must never sign the device out.
class RevocationHoldTest {
    private var now = 0L
    private var fired = 0
    private fun monitor() = CredentialRevocationMonitor(rejectionThreshold = 2, minimumSpanMillis = 0, now = { now }) { fired += 1 }

    @Test
    fun aRefusalDuringTheHoldIsDiscardedWhenTheNewTokenIsTaken() {
        val m = monitor()
        m.hold()
        m.handle(CredentialSignal.Rejected)
        m.noteResponse(401); m.noteResponse(401)
        assertEquals(0, fired)
        m.release(discard = true)
        assertEquals(0, fired)
        // The 401s counted during the hold were the old token's.
        m.noteResponse(401)
        assertEquals(0, fired)
    }

    @Test
    fun aRefusalDuringAFailedUnlockFiresWhenTheHoldEnds() {
        val m = monitor()
        m.hold()
        m.handle(CredentialSignal.Rejected)
        assertEquals(0, fired)
        m.release(discard = false)
        assertEquals(1, fired)
    }

    @Test
    fun aLockIsNeverARevoke() {
        val m = monitor()
        repeat(3) { m.handle(CredentialSignal.AccountLocked) }
        assertEquals(0, fired)
        assertFalse(m.hasFired)
    }

    @Test
    fun answersToAReplacedTokenAndLockedAnswersAreNotCounted() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val statuses = mutableListOf<Int>()
            val api = JournalApi(server.url("/").toString(), token = "old", authResponses = { statuses += it })
            // A 423 says the account is locked, nothing about the token.
            server.enqueue(MockResponse().setResponseCode(423).setBody("""{"error":"account_locked","since":1,"unlock":true}"""))
            runCatching { api.approvalKeys() }
            assertTrue(statuses.isEmpty())
            // A 401 that comes back after the token was swapped is the old one's.
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthenticated"}""").setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
            val late = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { runCatching { api.approvalKeys() } }
            Thread.sleep(100)
            api.setToken("new")
            late.await()
            assertTrue(statuses.isEmpty())
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthenticated"}"""))
            runCatching { api.approvalKeys() }
            assertEquals(listOf(401), statuses)
        } finally {
            server.shutdown()
        }
    }
}
