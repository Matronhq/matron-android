package chat.matron.android.chat

import chat.matron.android.features.chat.timelineItemShouldRender
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// Every event type the journal can store — matron-journal's
/// `AGENT_PUBLISH_TYPES` and `CLIENT_SEND_TYPES` (src/ws.js) plus the types
/// only the journal itself appends — must be drawn or deliberately hidden,
/// never the grey "[unsupported event: …]" fallback. `routine`,
/// `coordinator` and `consent_decision` all reached the apps as that
/// fallback. Port of matron-apple's `JournalEventTypeCoverageTests`; when the
/// journal grows a type, add it here.
class JournalEventTypeCoverageTest {
    private val server = "https://j.example".toHttpUrl()

    /// A representative well-formed payload per type, so the test proves the
    /// type's own branch is reached rather than a malformed-payload skip.
    private val payloads = mapOf(
        "text" to """{"body":"hi"}""",
        "prompt" to """{"question":"Go?","options":["Yes","No"]}""",
        "prompt_reply" to """{"target_seq":1,"choice":"Yes"}""",
        "tool_output" to """{"tool":"Bash","output":"ok"}""",
        "diff" to """{"diff":"--- a\n+++ b\n"}""",
        "permission_request" to """{"description":"Run ls?"}""",
        "file" to """{"blob_ref":"b1","name":"a.pdf"}""",
        "image" to """{"blob_ref":"b2"}""",
        "edit" to """{"target_seq":1,"body":"edited"}""",
        "summary" to """{"toc":"Fixed auth","detail":"…"}""",
        "convo_meta" to """{"title":"Chat"}""",
        "read_marker" to """{"up_to_seq":1}""",
        "session_status" to """{"state":"running"}""",
        "spawn_outcome" to """{"request_id":"sp_1","outcome":"started"}""",
        "consent_decision" to """{"kind":"spawn","request_id":"sp_1","decision":"approve","by":"coordinator","convo_id":"coord","reason":"Fits the box rules"}""",
        "coordinator" to """{"role":"assigned"}""",
        "item" to """{"item_id":"it_1","num":3,"kind":"task","title":"Do it","action":"created","by":"agent"}""",
        "memory" to """{"memory_id":"me_1","name":"x","action":"saved","created":true,"by":"agent"}""",
        "milestone" to """{"milestone_id":"ml_1","num":4,"kind":"progress","title":"Done","mission_id":"ms_1","mission_num":2,"by":"agent"}""",
        "mission" to """{"mission_id":"ms_1","num":2,"action":"created","by":"agent"}""",
        "routine" to """{"routine_id":"rt_1","name":"daily-sweep","action":"fired","outcome":"applied now"}""",
    )

    private fun map(type: String, json: String) = JournalTimelineMapper.timelineItem(
        JournalEvent(5, "c1", Instant.ofEpochMilli(1000), "agent:aspen", type, Json.parseToJsonElement(json).jsonObject),
        "user:alice", server,
    )

    @Test
    fun noJournalEventTypeFallsBackToUnsupported() {
        assertEquals(21, payloads.size)
        payloads.forEach { (type, json) ->
            assertFalse("journal event type $type renders as [unsupported event]",
                map(type, json)?.kind is TimelineItem.Kind.Unknown)
        }
    }

    @Test
    fun coordinatorMarkerIsAVisibleRow() {
        val item = map(JournalEventType.COORDINATOR, """{"role":"released"}""")!!
        val kind = item.kind as TimelineItem.Kind.CoordinatorMarker
        assertEquals("5", kind.eventID)
        assertEquals("This chat is no longer the Coordinator", kind.marker.text)
        assertTrue(timelineItemShouldRender(item))
        assertEquals(null, map(JournalEventType.COORDINATOR, """{"role":"promoted"}"""))
    }

    @Test
    fun consentDecisionIsAVisibleRowWithItsReason() {
        val item = map(JournalEventType.CONSENT_DECISION, payloads.getValue("consent_decision"))!!
        val kind = item.kind as TimelineItem.Kind.ConsentDecision
        assertEquals("✅ Coordinator approved the spawn request — Fits the box rules", kind.decision.text)
        assertTrue(timelineItemShouldRender(item))
        val declined = map(JournalEventType.CONSENT_DECISION,
            """{"kind":"chat","room_id":"r1","target_device_id":7,"decision":"decline","reason":"Peer looks\nlooped"}""")!!
        assertEquals("🚫 Coordinator declined the chat request — Peer looks looped",
            (declined.kind as TimelineItem.Kind.ConsentDecision).decision.text)
        val noReason = map(JournalEventType.CONSENT_DECISION, """{"kind":"chat","decision":"approve","reason":"  "}""")!!
        assertEquals("✅ Coordinator approved the chat request",
            (noReason.kind as TimelineItem.Kind.ConsentDecision).decision.text)
    }

    @Test
    fun malformedConsentDecisionIsSkipped() {
        listOf("""{"kind":"spawn","decision":"maybe"}""", """{"kind":"invite","decision":"approve"}""", """{"decision":"approve"}""")
            .forEach { assertEquals(it, null, map(JournalEventType.CONSENT_DECISION, it)) }
    }
}
