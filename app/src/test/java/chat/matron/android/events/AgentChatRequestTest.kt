package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/// Ported from matron-apple's `AgentChatRequestTests`.
class AgentChatRequestTest {
    private fun payload(json: String): JsonObject = parseJsonObjectOrNull(json)!!

    /// The exact payload the journal mints (matron-journal src/ws.js, the
    /// agent_invite park path). Note what it does NOT carry: `description` and
    /// `options`, the two keys the generic permission-request rendering reads —
    /// which is why that rendering produced the literal string "Permission
    /// request" with buttons that answered on the wrong channel.
    private val invite = """
        {"kind":"agent_chat","request":"invite","room_id":"room-1","from_device_id":4,
         "from_name":"dev-2","target_device_id":7,"topic":"ci triage",
         "justification":"need the failing build log"}
    """.trimIndent()

    @Test
    fun parsesInvite() {
        val request = AgentChatRequest.parse(payload(invite))!!
        assertEquals(AgentChatRequest.Ask.INVITE, request.ask)
        assertEquals("room-1", request.roomID)
        assertEquals(4L, request.fromDeviceID)
        assertEquals("dev-2", request.fromName)
        assertEquals(7L, request.targetDeviceID)
        assertEquals("ci triage", request.topic)
        assertEquals("need the failing build log", request.justification)
        assertEquals("dev-2 wants to start a chat with another agent.", request.headline)
    }

    @Test
    fun parsesJoin_whichSelfTargets() {
        val request = AgentChatRequest.parse(
            payload("""{"kind":"agent_chat","request":"join","room_id":"r","from_device_id":4,"from_name":"dev-2","target_device_id":4}"""),
        )!!
        assertEquals(AgentChatRequest.Ask.JOIN, request.ask)
        assertEquals("a join request's target is the joiner itself", 4L, request.targetDeviceID)
        assertEquals("dev-2 wants to join this chat.", request.headline)
    }

    @Test
    fun rejectsAnyOtherPermissionRequest() {
        assertNull(
            "a non-agent-chat permission request must fall through to the generic rendering",
            AgentChatRequest.parse(payload("""{"description":"Allow writing to /etc?","options":["Allow","Deny"]}""")),
        )
    }

    /// Each of these is a field `POST /agent-chat/answer` needs, or would be
    /// answered as. A card missing one cannot be resolved, so it must not draw
    /// buttons that would 400 — it falls back instead.
    @Test
    fun rejectsPayloadsItCouldNotAnswer() {
        assertNull(AgentChatRequest.parse(payload("""{"kind":"agent_chat","request":"invite","from_device_id":4,"target_device_id":7}""")))
        assertNull(AgentChatRequest.parse(payload("""{"kind":"agent_chat","room_id":"r","from_device_id":4,"target_device_id":7}""")))
        assertNull(AgentChatRequest.parse(payload("""{"kind":"agent_chat","request":"invite","room_id":"r","target_device_id":7}""")))
        assertNull(AgentChatRequest.parse(payload("""{"kind":"agent_chat","request":"invite","room_id":"r","from_device_id":4}""")))
        assertNull(AgentChatRequest.parse(payload("""{"kind":"agent_chat","request":"conscript","room_id":"r","from_device_id":4,"target_device_id":7}""")))
        assertNull(AgentChatRequest.parse(payload("""{"kind":"agent_chat","request":"invite","room_id":"","from_device_id":4,"target_device_id":7}""")))
    }

    /// The journal defaults an absent topic/justification to `""` rather than
    /// omitting the key, so "absent" and "empty" arrive identically — both have
    /// to collapse to null or the card draws an empty quote block.
    @Test
    fun emptyTopicAndJustificationBecomeNull() {
        val request = AgentChatRequest.parse(
            payload("""{"kind":"agent_chat","request":"invite","room_id":"r","from_device_id":4,"target_device_id":7,"topic":"","justification":"   "}"""),
        )!!
        assertNull(request.topic)
        assertNull(request.justification)
    }

    @Test
    fun fallsBackToDeviceIDWhenTheRequesterHasNoName() {
        val request = AgentChatRequest.parse(
            payload("""{"kind":"agent_chat","request":"invite","room_id":"r","from_device_id":4,"from_name":"","target_device_id":7}"""),
        )!!
        assertEquals("Device 4", request.requesterLabel)
        assertEquals("Device 4 wants to start a chat with another agent.", request.headline)
        assertNotNull(request)
    }
}
