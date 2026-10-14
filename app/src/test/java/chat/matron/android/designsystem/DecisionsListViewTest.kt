package chat.matron.android.designsystem

import chat.matron.android.models.ForYouTab
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.TrackerItem
import androidx.compose.material.icons.outlined.Visibility
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// Pins the Decisions list's pure UI rules. Apple pins the same states via
/// `DecisionsListSnapshotTests` baselines (populated / empty / unsupported
/// + row identity); this project's conventions replace snapshots with
/// pure-function tests.
class DecisionsListViewTest {
    private fun t(id: String, num: Int, kind: ItemKind, origin: String) =
        TrackerItem(id = id, num = num, kind = kind, awaiting = ItemAwaiting.USER, rank = num.toDouble(), title = "T$num", originConvoID = origin)

    @Test
    fun rowsAreIdentifiedByItemID() {
        val row = DecisionsRow(t("q1", 1, ItemKind.QUESTION, "c1"), originTitle = null)
        assertEquals("q1", row.id)
    }

    @Test
    fun statePrecedence() {
        val empty = DecisionsListModel(rows = emptyList(), isSupported = true, isRefreshing = false)
        assertEquals(DecisionsListState.EMPTY, decisionsListState(empty))
        assertEquals("unsupported wins over empty", DecisionsListState.UNSUPPORTED, decisionsListState(empty.copy(isSupported = false)))
        assertEquals("an unknown support state shows the list", DecisionsListState.EMPTY, decisionsListState(empty.copy(isSupported = null)))
        val populated = empty.copy(rows = listOf(DecisionsRow(t("q1", 12, ItemKind.QUESTION, "c1"), "auth refactor")))
        assertEquals(DecisionsListState.POPULATED, decisionsListState(populated))
        assertEquals("unsupported hides rows too", DecisionsListState.UNSUPPORTED, decisionsListState(populated.copy(isSupported = false)))
    }

    private fun closed(id: String, num: Int, kind: ItemKind, resolution: ItemResolution? = ItemResolution.DONE) =
        t(id, num, kind, "c1").copy(state = ItemState.CLOSED, resolution = resolution, awaiting = null)

    @Test
    fun theListOpensOnNeedsYou() {
        val model = DecisionsListModel(rows = emptyList(), isSupported = true, isRefreshing = false)
        assertEquals(ForYouTab.OPEN, model.tab)
        assertEquals(listOf("Needs you", "Done"), ForYouTab.entries.map { it.title })
        assertTrue(decisionsShowsTabs(model))
        assertTrue("an unknown support state shows the tabs", decisionsShowsTabs(model.copy(isSupported = null)))
        assertFalse("nothing to switch between without a tracker", decisionsShowsTabs(model.copy(isSupported = false)))
    }

    @Test
    fun doneTabStatePrecedence() {
        val open = DecisionsRow(t("q1", 12, ItemKind.QUESTION, "c1"), "auth refactor")
        val done = DecisionsRow(closed("q2", 13, ItemKind.QUESTION), null)
        val model = DecisionsListModel(rows = listOf(open), isSupported = true, isRefreshing = false, tab = ForYouTab.CLOSED)
        assertEquals("open rows do not fill the Done tab", DecisionsListState.CLOSED_EMPTY, decisionsListState(model))
        val populated = model.copy(closed = listOf(done), closedTotalCount = 1)
        assertEquals(DecisionsListState.CLOSED_POPULATED, decisionsListState(populated))
        assertEquals("unsupported wins on the Done tab too", DecisionsListState.UNSUPPORTED, decisionsListState(populated.copy(isSupported = false)))
        assertEquals("closed rows do not fill Needs you", DecisionsListState.EMPTY, decisionsListState(populated.copy(tab = ForYouTab.OPEN, rows = emptyList())))
        assertEquals(DecisionsListState.POPULATED, decisionsListState(populated.copy(tab = ForYouTab.OPEN)))
    }

    @Test
    fun showMoreOnlyWhileRowsStayHidden() {
        val rows = (1..3).map { DecisionsRow(closed("q$it", it, ItemKind.QUESTION), null) }
        val model = DecisionsListModel(
            rows = emptyList(), isSupported = true, isRefreshing = false, tab = ForYouTab.CLOSED, closed = rows.take(2), closedTotalCount = 3,
        )
        assertTrue(model.hasMoreClosed)
        assertFalse(model.copy(closed = rows).hasMoreClosed)
    }

