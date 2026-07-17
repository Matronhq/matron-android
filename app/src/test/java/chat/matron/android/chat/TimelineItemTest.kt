package chat.matron.android.chat

import chat.matron.android.events.AskUserEvent
import chat.matron.android.events.ToolCallEvent
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.models.TimelineSendState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineItemTest {
    @Test
    fun textKindEquality() {
        val a = TimelineItem.Kind.Text("hi", null)
        val b = TimelineItem.Kind.Text("hi", null)
        assertEquals(a, b)
    }

    @Test
    fun differentKindsAreInequal() {
        val a: TimelineItem.Kind = TimelineItem.Kind.Text("hi", null)
        val b: TimelineItem.Kind = TimelineItem.Kind.File(null, "x", null, null)
        assertNotEquals(a, b)
    }

    @Test
    fun toolCallKindEquality() {
        val evt = ToolCallEvent("Read", "{}", ToolCallEvent.Status.RUNNING, null, false,
            Instant.ofEpochSecond(0), null)
        assertEquals(
            TimelineItem.Kind.ToolCall("$1", evt),
            TimelineItem.Kind.ToolCall("$1", evt),
        )
    }

    @Test
    fun toolCallKindDifferentEventIDIsInequal() {
        val evt = ToolCallEvent("Read", "{}", ToolCallEvent.Status.RUNNING, null, false,
            Instant.ofEpochSecond(0), null)
        assertNotEquals(
            TimelineItem.Kind.ToolCall("$1", evt),
            TimelineItem.Kind.ToolCall("$2", evt),
        )
    }

    @Test
    fun askUserKindEquality() {
        val evt = AskUserEvent("Continue?", AskUserEvent.InputKind.Boolean, null)
        assertEquals(
            TimelineItem.Kind.AskUser("\$ask", evt),
            TimelineItem.Kind.AskUser("\$ask", evt),
        )
    }

    @Test
    fun toolCallKindInequalToAskUserKind() {
        val tc = ToolCallEvent("Read", "{}", ToolCallEvent.Status.OK, "x", false,
            Instant.ofEpochSecond(0), null)
        val au = AskUserEvent("?", AskUserEvent.InputKind.Text, null)
        val a: TimelineItem.Kind = TimelineItem.Kind.ToolCall("$1", tc)
        val b: TimelineItem.Kind = TimelineItem.Kind.AskUser("$1", au)
        assertNotEquals(a, b)
    }

    @Test
    fun idIsStable() {
        val item = TimelineItem("evt:1", "@a:s", Instant.ofEpochSecond(0),
            TimelineItem.Kind.Text("hi", null), isOwn = true)
        assertEquals("evt:1", item.id)
    }

    @Test
    fun sendStateDefaultsToSent() {
        val item = TimelineItem("evt:1", "@a:s", Instant.ofEpochSecond(0),
            TimelineItem.Kind.Text("hi", null), isOwn = true)
        assertEquals(TimelineSendState.Sent, item.sendState)
    }

    @Test
    fun sendStateFailedCarriesReason() {
        assertEquals(TimelineSendState.Failed("network"), TimelineSendState.Failed("network"))
        assertNotEquals(TimelineSendState.Failed("network"), TimelineSendState.Failed("auth"))
    }

    // MARK: prettyJSON()

    @Test
    fun prettyJSONTextIncludesAllFields() {
        val item = TimelineItem("\$evt:server", "@bot:server", Instant.ofEpochSecond(1_700_000_000),
            TimelineItem.Kind.Text("hello", null), isOwn = false)
        val json = item.prettyJSON()
        assertTrue(json.contains("\"id\""))
        assertTrue(json.contains("\"sender\""))
        assertTrue(json.contains("\"timestamp\""))
        assertTrue(json.contains("\"kind\""))
        assertTrue(json.contains("\"isOwn\""))
        assertTrue(json.contains("\"sendState\""))
        assertTrue(json.contains("\$evt:server"))
        assertTrue(json.contains("@bot:server"))
        assertTrue(json.contains("hello"))
    }

    @Test
    fun prettyJSONIsValidJSONAndRoundTrips() {
        val item = TimelineItem("$1", "@a:s", Instant.ofEpochSecond(0),
            TimelineItem.Kind.Text("with \"quotes\" and a\nnewline", null),
            isOwn = true, sendState = TimelineSendState.Sending)
        val parsed = parseJsonObjectOrNull(item.prettyJSON())
        assertNotNull(parsed)
        assertEquals("$1", parsed!!.stringOrNull("id"))
        assertEquals("@a:s", parsed.stringOrNull("sender"))
        assertEquals(true, parsed.boolOrNull("isOwn"))
    }

    @Test
    fun prettyJSONImageKindIncludesPayload() {
        val item = TimelineItem("$2", "@a:s", Instant.ofEpochSecond(0),
            TimelineItem.Kind.Image("mxc://server/abc", "cat", 12345), isOwn = false)
        val json = item.prettyJSON()
        assertTrue(json.contains("image"))
        assertTrue(json.contains("mxc://server/abc"))
        assertTrue(json.contains("cat"))
        assertTrue(json.contains("12345"))
    }

    @Test
    fun prettyJSONFileKindIncludesFilename() {
        val item = TimelineItem("$3", "@a:s", Instant.ofEpochSecond(0),
            TimelineItem.Kind.File("mxc://server/def", "report.pdf", null, null), isOwn = false)
        val json = item.prettyJSON()
        assertTrue(json.contains("file"))
        assertTrue(json.contains("report.pdf"))
    }

    @Test
    fun prettyJSONFailedSendStateIncludesReason() {
        val item = TimelineItem("$4", "@me:s", Instant.ofEpochSecond(0),
            TimelineItem.Kind.Text("oops", null),
            isOwn = true, sendState = TimelineSendState.Failed("network"))
        val json = item.prettyJSON()
        assertTrue(json.contains("failed"))
        assertTrue(json.contains("network"))
    }
}
