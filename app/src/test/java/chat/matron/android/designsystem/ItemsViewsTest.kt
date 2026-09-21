package chat.matron.android.designsystem

import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Pins the tracker views' pure UI logic (state pill, status lines, row
/// captions, list states, reorder menu, follow-tail). Apple pins the same
/// states via `ItemsListSnapshotTests` / `ItemDetailSnapshotTests` baselines;
/// this project's conventions replace snapshots with pure-function tests.
class ItemsViewsTest {
    private fun item(
        kind: ItemKind = ItemKind.TASK, state: ItemState = ItemState.OPEN, awaiting: ItemAwaiting? = ItemAwaiting.AGENT,
        resolution: ItemResolution? = null, missionNum: Int? = null,
    ) = TrackerItem(id = "it_1", num = 12, kind = kind, state = state, awaiting = awaiting, resolution = resolution, title = "Which auth?", originConvoID = "c1", missionNum = missionNum)

    @Test
    fun statusPillHasFourValues() {
        assertEquals("Needs you", itemStatusText(item(awaiting = ItemAwaiting.USER)))
        assertEquals("With the agent", itemStatusText(item(awaiting = ItemAwaiting.AGENT)))
        assertEquals("Open", itemStatusText(item(kind = ItemKind.DECISION, awaiting = null)))
        assertEquals("Closed · Done", itemStatusText(item(state = ItemState.CLOSED, awaiting = null, resolution = ItemResolution.DONE)))
        assertEquals("Closed", itemStatusText(item(state = ItemState.CLOSED, awaiting = null)))
        assertEquals("a closed item is never 'needs you'", "Closed · Answered", itemStatusText(item(state = ItemState.CLOSED, awaiting = ItemAwaiting.USER, resolution = ItemResolution.ANSWERED)))
    }

    @Test
    fun rowCaptionAndAccessibilityLabel() {
        assertEquals("Needs you", itemRowStatusCaption(item(awaiting = ItemAwaiting.USER)))
        assertEquals("With the agent", itemRowStatusCaption(item()))
        assertEquals("Reversed", itemRowStatusCaption(item(state = ItemState.CLOSED, awaiting = null, resolution = ItemResolution.REVERSED)))
        assertNull(itemRowStatusCaption(item(kind = ItemKind.DECISION, awaiting = null)))
        assertEquals("Task 12, Which auth?", itemRowAccessibilityLabel(item()))
        assertEquals("Question 12, Which auth?, needs you, mission #61", itemRowAccessibilityLabel(item(kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, missionNum = 61)))
        assertEquals("#61", itemMissionChipText(item(missionNum = 61)))
        assertNull(itemMissionChipText(item()))
    }

    @Test
    fun glyphLabels() {
        assertEquals(listOf("Task", "Question", "Decision"), listOf(ItemKind.TASK, ItemKind.QUESTION, ItemKind.DECISION).map(ItemGlyph::label))
        assertEquals(listOf("Done", "Answered", "Decided", "Reversed", "Cancelled"), ItemResolution.entries.map(ItemGlyph::label))
    }

    @Test
    fun resolveActionsNameTheAct() {
        assertEquals(listOf("Mark done", "Mark answered", "Mark decided", "Reverse", "Dismiss"), ItemResolution.entries.map(::resolveActionLabel))
        assertFalse("an open item with nothing honest to offer hides the control", resolveControlVisible(isOpen = true, resolutions = emptyList()))
        assertTrue(resolveControlVisible(isOpen = true, resolutions = listOf(ItemResolution.CANCELLED)))
        assertTrue("a closed item always offers Reopen", resolveControlVisible(isOpen = false, resolutions = emptyList()))
    }

    private fun status(from: TrackerItem.StatusSnapshot?, to: TrackerItem.StatusSnapshot?, author: ItemAuthor = ItemAuthor.AGENT) =
        TrackerComment("s", "it_1", author, kind = TrackerComment.Kind.STATUS, body = "", statusFrom = from, statusTo = to)

