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
         "from_name":"box-2","target_device_id":7,"topic":"ci triage",
         "justification":"need the failing build log"}
    """.trimIndent()

    @Test
    fun parsesInvite() {
        val request = AgentChatRequest.parse(payload(invite))!!
        assertEquals(AgentChatRequest.Ask.INVITE, request.ask)
        assertEquals("room-1", request.roomID)
        assertEquals(4L, request.fromDeviceID)
        assertEquals("box-2", request.fromName)
        assertEquals(7L, request.targetDeviceID)
        assertEquals("ci triage", request.topic)
        assertEquals("need the failing build log", request.justification)
        assertEquals("box-2 wants to start a chat with Device 7.", request.headline)
    }

    @Test
    fun parsesJoin_whichSelfTargets() {
        val request = AgentChatRequest.parse(
            payload("""{"kind":"agent_chat","request":"join","room_id":"r","from_device_id":4,"from_name":"box-2","target_device_id":4}"""),
        )!!
        assertEquals(AgentChatRequest.Ask.JOIN, request.ask)
        assertEquals("a join request's target is the joiner itself", 4L, request.targetDeviceID)
        assertEquals("box-2 wants to join this chat.", request.headline)
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
         "from_name":"box-2","target_device_id":7,"to_name":"box-9",
         "from_convo_id":"5c7e2a91-3d4b-4e6f-8a10-2b9c4d6e8f01",
         "from_convo_title":"Syncing bridge services",
         "to_convo_id":"4f2e8b17-9a3c-4d5e-b6f7-0a1b2c3d4e5f",
         "to_convo_title":"2:4f Fix the login page"}
    """.trimIndent()

    @Test
    fun namesBothEnds() {
        val request = AgentChatRequest.parse(payload(named))!!
        assertEquals("box-2 wants to start a chat with box-9.", request.headline)
        assertEquals("box-2 — 5c · Syncing bridge services", request.fromLabel)
        assertEquals("box-9 — 2:4f Fix the login page", request.toLabel)
    }

    /// The bridge seeds session titles as "<box>:<first two of the id> words",
    /// which is exactly what the conversation list shows. Prefixing our own
    /// short id there would print the same two characters twice.
    @Test
    fun sessionLabelDoesNotRepeatAShortIDTheTitleAlreadyCarries() {
        assertEquals(
            "2:4f Fix the login page",
            AgentChatRequest.sessionLabel("4f2e8b17-9a3c", "2:4f Fix the login page"),
        )
        assertEquals(
            "3:7b There’s a chat on your box",
            AgentChatRequest.sessionLabel("7b1d93c2-a4e5", "3:7b There’s a chat on your box"),
        )
        // A bare short id with no box prefix counts as carrying it too.
        assertEquals("ab already prefixed", AgentChatRequest.sessionLabel("abcdef", "ab already prefixed"))
    }

    /// Rooms and sub-chats are titled by hand and carry no prefix — this is the
    /// case the id is sent for.
    @Test
    fun sessionLabelPrefixesATitleThatLacksTheShortID() {
        assertEquals(
            "a3 · lab-mac ↔ box-2 — routing check",
            AgentChatRequest.sessionLabel("a3c5e7f9-1b2d", "lab-mac ↔ box-2 — routing check"),
        )
        // A near-miss must NOT count as carrying it: "a3x" is a different id.
        assertEquals("a3 · a3x nearly", AgentChatRequest.sessionLabel("a3c5e7f9", "a3x nearly"))
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
        assertEquals("box-2", request.fromLabel)
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
                    "from_name":"box-2","target_device_id":4,"to_name":"box-a"}""",
            ),
        )!!
        assertEquals("box-a", request.targetLabel)
        assertEquals("box-a", request.toLabel)
    }

    @Test
    fun joinWithoutAnOwnerNameSaysSoRatherThanNamingTheJoiner() {
        val request = AgentChatRequest.parse(
            payload(
                """{"kind":"agent_chat","request":"join","room_id":"r","from_device_id":4,
                    "from_name":"box-2","target_device_id":4}""",
            ),
        )!!
        // Never "Device 4" — that is the joiner, not who is being asked.
        assertEquals("the room's owner", request.targetLabel)
    }
}
