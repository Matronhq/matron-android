package chat.matron.android.chat

import chat.matron.android.events.ItemMarkerEvent
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The tracker marker branch in `JournalTimelineMapper` (port of matron-apple's
/// `JournalTimelineMapperItemTests` + the `updated` half of
/// `JournalTimelineMapperTests`, #186): `created`/`closed`/`commented`/
/// `reopened` map to `ItemMarker` so the row renders a card or note;
/// `reordered`, `updated` and malformed payloads stay hidden; the journal's
/// old-client `fallback_for` text twin is hidden so a reply is never shown
/// twice.
class JournalTimelineMapperItemTest {
    private val server = "https://j.example".toHttpUrl()

    private fun ev(type: String, payload: JsonObject, sender: String = "agent:dev-2", seq: Long = 7) =
        JournalEvent(seq, "c1", Instant.ofEpochMilli(1000), sender, type, payload)

    private fun map(e: JournalEvent) = JournalTimelineMapper.timelineItem(e, "user:dan", server)

    private fun marker(action: String, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null) = buildJsonObject {
        put("item_id", "it_1"); put("num", 12); put("kind", "question"); put("title", "Which auth?")
        put("by", "agent"); put("awaiting", "user"); put("action", action)
        extra?.invoke(this)
    }

    @Test
    fun createdMapsToItemMarker() {
        val item = map(ev(JournalEventType.ITEM, marker("created")))!!
        val kind = item.kind as TimelineItem.Kind.ItemMarker
        assertEquals("7", kind.eventID)
        assertEquals("7", item.id)
        assertEquals(ItemMarkerEvent.Action.CREATED, kind.marker.action)
        assertEquals(12, kind.marker.num)
        assertEquals("dev-2", item.sender)
    }

    @Test
    fun closedCommentedAndReopenedRenderToo() {
        for (action in listOf("closed", "commented", "reopened")) {
            val item = map(ev(JournalEventType.ITEM, marker(action)))
            assertTrue("$action renders inline", item?.kind is TimelineItem.Kind.ItemMarker)
        }
    }

    @Test
    fun reorderedUpdatedAndMalformedAreHidden() {
        assertNull("reordered exists only to invalidate the cache", map(ev(JournalEventType.ITEM, marker("reordered"))))
        assertNull("updated carries nothing worth showing inline", map(ev(JournalEventType.ITEM, marker("updated"))))
        assertNull("a malformed marker is skipped, never an unsupported row", map(ev(JournalEventType.ITEM, buildJsonObject { put("action", "created") })))
        assertNull(map(ev(JournalEventType.ITEM, buildJsonObject { })))
    }

    @Test
    fun fallbackTextTwinIsHidden() {
        val fallback = buildJsonObject {
            put("body", "📌 New task #1: T"); put("fallback_for", "item"); put("item_id", "it_1"); put("num", 1); put("action", "created")
        }
        assertNull(map(ev(JournalEventType.TEXT, fallback, sender = "user:dan")))
        assertNotNull("an ordinary text still renders", map(ev(JournalEventType.TEXT, buildJsonObject { put("body", "hi") })))
    }
}
