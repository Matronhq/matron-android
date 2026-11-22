package chat.matron.android.push

import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/// Registration with the journal's `/push/fcm/register`, against a fake journal.
class JournalPushServiceTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().also { it.start() } }

    @After fun stop() { server.shutdown() }

    private fun service(token: String? = "fcm-token-0001", key: String? = "uLHqgzncBWWOwTpUixAlfi0qAZ_p0tSU6vRJv7LCvi4", configured: Boolean = true) =
        JournalPushService(JournalApi(server.url("/").toString(), token = "t"), pushKey = { key }, token = { token }, configured = configured)

    private fun answer(status: Int, body: String) = server.enqueue(MockResponse().setResponseCode(status).setBody(body))

    @Test
    fun registersTheTokenAndKey() = runBlocking {
        answer(200, """{"ok":true}""")
        assertEquals(PushRegistration.REGISTERED, service().register())
        val obj = parseJsonObjectOrNull(server.takeRequest().body.readUtf8())!!
        assertEquals("fcm-token-0001", obj.stringOrNull("token"))
        assertEquals("uLHqgzncBWWOwTpUixAlfi0qAZ_p0tSU6vRJv7LCvi4", obj.stringOrNull("push_key"))
    }

    @Test
    fun aJournalWithPushOffIsServerOff() = runBlocking {
        answer(404, """{"error":"fcm_disabled"}""")
        assertEquals(PushRegistration.SERVER_OFF, service().register())
    }

    @Test
    fun aFailureIsReportedNotThrown() = runBlocking {
        answer(500, """{"error":"internal"}""")
        assertEquals(PushRegistration.FAILED, service().register())
    }

    @Test
    fun noTokenOrNoPushBuildSendsNothing() = runBlocking {
        assertEquals(PushRegistration.NO_TOKEN, service(token = null).register())
        assertEquals(PushRegistration.NOT_CONFIGURED, service(configured = false).register())
        assertEquals(PushRegistration.NOT_CONFIGURED, service(configured = false).register("fcm-token-0002"))
        assertEquals(0, server.requestCount)
    }
}