    @Test
    fun statusLineDerivesFromSnapshots() {
        val open = TrackerItem.StatusSnapshot(ItemState.OPEN, null, ItemAwaiting.USER)
        val closed = TrackerItem.StatusSnapshot(ItemState.CLOSED, ItemResolution.ANSWERED, null)
        assertEquals("Agent closed this as answered", itemStatusLine(status(open, closed)))
        assertEquals("You closed this", itemStatusLine(status(open, TrackerItem.StatusSnapshot(ItemState.CLOSED, null, null), ItemAuthor.USER)))
        assertEquals("You reopened this", itemStatusLine(status(closed, open, ItemAuthor.USER)))
        assertEquals("an awaiting hand-back is not a reopen", "Now with the agent", itemStatusLine(status(open, TrackerItem.StatusSnapshot(ItemState.OPEN, null, ItemAwaiting.AGENT))))
        assertEquals("Needs you", itemStatusLine(status(TrackerItem.StatusSnapshot(ItemState.OPEN, null, ItemAwaiting.AGENT), open)))
        assertNull("nothing changed ⇒ no line", itemStatusLine(status(open, open)))
        assertEquals("Agent updated the item", itemStatusLine(status(null, null)))
    }

    @Test
    fun pendingSendStatePrecedence() {
        assertEquals(SendStateGlyph.Sending, pendingCommentSendState(0, null))
        assertEquals(SendStateGlyph.Queued, pendingCommentSendState(2, null))
        assertEquals("an error wins regardless of attempts", SendStateGlyph.Failed("offline"), pendingCommentSendState(0, "offline"))
        assertEquals("Sending…", pendingItemRowCaption(false)); assertEquals("Failed — will retry", pendingItemRowCaption(true))
        assertEquals("Do X, failed, will retry", pendingItemRowLabel("Do X", true))
    }

    @Test
    fun relativeDateFallsBackToAnAbsoluteDateAfterAWeek() {
        val now = Instant.parse("2026-09-21T12:00:00Z")
        assertEquals("just now", itemRelativeDate(now.minusSeconds(5), now))
        assertEquals("5 min ago", itemRelativeDate(now.minusSeconds(300), now))
        assertEquals("3 hr ago", itemRelativeDate(now.minusSeconds(3 * 3600), now))
        assertEquals("1 day ago", itemRelativeDate(now.minusSeconds(86_400), now))
        assertEquals("2 days ago", itemRelativeDate(now.minusSeconds(2 * 86_400), now))
        assertEquals("3 Sep 2026", itemRelativeDate(now.minusSeconds(18 * 86_400), now, ZoneOffset.UTC))
    }

    @Test
    fun listStatePrecedenceAndOriginCaption() {
        val empty = ItemsListModel(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap(), isSupported = true, isRefreshing = false)
        assertEquals(ItemsListState.EMPTY, itemsListState(empty))
        assertEquals("unsupported wins over empty", ItemsListState.UNSUPPORTED, itemsListState(empty.copy(isSupported = false)))
        assertEquals("a pending create is content", ItemsListState.POPULATED, itemsListState(empty.copy(pending = listOf(PendingItemRowModel("L1", ItemKind.TASK, "T", false, null)))))
        assertEquals(ItemsListState.POPULATED, itemsListState(empty.copy(tasks = listOf(item()))))
        val titles = mapOf("c1" to "dev-y · Fix the parser")
        assertNull("no origin caption in the conversation scope", itemOriginCaption(item(), ItemsScope.Convo("c1"), titles))
        assertEquals("dev-y · Fix the parser", itemOriginCaption(item(), ItemsScope.All, titles))
        assertEquals("Another chat", itemOriginCaption(item(), ItemsScope.All, emptyMap()))
    }

    @Test
    fun taskMoveOptionsRespectTheEnds() {
        assertEquals(emptyList<TaskMoveOption>(), taskMoveOptions(0, 1))
        assertEquals(listOf("Move down" to 1, "Move to bottom" to 2), taskMoveOptions(0, 3).map { it.label to it.toIndex })
        assertEquals(listOf("Move to top" to 0, "Move up" to 0, "Move down" to 2, "Move to bottom" to 2), taskMoveOptions(1, 3).map { it.label to it.toIndex })
        assertEquals(listOf("Move to top" to 0, "Move up" to 1), taskMoveOptions(2, 3).map { it.label to it.toIndex })
        assertEquals(emptyList<TaskMoveOption>(), taskMoveOptions(5, 3))
    }

