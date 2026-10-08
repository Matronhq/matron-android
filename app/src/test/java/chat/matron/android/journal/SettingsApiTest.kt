package chat.matron.android.journal

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// `JournalApi`'s `/settings` route and the settings control frames.
class SettingsApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun api(): JournalApi = JournalApi(server.url("/").toString(), token = "t")

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun getSettingsDecodesNotices() = runBlocking {
        server.enqueue(json(200, """{"notices":false}"""))
        assertEquals(UserSettings(notices = false), api().settings())
        val request = server.takeRequest()
        assertEquals("GET", request.method); assertEquals("/settings", request.requestUrl!!.encodedPath)
        assertEquals("Bearer t", request.getHeader("Authorization"))
    }

    @Test
    fun patchSettingsSendsOnlyNotices() = runBlocking {
        server.enqueue(json(200, """{"notices":true}"""))
        assertEquals(UserSettings(notices = true), api().updateSettings(true))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        val sent = parseJsonObjectOrNull(request.body.readUtf8())!!
        assertEquals(setOf("notices"), sent.keys); assertEquals(true, sent.boolOrNull("notices"))
    }

    @Test
    fun aJournalWithoutTheRoute404s() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try {
            api().settings(); fail("expected NotFound")
        } catch (e: JournalApiError) {
            assertTrue(e is JournalApiError.NotFound)
        }
    }

    @Test
    fun helloOkAndSettingsFramesCarryTheSettings() {
        assertEquals(
            ServerFrame.HelloOK(7, settings = UserSettings(notices = true)),
            ServerFrame.decode("""{"kind":"control","op":"hello_ok","seq":7,"settings":{"notices":true}}"""),
        )
        assertNull(
            "a journal that sends no settings",
            (ServerFrame.decode("""{"kind":"control","op":"hello_ok","seq":7}""") as ServerFrame.HelloOK).settings,
        )
        assertEquals(
            ServerFrame.SettingsFrame(UserSettings(notices = false)),
            ServerFrame.decode("""{"kind":"control","op":"settings","settings":{"notices":false}}"""),
        )
        assertNull("a malformed settings frame is skipped", ServerFrame.decode("""{"kind":"control","op":"settings"}"""))
    }

    @Test
    fun helloOkCarriesPinsAndSettingsTogether() {
        val hello = ServerFrame.decode(
            """{"kind":"control","op":"hello_ok","seq":3,"settings":{"notices":true},""" +
                """"pins":[{"convo_id":"c1","label":"Inbox triage","emoji":"📮","position":0,"device_id":7}]}""",
        ) as ServerFrame.HelloOK
        assertEquals(3L, hello.headSeq)
        assertEquals(UserSettings(notices = true), hello.settings)
        assertEquals(listOf("c1"), hello.pins?.map { it.convoID })
    }
}
