package chat.matron.android.models

import chat.matron.android.designsystem.missionNoticeText
import chat.matron.android.events.MissionMarkerEvent
import chat.matron.android.journal.db.ItemCommentEntity
import chat.matron.android.journal.db.ItemEntity
import chat.matron.android.journal.db.MilestoneEntity
import chat.matron.android.journal.db.MissionEntity
import chat.matron.android.journal.parseJsonObjectOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Contributor attribution (protocol.md *Contributors*, "Attribution") and the
/// hand-over markers: every field is absent unless set, so an absent key must
/// decode to null and old rows must read exactly as before.
class ContributorAttributionTest {
    private fun json(s: String): JsonObject = parseJsonObjectOrNull(s)!!

    private val itemBase = """"id":"it_1","num":12,"kind":"task","state":"open","title":"Wire the cart",
        "origin_convo_id":"c1","created_at":1000,"updated_at":2000"""
    private val commentBase = """"id":"cm_1","item_id":"it_1","author":"agent","kind":"comment","body":"Done","created_at":1000"""
    private val milestoneBase = """"id":"ml_1","mission_id":"ms_1","num":3,"kind":"progress","title":"Cart works",
        "convo_id":"c1","seq":42,"created_at":1000"""
    private val missionBase = """"id":"ms_1","num":7,"state":"open","title":"Checkout redesign",
        "origin_convo_id":"c1","created_at":1000,"updated_at":2000"""

    @Test
    fun contributorDecodes() {
        assertEquals(Contributor("bob", Contributor.Via.USER), Contributor.fromJson(json("""{"name":"bob","via":"user"}""")))
        assertEquals(Contributor("bob", Contributor.Via.AGENT), Contributor.fromJson(json("""{"name":"bob","via":"agent"}""")))
        // Unknown via reads as the person; no name is no contributor.
        assertEquals(Contributor.Via.USER, Contributor.fromJson(json("""{"name":"bob","via":"robot"}"""))!!.via)
        assertNull(Contributor.fromJson(json("""{"via":"agent"}""")))
        assertNull(Contributor.fromJson(null))
    }

    @Test
    fun attributionWording() {
        assertEquals("by bob", attributionLine(Contributor("bob", Contributor.Via.USER), null))
        assertEquals("by bob's agent", attributionLine(Contributor("bob", Contributor.Via.AGENT), null))
        // The journal's own words win (a hand-over copy carries only these).
        assertEquals("by alice's agent", attributionLine(null, "alice's agent"))
        assertEquals("by bob", attributionLine(Contributor("bob", Contributor.Via.AGENT), "bob"))
        assertNull(attributionLine(null, null))
        assertNull(attributionLine(null, "  "))
        assertEquals("Status by bob", missionStatusByline("bob"))
        assertNull(missionStatusByline(null))
    }

    @Test
    fun itemDecodesItsContributorAndIsUnchangedWithout() {
        val item = TrackerItem.fromJson(json("""{$itemBase,"contributor":{"name":"bob","via":"agent"}}"""))!!
        assertEquals(Contributor("bob", Contributor.Via.AGENT), item.contributor)
        assertEquals("by bob's agent", item.attributionText)
        val plain = TrackerItem.fromJson(json("{$itemBase}"))!!
        assertNull(plain.contributor)
        assertNull(plain.attributionText)
    }

    @Test
    fun commentDecodesContributorAndAttribution() {
        val c = TrackerComment.fromJson(json("""{$commentBase,"device_id":0,"convo_id":null,
            "contributor":{"name":"bob","via":"user"},"attribution":"bob"}"""))!!
        assertEquals(Contributor("bob", Contributor.Via.USER), c.contributor)
        assertEquals("bob", c.attribution)
        assertEquals("by bob", c.attributionText)

        // A hand-over copy carries `attribution` only.
        val copied = TrackerComment.fromJson(json("""{$commentBase,"attribution":"alice's agent"}"""))!!
        assertNull(copied.contributor)
        assertEquals("by alice's agent", copied.attributionText)

        val plain = TrackerComment.fromJson(json("{$commentBase}"))!!
        assertNull(plain.contributor)
        assertNull(plain.attribution)
        assertNull(plain.attributionText)
    }

