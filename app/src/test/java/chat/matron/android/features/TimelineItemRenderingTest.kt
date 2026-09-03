package chat.matron.android.features

import chat.matron.android.chat.TimelineItem
import chat.matron.android.features.chat.attachmentIsExpired
import chat.matron.android.features.chat.attachmentIsLoading
import chat.matron.android.features.chat.timelineAvatarSender
import chat.matron.android.features.chat.timelineDisplayName
import chat.matron.android.features.chat.timelineItemShouldRender
import chat.matron.android.models.TimelineSendState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // MARK: - timelineAvatarSender (ports the `avatarSender` additions, apple #141)

    private fun textItem(id: String, sender: String, isOwn: Boolean) = TimelineItem(
        id = id,
        sender = sender,
        timestamp = Instant.ofEpochMilli(0),
        kind = TimelineItem.Kind.Text("hi", null),
        isOwn = isOwn,
        sendState = TimelineSendState.Sent,
    )

    /// Ports `test_avatarSender_ownMessage_isNil`: own messages never get an
    /// avatar, even in a multi-sender room.
    @Test fun avatarSender_ownMessage_isNull() {
        assertNull(timelineAvatarSender(textItem("1", "dev-2", isOwn = true), hasMultipleSenders = true))
    }

    /// Ports `test_avatarSender_singleSenderRoom_isNil`: a 1:1 chat (single
    /// bot) must not show an avatar even on its non-own messages — this is
    /// the "zero layout change" contract.
    @Test fun avatarSender_singleSenderRoom_isNull() {
        assertNull(timelineAvatarSender(textItem("1", "matron", isOwn = false), hasMultipleSenders = false))
    }

    /// Ports `test_avatarSender_multiSenderRoom_returnsSenderName`: the
    /// multi-agent case — non-own message in a room with ≥2 distinct senders
    /// gets the sender's name back for `MessageBubble`.
    @Test fun avatarSender_multiSenderRoom_returnsSenderName() {
        assertEquals("dev-2", timelineAvatarSender(textItem("1", "dev-2", isOwn = false), hasMultipleSenders = true))
    }

    /// Ports `test_avatarSender_ephemeralStreamingPlaceholder_isNil_evenInMultiSenderRoom`:
    /// render-side twin of the `ChatViewModel.hasMultipleSenders`
    /// ephemeral-row exclusion (Cursor Bugbot, apple #141) — the mid-turn
    /// streaming placeholder row ("eph:"-id, Text kind, hardcoded sender
    /// "agent") must not get an avatar even when the room is genuinely
    /// multi-sender, otherwise the in-flight bubble draws the wrong-coloured
    /// circle and jumps to the real one once the durable row lands.
    @Test fun avatarSender_ephemeralStreamingPlaceholder_isNull_evenInMultiSenderRoom() {
        assertNull(timelineAvatarSender(textItem("eph:1", "agent", isOwn = false), hasMultipleSenders = true))
    }
}
