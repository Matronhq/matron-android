package chat.matron.android.models

import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/// Ported from matron-apple's `MissionModelTests`: the wire shapes of
/// `Mission`, `Milestone` and `MissionConversation`, and the mission
/// identity an item row carries.
class MissionModelTest {
    companion object {
        /// Exactly the mission row `POST /missions` returns in the journal's
        /// conformance fixture (15_missions_roundtrip.json), plus the counts
        /// `GET /missions` adds. `idem_key` is never returned by the journal.
        const val MISSION_JSON = """{
            "id":"ms_a1","user_id":1,"num":61,"state":"open",
            "title":"Missions & milestones","body":"Ship it",
            "close_summary":null,"closed_by":null,"closed_over_open_items":0,
            "origin_convo_id":"c1","origin_device_id":3,"created_by":"agent",
            "created_at":1700000000000,"updated_at":1700000005000,
            "last_milestone_at":1700000004000,"closed_at":null,
            "open_items":2,"needs_you":1,"conversations":3,"milestones":4,
            "last_milestone":{"num":63,"title":"Wired the migration","kind":"user_input","created_at":1700000004000}
        }"""

        const val MILESTONE_JSON = """{
            "id":"ml_b2","mission_id":"ms_a1","user_id":1,"num":63,"kind":"user_input",
            "title":"Dan asked for missions","body":"the brief","convo_id":"c1","seq":4210,
            "device_id":3,"created_by":"agent","created_at":1700000004000
        }"""
    }

    private fun obj(text: String) = parseJsonObjectOrNull(text)!!

    @Test
    fun missionDecodesIncludingCountsAndLastMilestone() {
        val m = Mission.fromJson(obj(MISSION_JSON))
        assertNotNull(m); m!!
        assertEquals("ms_a1", m.id); assertEquals(61, m.num); assertEquals(MissionState.OPEN, m.state)
        assertEquals("Missions & milestones", m.title); assertEquals("Ship it", m.body)
        assertNull(m.closeSummary); assertNull(m.closedBy); assertEquals(0, m.closedOverOpenItems)
        assertEquals("c1", m.originConvoID); assertEquals(ItemAuthor.AGENT, m.createdBy)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), m.createdAt)
        assertEquals(Instant.ofEpochMilli(1_700_000_004_000), m.lastMilestoneAt)
        assertNull(m.closedAt)
        assertEquals(2, m.openItems); assertEquals(1, m.needsYou)
        assertEquals(3, m.conversationCount); assertEquals(4, m.milestoneCount)
        assertEquals(63, m.lastMilestone?.num)
        assertEquals(MilestoneKind.USER_INPUT, m.lastMilestone?.kind)
        assertEquals("Wired the migration", m.lastMilestone?.title)
        assertEquals("#61 Missions & milestones", m.label)
    }

    /// A mission row with none of the `GET /missions` counts (the shape
    /// `POST /missions/:id/close` and `PATCH` return) must still decode —
    /// the counts default to zero rather than failing the whole row.
    @Test
    fun missionDecodesWithoutCounts() {
        val bare = MISSION_JSON
            .replace(""""closed_at":null,""", """"closed_at":null""")
            .replace(""""open_items":2,"needs_you":1,"conversations":3,"milestones":4,""", "")
            .replace(Regex(""""last_milestone":\{[^}]*\}"""), "")
        val m = Mission.fromJson(obj(bare))
        assertNotNull(m); m!!
        assertEquals(0, m.openItems); assertEquals(0, m.needsYou)
        assertEquals(0, m.conversationCount); assertEquals(0, m.milestoneCount)
        assertNull(m.lastMilestone)
    }

    @Test
    fun missionRejectsUnknownStateAndMissingKeys() {
        assertNull(Mission.fromJson(obj(MISSION_JSON.replace("\"state\":\"open\"", "\"state\":\"paused\""))))
        assertNull(Mission.fromJson(obj(MISSION_JSON.replace("\"num\":61,", ""))))
        assertNull(Mission.fromJson(obj(MISSION_JSON.replace("\"origin_convo_id\":\"c1\",", ""))))
    }

    @Test
    fun closedMissionCarriesSummaryAndOverride() {
        val closed = MISSION_JSON
            .replace("\"state\":\"open\"", "\"state\":\"closed\"")
            .replace("\"close_summary\":null", "\"close_summary\":\"Done.\"")
            .replace("\"closed_by\":null", "\"closed_by\":\"user\"")
            .replace("\"closed_over_open_items\":0", "\"closed_over_open_items\":2")
            .replace("\"closed_at\":null", "\"closed_at\":1700000009000")
        val m = Mission.fromJson(obj(closed))
        assertNotNull(m); m!!
        assertEquals(MissionState.CLOSED, m.state); assertEquals("Done.", m.closeSummary)
        assertEquals(ItemAuthor.USER, m.closedBy); assertEquals(2, m.closedOverOpenItems)
        assertEquals(Instant.ofEpochMilli(1_700_000_009_000), m.closedAt)
    }

    @Test
    fun milestoneDecodesAndKeepsItsAnchorSeq() {
        val ms = Milestone.fromJson(obj(MILESTONE_JSON))
        assertNotNull(ms); ms!!
        assertEquals("ml_b2", ms.id); assertEquals("ms_a1", ms.missionID); assertEquals(63, ms.num)
        assertEquals(MilestoneKind.USER_INPUT, ms.kind); assertEquals("Dan asked for missions", ms.title)
        assertEquals("the brief", ms.body); assertEquals("c1", ms.convoID)
        assertEquals(4210L, ms.seq); assertEquals(3L, ms.deviceID); assertEquals(ItemAuthor.AGENT, ms.createdBy)
        assertEquals(Instant.ofEpochMilli(1_700_000_004_000), ms.createdAt)
    }

    @Test
    fun milestoneRejectsUnknownKindAndMissingSeq() {
        assertNull(Milestone.fromJson(obj(MILESTONE_JSON.replace("\"kind\":\"user_input\"", "\"kind\":\"vibes\""))))
        assertNull(
            "a milestone with no anchor is unusable — reject it",
            Milestone.fromJson(obj(MILESTONE_JSON.replace("\"seq\":4210,", ""))),
        )
    }

    @Test
    fun missionConversationDecodes() {
        val c = MissionConversation.fromJson(obj("""{"id":"c1","title":"Session","box":"dev-2","state":"running"}"""))
        assertNotNull(c); c!!
        assertEquals("c1", c.id); assertEquals("Session", c.title)
        assertEquals("dev-2", c.box); assertEquals("running", c.state)
        val noBox = MissionConversation.fromJson(obj("""{"id":"c2","title":"Other","box":null,"state":"idle"}"""))
        assertNull(noBox?.box)
        assertNull("an id is the one required key", MissionConversation.fromJson(obj("""{"title":"x"}""")))
    }

    /// `items.mission_id` / `mission_num` ride the ordinary item row (the
    /// journal's DECORATE adds them). Both are optional: an item filed in a
    /// conversation with no mission has neither.
    @Test
    fun trackerItemCarriesMissionIdentity() {
        val json = TrackerItemTest.ITEM_JSON.replace("\"has_image\":1", "\"has_image\":1,\"mission_id\":\"ms_a1\",\"mission_num\":61")
        val item = TrackerItem.fromJson(obj(json))
        assertEquals("ms_a1", item?.missionID); assertEquals(61, item?.missionNum)
        val unassigned = TrackerItem.fromJson(obj(TrackerItemTest.ITEM_JSON))
        assertNull(unassigned?.missionID); assertNull(unassigned?.missionNum)
    }
}
