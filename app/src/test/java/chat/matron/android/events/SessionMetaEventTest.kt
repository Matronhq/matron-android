package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMetaEventTest {
    private fun content(json: String): JsonObject = parseJsonObjectOrNull(json)!!

    @Test
    fun parsesFull() {
        val evt = SessionMetaEvent.parse(content("""{"session_id":"abc","model":"claude-sonnet-4-7","workdir":"~/my-app","started_at":1745000000000}"""))!!
        assertEquals("abc", evt.sessionID)
        assertEquals("claude-sonnet-4-7", evt.model)
        assertEquals("~/my-app", evt.workdir)
        assertEquals(Instant.ofEpochMilli(1_745_000_000_000), evt.startedAt)
    }

    @Test
    fun parsesIntegerStartedAtFromRealJSON() {
        val evt = SessionMetaEvent.parse(content("""{"session_id":"abc","started_at":1745000000000}"""))!!
        assertEquals(Instant.ofEpochMilli(1_745_000_000_000), evt.startedAt)
    }

    @Test
    fun parsesPartial() {
        val evt = SessionMetaEvent.parse(content("""{"session_id":"abc","started_at":1745000000000}"""))!!
        assertEquals("abc", evt.sessionID)
        assertNull(evt.model)
        assertNull(evt.workdir)
    }

    @Test
    fun returnsNullWhenSessionIDMissing() {
        assertNull(SessionMetaEvent.parse(content("""{"started_at":1745000000000}""")))
    }

    @Test
    fun returnsNullWhenStartedAtMissing() {
        assertNull(SessionMetaEvent.parse(content("""{"session_id":"abc"}""")))
    }

    @Test
    fun returnsNullWhenStartedAtIsNotANumber() {
        assertNull(SessionMetaEvent.parse(content("""{"session_id":"abc","started_at":"1745000000000"}""")))
    }

    @Test
    fun ignoresUnknownFields() {
        val evt = SessionMetaEvent.parse(content("""{"session_id":"abc","started_at":1745000000000,"future_field":"ignored"}"""))!!
        assertEquals("abc", evt.sessionID)
    }
}