    @Test
    fun followTailAndJumpToBottomRules() {
        // The opening load of an unread thread (0 → 8 with loaded 8) must not drag the reader to the end.
        assertFalse(itemThreadShouldFollowTail(loadedCount = 8, startsAtBottom = false, placed = true, atBottom = true, oldCount = 0, newCount = 8))
        // A reply landing on a loaded thread does.
        assertTrue(itemThreadShouldFollowTail(loadedCount = 8, startsAtBottom = false, placed = true, atBottom = true, oldCount = 8, newCount = 9))
        // A stale replay shrinking then regrowing below the loaded count is still the load.
        assertFalse(itemThreadShouldFollowTail(loadedCount = 8, startsAtBottom = false, placed = true, atBottom = true, oldCount = 3, newCount = 8))
        // Not at the bottom, not placed, or no growth: never.
        assertFalse(itemThreadShouldFollowTail(8, false, placed = true, atBottom = false, oldCount = 8, newCount = 9))
        assertFalse(itemThreadShouldFollowTail(8, false, placed = false, atBottom = true, oldCount = 8, newCount = 9))
        assertFalse(itemThreadShouldFollowTail(8, false, placed = true, atBottom = true, oldCount = 9, newCount = 8))
        // A reader who asked for the tail follows it through the load.
        assertTrue(itemThreadShouldFollowTail(loadedCount = null, startsAtBottom = true, placed = true, atBottom = true, oldCount = 0, newCount = 8))
        assertFalse("no loaded count yet and not a tail reader ⇒ wait", itemThreadShouldFollowTail(null, false, true, true, 0, 8))

        assertTrue(itemThreadShowsJumpToBottom(placed = true, scrollable = true, atBottom = false))
        assertFalse(itemThreadShowsJumpToBottom(placed = false, scrollable = true, atBottom = false))
        assertFalse("a thread that fits never offers a jump", itemThreadShowsJumpToBottom(placed = true, scrollable = false, atBottom = false))
        assertFalse(itemThreadShowsJumpToBottom(placed = true, scrollable = true, atBottom = true))
    }

    /// apple #200: a drag on the composer row hides the keyboard only when
    /// it is a deliberate pull-down.
    @Test
    fun dragDownDismissesTheKeyboardOnlyForADeliberatePullDown() {
        assertTrue(KeyboardDismiss.shouldDismiss(dx = 0f, dy = 24f))
        assertTrue(KeyboardDismiss.shouldDismiss(dx = 10f, dy = 60f))
        assertFalse("below the drop", KeyboardDismiss.shouldDismiss(dx = 0f, dy = 23f))
        assertFalse("an upward drag never counts", KeyboardDismiss.shouldDismiss(dx = 0f, dy = -60f))
        assertFalse("a sideways slip toward the send button never counts", KeyboardDismiss.shouldDismiss(dx = 80f, dy = 30f))
        assertFalse(KeyboardDismiss.shouldDismiss(dx = -40f, dy = 40f))
    }

    /// apple #198: attached-keyboard Enter sends, Shift+Enter newlines, an
    /// empty draft's Enter falls through.
    @Test
    fun enterSendsOnlyAPlainEnterWithSomethingToSend() {
        assertTrue(itemCommentEnterSends(isEnter = true, shift = false, draft = "ok"))
        assertFalse(itemCommentEnterSends(isEnter = true, shift = true, draft = "ok"))
        assertFalse(itemCommentEnterSends(isEnter = true, shift = false, draft = "  \n"))
        assertFalse(itemCommentEnterSends(isEnter = false, shift = false, draft = "ok"))
    }

    @Test
    fun composerAndBadgeCopy() {
        assertFalse(itemCommentCanSubmit("   \n"))
        assertTrue(itemCommentCanSubmit(" ok "))
        assertEquals("99+", needsYouBadgeText(120)); assertEquals("3", needsYouBadgeText(3))
        assertEquals("1 item needs you", needsYouBadgeLabel(1)); assertEquals("4 items need you", needsYouBadgeLabel(4))
    }
}
