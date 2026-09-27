package chat.matron.android.features

import chat.matron.android.chat.SubChatSummary
import chat.matron.android.features.chat.handOffSubagentTap
import chat.matron.android.features.chat.showsSubagentsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// Covers the subagents list that moved out of the chat toolbar's menu and
/// into `SessionStatusSheet` (Dan, 2026-09-09; port of apple #189's
/// `SessionStatusSheetSubagentsTests`). The Apple suite pins the handoff
/// closure and the defaults, and mounts the sheet in a window to prove it
/// renders; this repo's convention extracts the sheet's decisions into pure
/// functions and tests those instead (composables are never rendered in unit
/// tests), so the render cases have no counterpart here.
class SessionStatusSheetSubagentsTest {

    private fun child(id: String, running: Boolean) = SubChatSummary(id = id, title = "sweep $id", isRunning = running)

    // MARK: the section gate

    @Test fun sectionIsAbsentWithoutChildren() {
        assertFalse(showsSubagentsSection(emptyList()))
    }

    @Test fun sectionShowsForRunningAndFinishedChildrenAlike() {
        assertTrue(showsSubagentsSection(listOf(child("!c1:server", running = true))))
        assertTrue("a finished child is still a way back into its sub-chat", showsSubagentsSection(listOf(child("!c2:server", running = false))))
    }

    // MARK: the handoff

    @Test fun rowTapDismissesTheSheetThenReportsTheChildID() {
        val log = mutableListOf<String>()
        handOffSubagentTap("!c2:server", onDismiss = { log.add("dismiss") }, onOpenSubagent = { log.add("open:$it") })
        assertEquals(
            "the sheet must close BEFORE the host navigates — a stale sheet flag would otherwise greet the user on the way back",
            listOf("dismiss", "open:!c2:server"),
            log,
        )
    }

    @Test fun rowTapWithoutAHandlerStillDismisses() {
        var dismissed = 0
        handOffSubagentTap("!c1:server", onDismiss = { dismissed += 1 }, onOpenSubagent = null)
        assertEquals("the handler defaults to absent so other call sites are unaffected", 1, dismissed)
    }
}
