package chat.matron.android.journal

import chat.matron.android.journal.approvals.SignedApproval
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/// How a lock reaches the app: the socket's `panic` frame, a refused hello,
/// HTTP 423; and `POST /panic/unlock`.
class AccountLockWireTest {
    @Test
    fun panicFrameAndLockedErrorDecode() {
        assertEquals(
            ServerFrame.Panic(on = true, since = 1_790_000_000_000, unlock = true),
            ServerFrame.decode("""{"kind":"panic","on":true,"since":1790000000000,"unlock":true}"""),
        )
        assertEquals(
            ServerFrame.Panic(on = true, since = 5, unlock = false),
            ServerFrame.decode("""{"kind":"panic","on":true,"since":5}"""),
        )
        val error = ServerFrame.decode("""{"kind":"control","op":"error","code":"account_locked","since":5,"unlock":true}""") as ServerFrame.Error
        assertEquals("account_locked", error.code)
        assertEquals(5L, error.since)
        assertTrue(error.unlock)
    }

    @Test
    fun aHelloRefusedForALockSaysSoAndWhetherThisDeviceCanUnlock() = runBlocking {
        val socket = FakeWebSocketConnection()
        // The frozen device is told twice: the lock's frame, then the error.
        socket.serve("""{"kind":"panic","on":true,"since":5,"unlock":true}""")
        socket.serve("""{"kind":"control","op":"error","code":"account_locked","since":5,"unlock":true}""")
        try {
            JournalConnection.establish(FakeConnector(listOf(socket)), "wss://x/ws", "tok", 0)
            fail("expected a refusal")
        } catch (e: JournalConnectionError.AccountLocked) {
            assertEquals(JournalConnectionError.AccountLocked(5, true), e)
        }
    }

    private class Recorder : AccountLockListening {
        val events = mutableListOf<String>()
        override fun noteStatus(status: Int) { events += "status $status" }
        override fun noteLock(lock: AccountLock) { events += "lock ${lock.since} ${lock.canUnlock}" }
        override fun noteLockedBySocket(lock: AccountLock) { events += "socket" }
        override fun noteSocketAccepted() { events += "accepted" }
    }

    @Test
    fun http423IsAccountLockedAndToldToTheMonitor() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val api = JournalApi(server.url("/").toString(), token = "t")
            val recorder = Recorder()
            api.accountLock = recorder
            server.enqueue(MockResponse().setResponseCode(423).setBody("""{"error":"account_locked","since":9,"unlock":true}"""))
            try {
                api.approvalKeys()
                fail("expected a throw")
            } catch (e: JournalApiError.AccountLocked) {
                assertEquals(AccountLock(9, true), e.lock)
            }
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"enabled":false,"keys":[]}"""))
            api.approvalKeys()
            assertEquals(listOf("lock 9 true", "status 200"), recorder.events)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun unlockSendsTheApprovalAndReturnsTheNewToken() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val api = JournalApi(server.url("/").toString(), token = "t")
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"locked":false,"devices_revoked":2,"token":"new"}"""))
            assertEquals(AccountUnlockResult("new", 2), api.unlockAccount(SignedApproval("s", "g")))
            val req = server.takeRequest()
            assertEquals("/panic/unlock", req.path)
            val approval = parseJsonObjectOrNull(req.body.readUtf8())!!.objectOrNull("approval")!!
            assertEquals("s", approval.stringOrNull("statement"))
            assertEquals("g", approval.stringOrNull("signature"))

            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"not_locked"}"""))
            try {
                api.unlockAccount(SignedApproval("s", "g"))
                fail("expected a throw")
            } catch (_: AccountNotLockedError) {
                // expected
            }
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"approval_required"}"""))
            try {
                api.unlockAccount(SignedApproval("s", "g"))
                fail("expected a throw")
            } catch (e: JournalApiError) {
                assertEquals(JournalApiError.ApprovalRequired, e)
            }
        } finally {
            server.shutdown()
        }
    }
}
