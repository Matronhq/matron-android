package chat.matron.android.chat

import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/// Tracker events in the timeline (port of the `item` half of matron-apple's
/// `JournalTimelineMapperTests` for #185): a marker renders nothing until the
/// inline-cards port, and the journal's old-client `fallback_for` text twin is
/// hidden so a reply is never shown twice.
class JournalTimelineMapperItemTest {
    private val server = "https://j.example".toHttpUrl()

    private fun ev(type: String, payload: JsonObject, sender: String = "user:dan") =
        JournalEvent(1, "c1", Instant.ofEpochMilli(1000), sender, type, payload)

    private fun map(e: JournalEvent) = JournalTimelineMapper.timelineItem(e, "user:dan", server)

    @Test
    fun itemMarkerRendersNothing() {
        val marker = buildJsonObject {
            put("item_id", "it_1"); put("num", 1); put("kind", "task"); put("title", "T")
            put("action", "created"); put("by", "agent")
        }
        assertNull(map(ev(JournalEventType.ITEM, marker)))
        assertNull("a malformed marker is skipped too, never an unsupported row", map(ev(JournalEventType.ITEM, buildJsonObject { })))
    }

    @Test
    fun fallbackTextTwinIsHidden() {
        val fallback = buildJsonObject {
            put("body", "📌 New task #1: T"); put("fallback_for", "item"); put("item_id", "it_1"); put("num", 1); put("action", "created")
        }
        assertNull(map(ev(JournalEventType.TEXT, fallback)))
        assertNotNull("an ordinary text still renders", map(ev(JournalEventType.TEXT, buildJsonObject { put("body", "hi") })))
    }
}
