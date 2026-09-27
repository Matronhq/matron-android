package chat.matron.android.designsystem

import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerItem
import org.junit.Assert.assertEquals
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
}
