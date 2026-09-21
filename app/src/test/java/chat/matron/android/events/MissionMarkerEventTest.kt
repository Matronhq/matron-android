package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.MilestoneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `MissionMarkerEventTests`.
class MissionMarkerEventTest {
    companion object {
        /// The milestone payload exactly as the journal's conformance fixture
        /// (15_missions_roundtrip.json) shows it on GET /convo/:id/messages.
        const val MILESTONE_PAYLOAD = """{
            "milestone_id":"ml_b2","num":63,"kind":"user_input",
            "title":"Dan asked for missions","body":"the brief",
            "mission_id":"ms_a1","mission_num":61,"mission_title":"Missions & milestones",
            "by":"agent"
        }"""

        const val MISSION_PAYLOAD = """{
            "mission_id":"ms_a1","num":61,"title":"Missions & milestones",
            "action":"created","by":"agent"
        }"""
    }

    private fun obj(text: String) = parseJsonObjectOrNull(text)!!

    @Test
    fun milestoneMarkerParses() {
        val m = MilestoneMarkerEvent.parse(obj(MILESTONE_PAYLOAD))
        assertNotNull(m); m!!
        assertEquals("ml_b2", m.milestoneID); assertEquals(63, m.num)
        assertEquals(MilestoneKind.USER_INPUT, m.kind); assertEquals("Dan asked for missions", m.title)
        assertEquals("the brief", m.body); assertEquals("ms_a1", m.missionID)
        assertEquals(61, m.missionNum); assertEquals("Missions & milestones", m.missionTitle)
        assertEquals(ItemAuthor.AGENT, m.by); assertEquals("Missions & milestones", m.missionLabel)
    }

    /// Protocol, "Markers written across the boundary carry numbers only":
    /// a marker written into a public conversation for a private-origin
    /// mission omits `mission_title`. It must still parse, and it must
    /// render as `#61` — never as an empty string.
    @Test
    fun milestoneMarkerWithoutMissionTitleFallsBackToTheNumber() {
        val m = MilestoneMarkerEvent.parse(obj(MILESTONE_PAYLOAD.replace("\"mission_title\":\"Missions & milestones\",", "")))
        assertNotNull(m); m!!
        assertNull(m.missionTitle)
        assertEquals("#61", m.missionLabel)
        assertEquals("the number always crosses the boundary", 61, m.missionNum)
        assertEquals("the milestone's OWN title is that conversation's content and stays", "Dan asked for missions", m.title)
        val empty = MilestoneMarkerEvent.parse(obj(MILESTONE_PAYLOAD.replace("\"mission_title\":\"Missions & milestones\"", "\"mission_title\":\"\"")))
        assertEquals("an empty title reads as absent", "#61", empty?.missionLabel)
    }

    @Test
    fun milestoneMarkerRejectsMissingIdentityAndUnknownKind() {
        assertNull(MilestoneMarkerEvent.parse(obj(MILESTONE_PAYLOAD.replace("\"kind\":\"user_input\"", "\"kind\":\"vibes\""))))
        assertNull(MilestoneMarkerEvent.parse(obj(MILESTONE_PAYLOAD.replace("\"mission_id\":\"ms_a1\",", ""))))
        assertNull(
            "with no number there is nothing to fall back to",
            MilestoneMarkerEvent.parse(obj(MILESTONE_PAYLOAD.replace("\"mission_num\":61,", ""))),
        )
    }

    @Test
    fun missionMarkerParsesEveryAction() {
        for (action in listOf("created", "joined", "updated", "closed")) {
            val m = MissionMarkerEvent.parse(obj(MISSION_PAYLOAD.replace("\"action\":\"created\"", "\"action\":\"$action\"")))
            assertNotNull(action, m); m!!
            assertEquals(action, m.action.wire)
            assertEquals("ms_a1", m.missionID); assertEquals(61, m.num)
            assertEquals("Missions & milestones", m.missionLabel)
            assertTrue(m.openItemNums.isEmpty())
        }
        assertNull(MissionMarkerEvent.parse(obj(MISSION_PAYLOAD.replace("\"action\":\"created\"", "\"action\":\"vaporised\""))))
    }

    @Test
    fun missionMarkerWithoutTitleFallsBackToTheNumber() {
        val m = MissionMarkerEvent.parse(
            obj(MISSION_PAYLOAD.replace("\"title\":\"Missions & milestones\",", "").replace("\"action\":\"created\"", "\"action\":\"joined\"")),
        )
        assertNotNull(m); m!!
        assertNull(m.title)
        assertEquals("#61", m.missionLabel)
    }

    /// A user-forced close records which item numbers were still open.
    @Test
    fun missionCloseMarkerCarriesOpenItemNumbers() {
        val m = MissionMarkerEvent.parse(
            obj(MISSION_PAYLOAD.replace("\"action\":\"created\",\"by\":\"agent\"", "\"action\":\"closed\",\"by\":\"user\",\"open_item_nums\":[64,70]")),
        )
        assertNotNull(m); m!!
        assertEquals(MissionMarkerEvent.Action.CLOSED, m.action); assertEquals(ItemAuthor.USER, m.by)
        assertEquals(listOf(64, 70), m.openItemNums)
    }

    /// One stream carries both kinds — `MissionsSync` needs the mission id
    /// out of either without switching at every call site.
    @Test
    fun missionMarkerUnionExposesTheMissionID() {
        val milestone = MilestoneMarkerEvent.parse(obj(MILESTONE_PAYLOAD))!!
        val mission = MissionMarkerEvent.parse(obj(MISSION_PAYLOAD))!!
        assertEquals("ms_a1", MissionMarker.Milestone(milestone).missionID)
        assertEquals("ms_a1", MissionMarker.Mission(mission).missionID)
    }
}
