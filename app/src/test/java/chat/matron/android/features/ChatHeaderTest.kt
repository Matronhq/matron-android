package chat.matron.android.features

import chat.matron.android.features.chat.chatAccessibilityTitle
import chat.matron.android.features.chat.chatContextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Pure header-composition logic for ChatScreen, ported from matron-apple's
/// `ChatViewBindingTests`.
class ChatHeaderTest {

    /// Ports matron-apple's `test_contextLine_composesBoxAndAbbreviatedWorkdir`.
    /// The "box · ~/workdir" line under the nav title: either part can be
    /// missing — single-box users get no box name (the chip gate resolves
    /// upstream), and the workdir only arrives with the first session-status
    /// frame — so the line shows what's known and hides entirely when
    /// nothing is.
    @Test
    fun contextLineComposesBoxAndAbbreviatedWorkdir() {
        assertEquals(
            "mac-mini · ~/Dev/matron-apple",
            chatContextLine("mac-mini", "/Users/dan/Dev/matron-apple"),
        )
        assertEquals("mac-mini", chatContextLine("mac-mini", null))
        assertEquals("~/apps/web", chatContextLine(null, "/home/dan/apps/web"))
        assertNull(chatContextLine(null, null))
    }

    /// Ports matron-apple's `test_accessibilityTitle_spellsOutTheVisibleTag`:
    /// the visible header leads with the styled `A:bc` / `A↔B:bc` tag, so
    /// TalkBack's label must spell the same information out — box name(s) and
    /// session short ahead of the clean title, with the room marker dropped
    /// exactly where the visible composition drops it.
    @Test
    fun accessibilityTitleSpellsOutTheVisibleTag() {
        assertEquals(
            "dev-y, session b5, css token migration",
            chatAccessibilityTitle(
                chatTitle = "css token migration",
                boxName = "dev-y", sessionShort = "b5", roomBoxNames = emptyList(),
            ),
        )
        assertEquals(
            "dev-y and dev-z, session ab, mac ↔ dev-z",
            chatAccessibilityTitle(
                chatTitle = "🔗 mac ↔ dev-z",
                boxName = "dev-y", sessionShort = "ab", roomBoxNames = listOf("dev-y", "dev-z"),
            ),
        )
        // A single-box user sees no room tag, so the visible header keeps
        // the room marker — the label must match it (apple #154's
        // marker-discipline fix, ported with SessionTag.accessibilityTitle).
        assertEquals(
            "session ab, ↔️ mac ↔ dev-z",
            chatAccessibilityTitle(
                chatTitle = "↔️ mac ↔ dev-z",
                boxName = null, sessionShort = "ab", roomBoxNames = emptyList(),
            ),
        )
        assertEquals(
            "plain title",
            chatAccessibilityTitle(
                chatTitle = "plain title",
                boxName = null, sessionShort = null, roomBoxNames = emptyList(),
            ),
        )
    }
}
