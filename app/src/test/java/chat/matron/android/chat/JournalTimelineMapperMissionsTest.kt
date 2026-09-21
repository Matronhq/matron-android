package chat.matron.android.chat

import chat.matron.android.events.MissionMarkerEvent
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The mission marker branches in `JournalTimelineMapper` (port of
/// matron-apple's `JournalTimelineMapperMissionsTests`, #209): a
/// `milestone` becomes a card keyed by its own seq (the anchor), a
/// `mission` a notice, and a malformed payload is skipped rather than
/// rendered as `Unknown`.
class JournalTimelineMapperMissionsTest {
    private val server = "https://j.example".toHttpUrl()

    private fun ev(type: String, payload: JsonObject, seq: Long = 4210) =
        JournalEvent(seq, "c1", Instant.ofEpochMilli(1000), "agent:dev-2", type, payload)

    private fun map(e: JournalEvent) = JournalTimelineMapper.timelineItem(e, "user:dan", server)

    @Test
    fun milestoneEventBecomesACardKeyedByItsOwnSeq() {
        val item = map(
            ev(
                JournalEventType.MILESTONE,
                buildJsonObject {
                    put("milestone_id", "ml_2"); put("num", 63); put("kind", "user_input"); put("title", "Dan asked")
                    put("body", "the brief"); put("mission_id", "ms_1"); put("mission_num", 61)
                    put("mission_title", "Missions & milestones"); put("by", "agent")
                },
            ),
        )!!
        val kind = item.kind as TimelineItem.Kind.MilestoneMarker
        // The event's OWN seq is the anchor, so it is also the row id the
        // transcript scrolls to.
        assertEquals("4210", kind.eventID); assertEquals("4210", item.id)
        assertEquals(63, kind.marker.num)
        assertEquals("Missions & milestones", kind.marker.missionLabel)
        assertEquals("dev-2", item.sender)
    }

    @Test
    fun milestoneEventWithoutMissionTitleStillRenders() {
        val item = map(
            ev(
                JournalEventType.MILESTONE,
                buildJsonObject {
                    put("milestone_id", "ml_2"); put("num", 63); put("kind", "progress"); put("title", "landed")
                    put("mission_id", "ms_1"); put("mission_num", 61); put("by", "agent")
                },
            ),
        )!!
        assertEquals("#61", (item.kind as TimelineItem.Kind.MilestoneMarker).marker.missionLabel)
    }

    @Test
    fun missionEventBecomesANotice() {
        val item = map(
            ev(
                JournalEventType.MISSION,
                buildJsonObject {
                    put("mission_id", "ms_1"); put("num", 61); put("title", "Missions & milestones")
                    put("action", "closed"); put("by", "user")
                    put("open_item_nums", buildJsonArray { add(JsonPrimitive(64)); add(JsonPrimitive(70)) })
                },
            ),
        )!!
        val kind = item.kind as TimelineItem.Kind.MissionMarker
        assertEquals(MissionMarkerEvent.Action.CLOSED, kind.marker.action)
        assertEquals(listOf(64, 70), kind.marker.openItemNums)
        assertTrue(item.id == "4210")
    }

    @Test
    fun malformedMarkersAreSkippedRatherThanRenderedAsUnknown() {
        assertNull(map(ev(JournalEventType.MILESTONE, buildJsonObject { put("milestone_id", "ml_2") })))
        assertNull(
            map(
                ev(
                    JournalEventType.MISSION,
                    buildJsonObject { put("mission_id", "ms_1"); put("num", 61); put("action", "exploded"); put("by", "agent") },
                ),
            ),
        )
    }
}
