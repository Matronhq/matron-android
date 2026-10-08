package chat.matron.android.designsystem

import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.TrackerItem
import androidx.compose.material.icons.outlined.Visibility
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
