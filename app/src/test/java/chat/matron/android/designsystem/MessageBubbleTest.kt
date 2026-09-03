package chat.matron.android.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ports the logic slice of matron-apple's `MessageBubbleSnapshotTests
/// .test_botBubble_withSender` (apple #141) — the visual baseline is an
/// Apple snapshot test; the load-bearing contract is the gating: a sender
/// only ever renders an avatar for a Bot bubble, and the null/Me path must
/// stay layout-identical to before the parameter existed.
class MessageBubbleTest {

    @Test
    fun botBubbleWithSender_showsAvatar() {
        assertTrue(messageBubbleShowsAvatar(MessageAuthorStyle.Bot, "dev-2"))
    }

    @Test
    fun botBubbleWithoutSender_showsNoAvatar() {
        assertFalse(messageBubbleShowsAvatar(MessageAuthorStyle.Bot, null))
    }

    /// `.me` never carries one — MessageBubble only renders the avatar for
    /// `.bot` even if a caller passed `sender` by mistake (spec, 2026-08-13).
    @Test
    fun meBubble_ignoresSenderEvenWhenPassed() {
        assertFalse(messageBubbleShowsAvatar(MessageAuthorStyle.Me, "dev-2"))
        assertFalse(messageBubbleShowsAvatar(MessageAuthorStyle.Me, null))
    }
}
