package chat.matron.android.features

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
}