    @Test
    fun closedRowsCaptionTheResolutionAtTheSortTime() {
        val now = Instant.parse("2026-09-21T12:00:00Z")
        val question = closed("q1", 1, ItemKind.QUESTION, ItemResolution.ANSWERED)
            .copy(closedAt = now.minusSeconds(60), lastUserInputAt = now.minusSeconds(2 * 3600))
        assertEquals("the user's last input, not the later close", "Answered · 2 hr ago", decisionsClosedRowCaption(DecisionsRow(question, null), now))
        assertEquals("Answered · 1 min ago", decisionsClosedRowCaption(DecisionsRow(question.copy(lastUserInputAt = null), null), now))
        assertEquals("Closed · 1 min ago", decisionsClosedRowCaption(DecisionsRow(question.copy(lastUserInputAt = null, resolution = null), null), now))
        val notice = closed("n1", 2, ItemKind.NOTICE).copy(closedAt = now.minusSeconds(300), chosenAction = "seen")
        assertEquals("Seen · 5 min ago", decisionsClosedRowCaption(DecisionsRow(notice, null), now))
        assertFalse("a closed row offers no Seen button or swipe", decisionsRowOffersSeen(DecisionsRow(notice, null)))
    }

    @Test
    fun originCaptionAlwaysShowsWithTheTrackerFallback() {
        assertEquals("auth refactor", decisionsRowOrigin(DecisionsRow(t("q1", 1, ItemKind.QUESTION, "c1"), "auth refactor")))
        assertEquals("Another chat", decisionsRowOrigin(DecisionsRow(t("d1", 2, ItemKind.DECISION, "c2"), null)))
    }

    @Test
    fun forYouCopy() {
        assertEquals("For you", ForYouCopy.TITLE)
        assertEquals("the tab reads the same as the screen", ForYouCopy.TITLE, chat.matron.android.AppTab.DECISIONS.label)
        assertEquals("Nothing needs you", ForYouCopy.EMPTY_TITLE)
        assertEquals(
            "Questions, things to read and secret requests from every conversation appear here.",
            ForYouCopy.EMPTY_DESCRIPTION,
        )
        assertEquals("Nothing done yet", ForYouCopy.CLOSED_EMPTY_TITLE)
        assertEquals(
            "Questions and decisions move here once they are closed, and notices once you have seen them. " +
                "Newest first by your last reply.",
            ForYouCopy.CLOSED_EMPTY_DESCRIPTION,
        )
        assertEquals("Show more", ForYouCopy.SHOW_MORE)
    }

    @Test
    fun onlyOpenNoticesOfferSeen() {
        val notice = t("n1", 3, ItemKind.NOTICE, "c1").copy(actions = listOf(TrackerItem.SEEN_ACTION))
        assertTrue(decisionsRowOffersSeen(DecisionsRow(notice, null)))
        assertFalse(decisionsRowOffersSeen(DecisionsRow(notice.copy(state = ItemState.CLOSED), null)))
        assertFalse(decisionsRowOffersSeen(DecisionsRow(t("q1", 1, ItemKind.QUESTION, "c1"), null)))
    }

    @Test
    fun noticeRowsReadLighter() {
        val notice = t("n1", 3, ItemKind.NOTICE, "c1")
        assertTrue(itemRowTitleIsSecondary(notice))
        assertFalse(itemRowTitleIsSecondary(t("q1", 1, ItemKind.QUESTION, "c1")))
        assertEquals("To read", itemRowStatusCaption(notice))
        assertEquals("Needs you", itemRowStatusCaption(t("q1", 1, ItemKind.QUESTION, "c1")))
        assertEquals(
            "Seen",
            itemRowStatusCaption(notice.copy(state = ItemState.CLOSED, resolution = ItemResolution.DONE, awaiting = null)),
        )
        assertEquals("Notice 3, T3, to read", itemRowAccessibilityLabel(notice))
        assertEquals("To read", itemStatusText(notice))
        assertEquals(androidx.compose.material.icons.Icons.Outlined.Visibility, ItemGlyph.icon(ItemKind.NOTICE))
    }

    @Test
    fun itemChosenActionPrefersTheQueuedTap() {
        val item = t("n1", 3, ItemKind.NOTICE, "c1").copy(actions = listOf("Seen"))
        assertEquals(null, itemChosenAction(item, null))
        assertEquals("Seen", itemChosenAction(item, "Seen"))
        assertEquals("Seen", itemChosenAction(item.copy(chosenAction = "Seen"), null))
        assertEquals("a label no longer offered marks nothing", null, itemChosenAction(item, "Go"))
    }
}
