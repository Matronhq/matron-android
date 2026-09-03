package chat.matron.android.features

import chat.matron.android.chat.TimelineItem
import chat.matron.android.features.chat.summaryScrollIndex
import chat.matron.android.features.chat.summaryTimestampLabel
import chat.matron.android.features.chat.toggleExpandedSeq
import chat.matron.android.viewmodels.TimelineRow
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Pure-logic coverage for the summaries TOC sheet (apple #124/#126/#128
/// port). The Apple suite pins the rendered sheet via
/// `SummariesSheetBindingTests` + the Mac panel snapshots; this repo's
/// convention extracts the sheet's decisions into pure functions and tests
/// those instead (composables are never rendered in unit tests).
class SummariesSheetLogicTest {

    // MARK: toggleExpandedSeq — port of the iOS sheet's `toggle(_ seq:)`

    @Test fun tappingACollapsedRowExpandsIt() {
        assertEquals(42L, toggleExpandedSeq(current = null, tapped = 42))
    }

    @Test fun tappingTheExpandedRowCollapsesIt() {
        assertNull(toggleExpandedSeq(current = 42, tapped = 42))
    }

    @Test fun tappingAnotherRowMovesTheExpansion() {
        assertEquals(7L, toggleExpandedSeq(current = 42, tapped = 7))
    }

    // MARK: summaryTimestampLabel — the iOS `.dateTime.month().day().hour().minute()` caption

    @Test fun timestampLabelFormatsMonthDayHourMinute() {
        // 2026-08-16 14:30 UTC.
        val date = Instant.parse("2026-08-16T14:30:00Z")
        // JDK 20+ CLDR data puts a narrow no-break space before AM/PM; the
        // glyph choice is the locale's, not ours, so normalise it away.
        assertEquals("Aug 16, 2:30 PM", summaryTimestampLabel(date, ZoneOffset.UTC, Locale.US).replace('\u202f', ' '))
    }

    /// The clock half follows the locale (CodeRabbit, #37): a 24-hour locale
    /// must not be forced onto "h:mm a".
    @Test fun timestampLabelFollowsLocaleClockStyle() {
        val date = Instant.parse("2026-08-16T14:30:00Z")
        assertEquals("Aug 16, 14:30", summaryTimestampLabel(date, ZoneOffset.UTC, Locale.UK))
    }

    // MARK: summaryScrollIndex — the jump-to-point LazyColumn index

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
        assertEquals(2, summaryScrollIndex(rows, "10"))
        assertEquals(3, summaryScrollIndex(rows, "20"))
    }

    @Test fun scrollIndexIsNullWhenTheTargetIsNotInTheWindow() {
        assertNull(summaryScrollIndex(listOf(message("10")), "99"))
        assertNull(summaryScrollIndex(emptyList(), "10"))
    }

    /// A separator row whose id happens to embed the target must not match —
    /// only message rows are scroll anchors.
    @Test fun scrollIndexMatchesMessageRowsOnly() {
        val rows = listOf(TimelineRow.Separator(Instant.EPOCH), message("10"))
        assertNull(summaryScrollIndex(rows, "sep:0"))
    }
}
