package chat.matron.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Ported from matron-apple's `BoxDefaultsTests`.
class BoxDefaultsTest {
    private fun obj(json: String) = parseJsonObjectOrNull(json)!!

    @Test
    fun decodeRoster_readsTheShortKeys_nullAndEmptyAreBoxDefault() {
        assertEquals(
            BoxDefaults(agent = "claude", model = "opus", effort = null),
            BoxDefaults.decodeRoster(obj("""{"agent":"claude","model":"opus","effort":""}""")),
        )
        assertEquals(BoxDefaults(), BoxDefaults.decodeRoster(obj("""{"agent":null}""")))
    }

    @Test
    fun decodeState_readsTheWireKeys_andIgnoresTheRest() {
        assertEquals(
            BoxDefaults(agent = "codex", model = null, effort = "high"),
            BoxDefaults.decodeState(obj("""{"device_id":9,"default_agent":"codex","default_model":null,"default_effort":"high"}""")),
        )
    }

    @Test
    fun aNonStringValueRejectsTheWholeBlock() {
        assertNull(BoxDefaults.decodeRoster(obj("""{"agent":"claude","model":5}""")))
        assertNull(BoxDefaults.decodeState(obj("""{"default_effort":{"x":1}}""")))
    }

    @Test
    fun applying_aNewAgentClearsTheModelButKeepsEffort() {
        val claude = BoxDefaults(agent = "claude", model = "opus", effort = "high")
        assertEquals(BoxDefaults(agent = "codex", model = null, effort = "high"), claude.applying(BoxDefaults.Key.AGENT, "codex"))
        // The same agent again is no change.
        assertEquals(claude, claude.applying(BoxDefaults.Key.AGENT, "claude"))
        assertEquals(claude.copy(model = "sonnet"), claude.applying(BoxDefaults.Key.MODEL, "sonnet"))
        assertEquals(claude.copy(effort = null), claude.applying(BoxDefaults.Key.EFFORT, null))
    }
}
