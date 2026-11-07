package chat.matron.android.events

import chat.matron.android.chat.JournalTimelineMapper
import chat.matron.android.chat.TimelineItem
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// An agent's ask for an invite link: the card's payload, its row in the
/// timeline, and how the card reads once its mirror closes.
class ContactInviteAskTest {
    private fun payload(json: String): JsonObject = parseJsonObjectOrNull(json)!!

    private fun event(seq: Long, type: String, payload: JsonObject) =
        JournalTimelineMapper.timelineItem(
            JournalEvent(seq, "room-1", Instant.ofEpochSecond(seq), "agent:box-a", type, payload),
            "user:alice", "https://example.com".toHttpUrl(),
        )!!

    private val cardJson = """
        {"kind":"contact_invite","direction":"out","ask_id":"ia_1","from_device_id":3,"from_name":"box-a","from_convo_id":"c1"}
    """.trimIndent()

    private fun marker(seq: Long, resolution: String, note: String?, consent: String = "invite") =
        event(seq, "item", buildJsonObject {
            put("item_id", "it_1"); put("num", 1); put("kind", "question"); put("title", "Approve making an invite link")
            put("by", "user"); put("action", "closed"); put("resolution", resolution); put("consent", consent)
            note?.let { putJsonObject("comment") { put("id", "c$seq"); put("body", it) } }
        })

    @Test
    fun parsesTheCard() {
        val ask = ContactInviteAsk.parse(payload(cardJson))!!
        assertEquals("ia_1", ask.askId)
        assertEquals("box-a", ask.fromName)
        assertEquals("c1", ask.fromConvoId)
    }

    @Test
    fun anAskWithoutAnIdIsNotACard() {
        assertNull(ContactInviteAsk.parse(payload("""{"kind":"contact_invite","direction":"out"}""")))
        assertNull(ContactInviteAsk.parse(payload("""{"kind":"contact_request","ask_id":"ia_1"}""")))
    }

    @Test
    fun theTimelineDrawsItsOwnCardNeverAllowDeny() {
        val row = event(10, "permission_request", payload(cardJson))
        val kind = row.kind as TimelineItem.Kind.ContactInviteAskCard
        assertEquals("10", kind.eventID)
        assertEquals("ia_1", kind.ask.askId)
        // A malformed one is still a person kind: an inert line, not buttons.
        val malformed = event(11, "permission_request", payload("""{"kind":"contact_invite"}"""))
        assertTrue(malformed.kind is TimelineItem.Kind.StateChange)
    }

    @Test
    fun theMirrorsCloseSaysHowTheCardEnded() {
        val card = event(10, "permission_request", payload(cardJson))
        assertEquals(
            mapOf("10" to InviteAskState.ApprovedElsewhere),
            ContactInviteAsk.resolutions(listOf(card, marker(12, "decided", "Approved — the link was shown on the device you tapped on."))),
        )
        assertEquals(
            mapOf("10" to InviteAskState.Declined),
            ContactInviteAsk.resolutions(listOf(card, marker(12, "decided", "Declined — no link was made."))),
        )
        assertEquals(
            mapOf("10" to InviteAskState.Expired),
            ContactInviteAsk.resolutions(listOf(card, marker(12, "cancelled", "Expired — no answer within 24 h. No link was made."))),
        )
        assertEquals("still waiting", emptyMap<String, InviteAskState>(), ContactInviteAsk.resolutions(listOf(card)))
        assertEquals(
            "another consent's close is not this card's",
            emptyMap<String, InviteAskState>(),
            ContactInviteAsk.resolutions(listOf(card, marker(12, "decided", "Declined", consent = "contact"))),
        )
    }

    @Test
    fun aLaterAskIsResolvedOnItsOwn() {
        val first = event(10, "permission_request", payload(cardJson))
        val second = event(20, "permission_request", payload(cardJson.replace("ia_1", "ia_2")))
        val out = ContactInviteAsk.resolutions(listOf(first, marker(12, "decided", "Declined — no link was made."), second))
        assertEquals(mapOf("10" to InviteAskState.Declined), out)
    }

    @Test
    fun resolvedLinesUseTheSharedWording() {
        assertEquals("Approved on another device.", InviteAskState.ApprovedElsewhere.resolvedText)
        assertEquals("Declined", InviteAskState.Declined.resolvedText)
        assertEquals("Expired — nobody answered within 24 hours", InviteAskState.Expired.resolvedText)
        assertNull(InviteAskState.Idle.resolvedText)
    }
}
