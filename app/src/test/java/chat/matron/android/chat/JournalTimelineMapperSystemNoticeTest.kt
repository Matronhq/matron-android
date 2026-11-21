package chat.matron.android.chat

import chat.matron.android.events.SystemNoticeEvent
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
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

/// A bridge notice (a `text` event tagged `payload.notice`) maps to one
/// expandable row; an untagged or unusably tagged text stays a bubble. Port of
/// matron-apple's `JournalTimelineMapperSystemNoticeTests`.
class JournalTimelineMapperSystemNoticeTest {
    private val server = "https://j.example".toHttpUrl()

    private fun ev(payload: JsonObject) =
        JournalEvent(9, "c1", Instant.ofEpochMilli(1000), "agent:box-a", JournalEventType.TEXT, payload)

    private fun map(payload: JsonObject) = JournalTimelineMapper.timelineItem(ev(payload), "user:alice", server)

    private val body = "Consent request from bob's agent.\n\nReply with agent_chat_accept to join."

    @Test
    fun taggedTextIsANotice() {
        val item = map(buildJsonObject {
            put("body", body)
            putJsonObject("notice") { put("kind", "consent"); put("icon", "🔐"); put("summary", "bob's agent asks to chat") }
        })!!
        val kind = item.kind as TimelineItem.Kind.SystemNotice
        assertEquals("9", kind.eventID)
        assertEquals(SystemNoticeEvent("consent", "🔐", "bob's agent asks to chat", body), kind.notice)
    }

    @Test
    fun untaggedTextStaysABubble() {
        val item = map(buildJsonObject { put("body", "Done, the tests pass.") })!!
        assertEquals(TimelineItem.Kind.Text("Done, the tests pass.", null), item.kind)
    }

    @Test
    fun anUnusableTagFallsBackToText() {
        val noSummary = buildJsonObject { put("body", body); putJsonObject("notice") { put("kind", "room"); put("icon", "x") } }
        assertTrue(map(noSummary)!!.kind is TimelineItem.Kind.Text)
        val blank = buildJsonObject { put("body", body); putJsonObject("notice") { put("summary", "  \n ") } }
        assertTrue(map(blank)!!.kind is TimelineItem.Kind.Text)
        val wrongType = buildJsonObject { put("body", body); put("notice", "consent") }
        assertTrue(map(wrongType)!!.kind is TimelineItem.Kind.Text)
        val numericSummary = buildJsonObject { put("body", body); putJsonObject("notice") { put("summary", 3) } }
        assertTrue(map(numericSummary)!!.kind is TimelineItem.Kind.Text)
    }

    @Test
    fun theItemFallbackTwinStaysHiddenEvenWhenTagged() {
        assertNull(map(buildJsonObject {
            put("body", body); put("fallback_for", "item")
            putJsonObject("notice") { put("summary", "x") }
        }))
    }

    @Test
    fun missingIconAndKindFallBack() {
        val notice = SystemNoticeEvent.parse(buildJsonObject { putJsonObject("notice") { put("summary", "Routine fired") } })!!
        assertEquals(SystemNoticeEvent.FALLBACK_ICON, notice.icon)
        assertEquals("notice", notice.kind)
        assertEquals("a notice with no body expands to nothing", "", notice.body)
    }

    @Test
    fun summaryIsOneLineAndCapped() {
        val notice = SystemNoticeEvent.parse(buildJsonObject {
            putJsonObject("notice") { put("summary", "Room\n message\t from   box-b") }
        })!!
        assertEquals("Room message from box-b", notice.summary)
        val long = SystemNoticeEvent.parse(buildJsonObject {
            putJsonObject("notice") { put("summary", "a".repeat(300)); put("icon", "🔐".repeat(10)) }
        })!!
        assertEquals(SystemNoticeEvent.SUMMARY_MAX, long.summary.length)
        assertTrue(long.summary.endsWith("…"))
        assertEquals("an emoji is never split", "🔐".repeat(7) + "…", long.icon)
    }
}
