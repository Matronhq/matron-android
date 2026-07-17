package chat.matron.android.features

import chat.matron.android.chat.TimelineItem
import chat.matron.android.features.chat.timelineDisplayName
import chat.matron.android.features.chat.timelineItemShouldRender
import chat.matron.android.models.TimelineSendState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports MatronTests/TimelineItemViewTests.swift — the pure `displayName(for:)` and
 * `shouldRender(_:)` contract, now the top-level helpers in TimelineItemView.kt.
 */
class TimelineItemRenderingTest {

    private fun item(kind: TimelineItem.Kind, isOwn: Boolean = false, sender: String = "@a:s") = TimelineItem(
        id = "k",
        sender = sender,
        timestamp = Instant.ofEpochMilli(0),
        kind = kind,
        isOwn = isOwn,
        sendState = TimelineSendState.Sent,
    )

    @Test fun displayName_stripsAtSigil_andServerSuffix() {
        assertEquals("bot", timelineDisplayName("@bot:server.com"))
    }

    @Test fun displayName_handlesMissingSigil() {
        assertEquals("bot", timelineDisplayName("bot:server.com"))
    }

    @Test fun displayName_returnsInputWhenNoColon() {
        assertEquals("weird", timelineDisplayName("weird"))
    }

    @Test fun displayName_handlesAtSigilOnly() {
        assertEquals("@", timelineDisplayName("@"))
    }

    @Test fun shouldRender_returnsFalse_forEmptyStateChange() {
        assertFalse(timelineItemShouldRender(item(TimelineItem.Kind.StateChange(""))))
    }

    @Test fun shouldRender_returnsFalse_forPopulatedStateChange() {
        assertFalse(timelineItemShouldRender(item(TimelineItem.Kind.StateChange("alice joined"))))
    }

    @Test fun shouldRender_returnsFalse_forAskUserAnswer() {
        assertFalse(
            timelineItemShouldRender(
                item(TimelineItem.Kind.AskUserAnswer(promptEventID = "\$1", selectedValues = listOf("yes")), isOwn = true),
            ),
        )
    }

    @Test fun shouldRender_returnsTrue_forContentKinds() {
        val kinds = listOf(
            TimelineItem.Kind.Text("hi", null),
            TimelineItem.Kind.Image(null, null, null),
            TimelineItem.Kind.File(null, "x.pdf", null, null),
            TimelineItem.Kind.Unknown("m.audio"),
        )
        for (kind in kinds) assertTrue("content kind $kind must render", timelineItemShouldRender(item(kind)))
    }
}