    @Test
    fun milestoneDecodesItsContributor() {
        val m = Milestone.fromJson(json("""{$milestoneBase,"contributor":{"name":"bob","via":"user"}}"""))!!
        assertEquals("by bob", m.attributionText)
        assertNull(Milestone.fromJson(json("{$milestoneBase}"))!!.contributor)
    }

    @Test
    fun missionDecodesStatusAndItsContributor() {
        val m = Mission.fromJson(json("""{$missionBase,"status":"Cart done, payment next.","status_contributor":"bob"}"""))!!
        assertEquals("Cart done, payment next.", m.status)
        assertEquals("bob", m.statusContributor)
        val plain = Mission.fromJson(json("""{$missionBase,"status":"On track."}"""))!!
        assertEquals("On track.", plain.status)
        assertNull(plain.statusContributor)
        assertNull(Mission.fromJson(json("{$missionBase}"))!!.status)
    }

    @Test
    fun closedAndLeftMarkersCarryTheHandOver() {
        val closed = MissionMarkerEvent.parse(json("""{"mission_id":"ms_1","num":7,"title":"Checkout redesign",
            "action":"closed","by":"user","handed_over":{"to":"bob"}}"""))!!
        assertEquals("bob", closed.handedOverTo)
        assertEquals("🏁 Mission #7 · Checkout redesign · Handed to bob", missionNoticeText(closed))

        val left = MissionMarkerEvent.parse(json("""{"mission_id":"ms_1","num":7,"action":"left","by":"agent","handed_over":{"to":"bob"}}"""))!!
        assertEquals(MissionMarkerEvent.Action.LEFT, left.action)
        assertEquals("🏁 Left mission #7 · Handed to bob", missionNoticeText(left))

        val plainLeft = MissionMarkerEvent.parse(json("""{"mission_id":"ms_1","num":7,"action":"left","by":"agent"}"""))!!
        assertNull(plainLeft.handedOverTo)
        assertEquals("🏁 Left mission #7", missionNoticeText(plainLeft))

        val plainClosed = MissionMarkerEvent.parse(json("""{"mission_id":"ms_1","num":7,"action":"closed","by":"user"}"""))!!
        assertNull(plainClosed.handedOverTo)
        assertEquals("🏁 Mission #7 closed", missionNoticeText(plainClosed))
    }

    @Test
    fun attributionSurvivesTheLocalCache() {
        val item = TrackerItem.fromJson(json("""{$itemBase,"contributor":{"name":"bob","via":"agent"}}"""))!!
        assertEquals(item.contributor, ItemEntity.from(item).toItem().contributor)
        assertNull(ItemEntity.from(TrackerItem.fromJson(json("{$itemBase}"))!!).contributorJson)

        val comment = TrackerComment.fromJson(json("""{$commentBase,"contributor":{"name":"bob","via":"agent"},"attribution":"bob's agent"}"""))!!
        val cached = ItemCommentEntity.from(comment).toComment()
        assertEquals(comment.contributor, cached.contributor)
        assertEquals("bob's agent", cached.attribution)

        val milestone = Milestone.fromJson(json("""{$milestoneBase,"contributor":{"name":"bob","via":"user"}}"""))!!
        assertEquals(milestone.contributor, MilestoneEntity.from(milestone).toMilestone().contributor)

        val mission = Mission.fromJson(json("""{$missionBase,"status":"Cart done.","status_contributor":"bob"}"""))!!
        val back = MissionEntity.from(mission).toMission()
        assertEquals("Cart done.", back.status)
        assertEquals("bob", back.statusContributor)
    }
}
