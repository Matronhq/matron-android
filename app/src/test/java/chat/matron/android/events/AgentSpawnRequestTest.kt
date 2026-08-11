package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Ported (in spirit) from AgentChatRequestTest — mirrors its structure for
/// the journal's other client-only consent card.
class AgentSpawnRequestTest {
    private fun payload(json: String): JsonObject = parseJsonObjectOrNull(json)!!

    /// The exact payload the journal mints (matron-journal src/ws.js, the
    /// spawn_request park path).
    private val ask = """
        {"kind":"agent_spawn","request_id":"spawn-1","from_device_id":4,
         "from_name":"dev-2","from_convo_id":"c1","from_convo_title":"CI triage",
         "target_device_id":7,"target_name":"dev-9",
         "workdir":"/home/dev/project","task":"Fix the failing build","topic":"ci triage"}
    """.trimIndent()

    @Test
    fun parsesHappyPath() {
        val request = AgentSpawnRequest.parse(payload(ask))!!
        assertEquals("spawn-1", request.requestId)
        assertEquals(4L, request.fromDeviceId)
        assertEquals("dev-2", request.fromName)
        assertEquals("c1", request.fromConvoId)
        assertEquals("CI triage", request.fromConvoTitle)
        assertEquals(7L, request.targetDeviceId)
        assertEquals("dev-9", request.targetName)
        assertEquals("/home/dev/project", request.workdir)
        assertEquals("Fix the failing build", request.task)
        assertEquals("ci triage", request.topic)
        assertEquals("ci triage", request.headline)
        assertEquals("dev-2", request.requesterLabel)
        assertEquals("dev-9", request.targetLabel)
    }

    @Test
    fun rejectsWrongKind() {
        assertNull(
            "a non-agent-spawn permission request must fall through to the generic rendering",
            AgentSpawnRequest.parse(payload("""{"kind":"agent_chat","request_id":"x","task":"do it"}""")),
        )
        assertNull(AgentSpawnRequest.parse(payload("""{"description":"Allow writing to /etc?","options":["Allow","Deny"]}""")))
    }

    @Test
    fun rejectsMissingOrEmptyRequestId() {
        assertNull(AgentSpawnRequest.parse(payload("""{"kind":"agent_spawn","task":"do it"}""")))
        assertNull(AgentSpawnRequest.parse(payload("""{"kind":"agent_spawn","request_id":"","task":"do it"}""")))
    }

    @Test
    fun rejectsMissingOrEmptyTask() {
        assertNull(AgentSpawnRequest.parse(payload("""{"kind":"agent_spawn","request_id":"spawn-1"}""")))
        assertNull(AgentSpawnRequest.parse(payload("""{"kind":"agent_spawn","request_id":"spawn-1","task":""}""")))
    }

    /// The journal defaults an absent topic/from_convo_title to `""` rather
    /// than omitting the key, so "absent" and "empty" arrive identically —
    /// both have to collapse to null, mirroring AgentChatRequest.
    @Test
    fun emptyTopicAndTitleBecomeNull() {
        val request = AgentSpawnRequest.parse(
            payload(
                """{"kind":"agent_spawn","request_id":"spawn-1","from_device_id":4,"target_device_id":7,
                    "workdir":"/w","task":"do the thing","topic":"","from_convo_title":"   "}""",
            ),
        )!!
        assertNull(request.topic)
        assertNull(request.fromConvoTitle)
    }

    /// With no topic, the headline falls back to the first line of the task —
    /// never the whole (possibly multi-line) seed prompt.
    @Test
    fun headlineFallsBackToTheFirstLineOfTheTaskWhenThereIsNoTopic() {
        val request = AgentSpawnRequest.parse(
            payload(
                """{"kind":"agent_spawn","request_id":"spawn-1","from_device_id":4,"target_device_id":7,
                    "workdir":"/w","task":"Fix the build\nand also the tests"}""",
            ),
        )!!
        assertNull(request.topic)
        assertEquals("Fix the build", request.headline)
    }

    @Test
    fun fallsBackToDeviceIdsWhenNamesAreMissing() {
        val request = AgentSpawnRequest.parse(
            payload(
                """{"kind":"agent_spawn","request_id":"spawn-1","from_device_id":4,"target_device_id":7,
                    "workdir":"/w","task":"do it"}""",
            ),
        )!!
        assertNull(request.fromName)
        assertNull(request.targetName)
        assertEquals("Device 4", request.requesterLabel)
        assertEquals("Device 7", request.targetLabel)
    }

    /// A journal that predates the device-id fields must still produce a
    /// parseable (if anonymous) card rather than crash. Pinned as a decision:
    /// the fallback device id is 0, which reads as "Device 0" — not pretty,
    /// but never nonsensical, and never blocks the card from answering.
    @Test
    fun degradesWithoutDeviceIds() {
        val request = AgentSpawnRequest.parse(
            payload("""{"kind":"agent_spawn","request_id":"spawn-1","task":"do it"}"""),
        )!!
        assertEquals(0L, request.fromDeviceId)
        assertEquals(0L, request.targetDeviceId)
        assertEquals("", request.workdir)
        assertEquals("Device 0", request.requesterLabel)
        assertEquals("Device 0", request.targetLabel)
    }
}
