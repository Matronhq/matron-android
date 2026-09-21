package chat.matron.android.features

import chat.matron.android.chat.TimelineItem
import chat.matron.android.features.chat.focusScrollIndex
import chat.matron.android.viewmodels.TimelineRow
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// The jump-to-point LazyColumn index behind milestone taps and search
/// hits (`ChatScreen.focusScrollIndex`). The summaries sheet this used to
/// serve was retired for the mission page (apple #209 / #214); the jump
/// itself stays.
class ChatFocusScrollIndexTest {

    private fun message(id: String) = TimelineRow.Message(
        TimelineItem(
            id = id, sender = "@a:s", timestamp = Instant.EPOCH,
            kind = TimelineItem.Kind.Text("m$id", null), isOwn = false,
        ),
    )

    /// Row i sits at LazyColumn index i + 1: index 0 is the always-present
    /// "paginating" item (see TimelineList).
    @Test fun scrollIndexAccountsForThePaginatingHeaderRow() {
        val rows = listOf(TimelineRow.Separator(Instant.EPOCH), message("10"), message("20"))
        assertEquals(2, focusScrollIndex(rows, "10"))
        assertEquals(3, focusScrollIndex(rows, "20"))
    }

    @Test fun scrollIndexIsNullWhenTheTargetIsNotInTheWindow() {
        assertNull(focusScrollIndex(listOf(message("10")), "99"))
        assertNull(focusScrollIndex(emptyList(), "10"))
    }

    /// A separator row whose id happens to embed the target must not match —
    /// only message rows are scroll anchors.
    @Test fun scrollIndexMatchesMessageRowsOnly() {
        val rows = listOf(TimelineRow.Separator(Instant.EPOCH), message("10"))
        assertNull(focusScrollIndex(rows, "sep:0"))
    }
}
