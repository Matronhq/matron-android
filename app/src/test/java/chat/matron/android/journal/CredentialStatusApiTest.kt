package chat.matron.android.journal

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/// What `JournalApi` tells the revocation check about each answer.
class CredentialStatusApiTest {
    private lateinit var server: MockWebServer
    private val told = CopyOnWriteArrayList<Int>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(token: String? = "t") =
        JournalApi(server.url("/").toString(), token = token, authResponses = { told += it })

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun tokenBearingAnswers_areTold() = runBlocking {
        server.enqueue(json(200, """{"settings":{}}"""))
        server.enqueue(json(401, """{"error":"unauthenticated"}"""))
        server.enqueue(json(503, """{"error":"unavailable"}"""))
        val api = api()
        runCatching { api.pins() }
        runCatching { api.pins() }
        runCatching { api.pins() }
        assertEquals(listOf(200, 401, 503), told.toList())
    }

    @Test
    fun aWrongCurrentPassword_isNotTakenAsARevoke() = runBlocking {
        server.enqueue(json(401, """{"error":"bad_password"}"""))
        runCatching { api().pins() }
        assertEquals(listOf(403), told.toList())
    }

    @Test
    fun requestsWithoutAToken_areNotTold() = runBlocking {
        server.enqueue(json(401, """{"error":"unauthenticated"}"""))
        runCatching { api(token = null).pins() }
        assertEquals(emptyList<Int>(), told.toList())
    }

    @Test
    fun anAnswerForAReplacedToken_isNotTold() = runBlocking {
        val api = api()
        server.enqueue(json(401, """{"error":"unauthenticated"}""").setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        val call = launch(Dispatchers.IO) { runCatching { api.pins() } }
        server.takeRequest()
        api.setToken("t2")
        call.join()
        assertEquals(emptyList<Int>(), told.toList())
    }

    @Test
    fun transportFailures_areNotTold() = runBlocking {
        server.shutdown()
        runCatching { api().pins() }
        assertEquals(emptyList<Int>(), told.toList())
    }

    @Test
    fun credentialStatus_mapsOnlyBadPassword() {
        assertEquals(403, JournalApi.credentialStatus(401, """{"error":"bad_password"}""".toByteArray()))
        assertEquals(401, JournalApi.credentialStatus(401, """{"error":"unauthenticated"}""".toByteArray()))
        assertEquals(401, JournalApi.credentialStatus(401, ByteArray(0)))
        assertEquals(500, JournalApi.credentialStatus(500, """{"error":"bad_password"}""".toByteArray()))
    }
}
