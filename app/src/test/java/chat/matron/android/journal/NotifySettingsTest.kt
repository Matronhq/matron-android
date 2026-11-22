package chat.matron.android.journal

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/// The `/notify` shapes, the `notify` frame and `JournalApi`'s route.
class NotifySettingsTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun api() = JournalApi(server.url("/").toString(), token = "t")
    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val viewJSON = """{"mode":"custom","events":{"prompts":false,"questions":true,"other_done":true,"future":true},""" +
        """"has_coordinator":true,"device_level":"needs_me",""" +
        """"convos":[{"convo_id":"c-alice","level":"none","mute_until":null},{"convo_id":"c-bob","level":null,"mute_until":1900000000000},{"convo_id":""}]}"""

    @Test
    fun decodesTheWholeView() {
        val view = NotifyView.decode(parseJsonObjectOrNull(viewJSON))!!
        assertEquals(NotifySettings.Mode.CUSTOM, view.settings.mode)
        assertEquals(NotifyDeviceLevel.NEEDS_ME, view.deviceLevel)
        assertTrue(view.settings.hasCoordinator)
        assertTrue("prompts are always on", view.settings.isOn(NotifySettings.Event.PROMPTS))
        assertTrue(view.settings.isOn(NotifySettings.Event.OTHER_DONE))
        assertTrue("notices default on when missing", view.settings.isOn(NotifySettings.Event.NOTICES))
        assertFalse(view.settings.isOn(NotifySettings.Event.STOPPED))
        assertEquals(
            listOf(
                NotifySettings.ConvoOverride("c-alice", NotifySettings.ConvoLevel.NONE, null),
                NotifySettings.ConvoOverride("c-bob", null, 1_900_000_000_000),
            ),
            view.settings.convos,
        )
    }

    @Test
    fun anUnknownModeIsNotAView() {
        assertNull(NotifyView.decode(parseJsonObjectOrNull("""{"mode":"loud"}""")))
        assertEquals(
            NotifyDeviceLevel.ALL,
            NotifyView.decode(parseJsonObjectOrNull("""{"mode":"all"}"""))!!.deviceLevel,
        )
    }

    @Test
    fun bodiesSendExplicitNullsOnlyWhereTheyClear() {
        assertEquals("""{"mode":"all"}""", NotifyChange.SetMode(NotifySettings.Mode.ALL).body().toString())
        assertEquals("""{"events":{"rooms":true}}""", NotifyChange.SetEvent(NotifySettings.Event.ROOMS, true).body().toString())
        assertEquals("""{"device_level":"off"}""", NotifyChange.SetDeviceLevel(NotifyDeviceLevel.OFF).body().toString())
        assertEquals("""{"convo":{"convo_id":"c1","level":null}}""", NotifyChange.ConvoLevel("c1", null).body().toString())
        assertEquals("""{"convo":{"convo_id":"c1","mute_until":5}}""", NotifyChange.ConvoMute("c1", 5).body().toString())
        assertEquals(
            """{"convo":{"convo_id":"c1","level":null,"mute_until":null}}""",
            NotifyChange.ClearConvo("c1").body().toString(),
        )
    }

    private val base = NotifyView(
        NotifySettings(NotifySettings.Mode.COORDINATOR, NotifySettings.preset(NotifySettings.Mode.COORDINATOR)!!, false, emptyList()),
        NotifyDeviceLevel.ALL,
    )

    @Test
    fun appliedChangesFollowTheJournalsRules() {
        val all = NotifyChange.SetMode(NotifySettings.Mode.ALL).appliedTo(base)
        assertTrue(all.settings.isOn(NotifySettings.Event.OTHER_DONE))
        val custom = NotifyChange.SetEvent(NotifySettings.Event.ROOMS, true).appliedTo(all)
        assertEquals("a switch moves to custom", NotifySettings.Mode.CUSTOM, custom.settings.mode)
        assertTrue("starting from the preset", custom.settings.isOn(NotifySettings.Event.OTHER_DONE))
        assertTrue(custom.settings.isOn(NotifySettings.Event.ROOMS))
        assertEquals("custom keeps its switches", custom.settings.events,
            NotifyChange.SetMode(NotifySettings.Mode.CUSTOM).appliedTo(custom).settings.events)

        val leveled = NotifyChange.ConvoLevel("c1", NotifySettings.ConvoLevel.NEEDS_ME).appliedTo(base)
        val both = NotifyChange.ConvoMute("c1", 10).appliedTo(NotifyChange.ConvoLevel("c2", NotifySettings.ConvoLevel.ALL).appliedTo(leveled))
        assertEquals("latest change first", listOf("c1", "c2"), both.settings.convos.map { it.convoID })
        assertEquals(NotifySettings.ConvoOverride("c1", NotifySettings.ConvoLevel.NEEDS_ME, 10), both.settings.convos[0])
        val unmuted = NotifyChange.ConvoLevel("c1", null).appliedTo(NotifyChange.ConvoMute("c1", null).appliedTo(both))
        assertEquals("a row with neither is dropped", listOf("c2"), unmuted.settings.convos.map { it.convoID })
        assertEquals(emptyList<String>(), NotifyChange.ClearConvo("c2").appliedTo(unmuted).settings.convos.map { it.convoID })
    }

    @Test
    fun muteDurationsEndWhereTheMenuSays() {
        val zone = ZoneId.of("Europe/Paris")
        val now = LocalDateTime.of(2026, 3, 9, 22, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(now + 3_600_000, NotifyMuteDuration.ONE_HOUR.endMs(now, zone))
        assertEquals(now + 8 * 3_600_000, NotifyMuteDuration.EIGHT_HOURS.endMs(now, zone))
        assertEquals(
            LocalDateTime.of(2026, 3, 10, 8, 0).atZone(zone).toInstant().toEpochMilli(),
            NotifyMuteDuration.UNTIL_TOMORROW_MORNING.endMs(now, zone),
        )
    }

    @Test
    fun overridesKnowWhetherTheyStillCount() {
        val muted = NotifySettings.ConvoOverride("c1", muteUntil = 100)
        assertTrue(muted.isMuted(99)); assertFalse(muted.isMuted(100)); assertFalse(muted.isActive(100))
        assertTrue(NotifySettings.ConvoOverride("c1", level = NotifySettings.ConvoLevel.ALL).isActive(100))
    }

    @Test
    fun getAndPutNotify() = runBlocking {
        server.enqueue(json(200, viewJSON))
        assertEquals(NotifyDeviceLevel.NEEDS_ME, api().notifySettings().deviceLevel)
        val get = server.takeRequest()
        assertEquals("GET", get.method); assertEquals("/notify", get.requestUrl!!.encodedPath)

        server.enqueue(json(200, """{"mode":"all","events":{},"has_coordinator":false,"device_level":"off","convos":[]}"""))
        val answer = api().updateNotify(NotifyChange.SetDeviceLevel(NotifyDeviceLevel.OFF))
        assertEquals(NotifyDeviceLevel.OFF, answer.deviceLevel)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("""{"device_level":"off"}""", put.body.readUtf8())
    }

    @Test
    fun aJournalWithoutTheRoute404s() = runBlocking {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        try { api().notifySettings(); fail("expected NotFound") } catch (e: JournalApiError) {
            assertTrue(e is JournalApiError.NotFound)
        }
    }

    @Test
    fun notifyFramesCarryTheSyncedPart() {
        val frame = ServerFrame.decode("""{"kind":"notify","settings":{"mode":"all","events":{},"has_coordinator":true,"convos":[]}}""")
        assertEquals(NotifySettings.Mode.ALL, (frame as ServerFrame.NotifyFrame).settings.mode)
        assertNull("a malformed frame is skipped", ServerFrame.decode("""{"kind":"notify","settings":{}}"""))
    }
}
