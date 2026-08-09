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
        assertEquals("dev-2 wants to start a chat with Device 7.", request.headline)
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
        assertEquals("Device 4 wants to start a chat with Device 7.", request.headline)
        assertNotNull(request)
    }

    /// Everything the journal sends once it knows both ends: device names for
    /// the headline, and a session id + title each for the From/To rows.
    private val named = """
        {"kind":"agent_chat","request":"invite","room_id":"room-1","from_device_id":4,
         "from_name":"dev-2","target_device_id":7,"to_name":"dev-9",
         "from_convo_id":"68a1c4de-2f10-4a55-9c31-7be0f5a1d900",
         "from_convo_title":"Syncing bridge services",
         "to_convo_id":"69d925ab-58ce-49f9-a1a0-f5137d14487b",
         "to_convo_title":"2:69 text carry and fitting parity"}
    """.trimIndent()

    @Test
    fun namesBothEnds() {
        val request = AgentChatRequest.parse(payload(named))!!
        assertEquals("dev-2 wants to start a chat with dev-9.", request.headline)
        assertEquals("dev-2 — 68 · Syncing bridge services", request.fromLabel)
        assertEquals("dev-9 — 2:69 text carry and fitting parity", request.toLabel)
    }

    /// The bridge seeds session titles as "<box>:<first two of the id> words",
    /// which is exactly what the conversation list shows. Prefixing our own
    /// short id there would print the same two characters twice.
    @Test
    fun sessionLabelDoesNotRepeatAShortIDTheTitleAlreadyCarries() {
        assertEquals(
            "2:69 text carry and fitting parity",
            AgentChatRequest.sessionLabel("69d925ab-58ce", "2:69 text carry and fitting parity"),
        )
        assertEquals(
            "3:83 There’s a chat on your box",
            AgentChatRequest.sessionLabel("830cd6e4-f709", "3:83 There’s a chat on your box"),
        )
        // A bare short id with no box prefix counts as carrying it too.
        assertEquals("ab already prefixed", AgentChatRequest.sessionLabel("abcdef", "ab already prefixed"))
    }

    /// Rooms and sub-chats are titled by hand and carry no prefix — this is the
    /// case the id is sent for.
    @Test
    fun sessionLabelPrefixesATitleThatLacksTheShortID() {
        assertEquals(
            "e8 · dan-mac ↔ dev-2 — routing check",
            AgentChatRequest.sessionLabel("e8e4b719-1809", "dan-mac ↔ dev-2 — routing check"),
        )
        // A near-miss must NOT count as carrying it: "e8x" is a different id.
        assertEquals("e8 · e8x nearly", AgentChatRequest.sessionLabel("e8e4b719", "e8x nearly"))
    }

    @Test
    fun sessionLabelHandlesMissingHalves() {
        assertNull(AgentChatRequest.sessionLabel("", ""))
        assertEquals("titled but unidentified", AgentChatRequest.sessionLabel("", "titled but unidentified"))
        assertEquals("ab", AgentChatRequest.sessionLabel("abcdef", "   "))
    }

    /// A journal that predates these fields must still produce an answerable,
    /// non-anonymous card — the far end falls back to its device id.
    @Test
    fun degradesWithoutTheDisplayFields() {
        val request = AgentChatRequest.parse(payload(invite))!!
        assertEquals("dev-2", request.fromLabel)
        assertEquals("Device 7", request.toLabel)
    }

    /// On a join the target IS the joiner, so `to_name` names the room's owner
    /// instead. Labelling the far end from targetDeviceID would say the
    /// requester is asking themselves.
    @Test
    fun joinNamesTheOwnerNotTheSelfTarget() {
        val request = AgentChatRequest.parse(
            payload(
                """{"kind":"agent_chat","request":"join","room_id":"r","from_device_id":4,
                    "from_name":"dev-2","target_device_id":4,"to_name":"dev-a"}""",
            ),
        )!!
        assertEquals("dev-a", request.targetLabel)
        assertEquals("dev-a", request.toLabel)
    }

    @Test
    fun joinWithoutAnOwnerNameSaysSoRatherThanNamingTheJoiner() {
        val request = AgentChatRequest.parse(
            payload(
                """{"kind":"agent_chat","request":"join","room_id":"r","from_device_id":4,
                    "from_name":"dev-2","target_device_id":4}""",
            ),
        )!!
        // Never "Device 4" — that is the joiner, not who is being asked.
        assertEquals("the room's owner", request.targetLabel)
    }
}
