package chat.matron.android.features

import chat.matron.android.chat.TimelineItem
import chat.matron.android.features.chat.attachmentIsExpired
import chat.matron.android.features.chat.attachmentIsLoading
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
        // Expired variants included per apple #140 (the construction-site
        // sweep the #139 merge missed): a tombstoned attachment still renders
        // — as its Expired presentation, not as a hidden row.
        val kinds = listOf(
            TimelineItem.Kind.Text("hi", null),
            TimelineItem.Kind.Image(null, null, null),
            TimelineItem.Kind.File(null, "x.pdf", null, null),
            TimelineItem.Kind.Image(null, null, null, expired = true),
            TimelineItem.Kind.File(null, "x.pdf", null, null, expired = true),
            TimelineItem.Kind.Unknown("m.audio"),
        )
        for (kind in kinds) assertTrue("content kind $kind must render", timelineItemShouldRender(item(kind)))
    }

    // MARK: attachment presentation derivations (port of apple #139's
    // TimelineItemView expired/loading rules)

    @Test fun attachmentIsExpired_tombstoneAloneSuffices() {
        // Fresh syncs carry the payload tombstone; no fetch needed.
        assertTrue(attachmentIsExpired(tombstoned = true, url = null, isMediaUnavailable = null))
    }

    @Test fun attachmentIsExpired_discovered404Suffices() {
        // Already-synced clients never re-fetch the rewritten event — the 404
        // on fetch is how they learn.
        assertTrue(attachmentIsExpired(tombstoned = false, url = "u", isMediaUnavailable = { true }))
    }

    @Test fun attachmentIsExpired_falseForLiveAttachment() {
        assertFalse(attachmentIsExpired(tombstoned = false, url = "u", isMediaUnavailable = { false }))
        // Null resolvers (previews/tests) and URL-less placeholders stay live.
        assertFalse(attachmentIsExpired(tombstoned = false, url = "u", isMediaUnavailable = null))
        assertFalse(attachmentIsExpired(tombstoned = false, url = null, isMediaUnavailable = { true }))
    }

    @Test fun attachmentIsLoading_trueOnlyWhileDownloadingAndNotExpired() {
        assertTrue(attachmentIsLoading(isExpired = false, url = "u", isDownloadingFile = { true }))
        // An expired chip never spins — there is nothing to download.
        assertFalse(attachmentIsLoading(isExpired = true, url = "u", isDownloadingFile = { true }))
        assertFalse(attachmentIsLoading(isExpired = false, url = null, isDownloadingFile = { true }))
        assertFalse(attachmentIsLoading(isExpired = false, url = "u", isDownloadingFile = { false }))
        assertFalse(attachmentIsLoading(isExpired = false, url = "u", isDownloadingFile = null))
    }
}
