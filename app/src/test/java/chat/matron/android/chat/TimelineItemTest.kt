package chat.matron.android.chat

import chat.matron.android.events.AskUserEvent
import chat.matron.android.events.ToolCallEvent
import chat.matron.android.models.TimelineSendState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    /// Pins `isEphemeralStreamingPlaceholder` (apple #141) against the
    /// mapper that actually mints the "eph:" ids — the property and
    /// `JournalTimelineMapper.streamingItem` form one contract, and both
    /// `ChatViewModel.hasMultipleSenders` and `timelineAvatarSender` read
    /// the property as their single source of truth.
    @Test
    fun ephemeralStreamingPlaceholder_matchesMapperSyntheticRows() {
        val streaming = JournalTimelineMapper.streamingItem("7", "partial reply…", Instant.ofEpochSecond(0))
        assertTrue(streaming.isEphemeralStreamingPlaceholder)
        // Durable rows — including the other synthetic overlay rows, whose
        // ids are "activity"/"toolstream:<ref>" — are NOT the placeholder.
        val durable = TimelineItem("42", "matron", Instant.ofEpochSecond(0),
            TimelineItem.Kind.Text("hi", null), isOwn = false)
        assertFalse(durable.isEphemeralStreamingPlaceholder)
        assertFalse(
            JournalTimelineMapper.activityItem("Thinking…", Instant.ofEpochSecond(0))
                .isEphemeralStreamingPlaceholder,
        )
        assertFalse(
            JournalTimelineMapper.toolStreamItem("7", "ls", "", false, Instant.ofEpochSecond(0))
                .isEphemeralStreamingPlaceholder,
        )
    }
}
