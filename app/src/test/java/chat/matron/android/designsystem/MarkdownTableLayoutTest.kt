package chat.matron.android.designsystem

import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Unit tests for the pure layout helpers behind [MarkdownTableBlock] (port
/// of apple #134 — the Mac gets column sizing from `NSTextTable`'s automatic
/// layout; Compose has no text-table primitive, so the sizing rule is a
/// testable function here).
class MarkdownTableLayoutTest {

    // MARK: - Column widths

    @Test
    fun columnWidths_eachColumnTakesItsWidestCell() {
        // Row-major 2x3: rows (10, 40, 5) and (30, 20, 8).
        assertEquals(
            listOf(30, 40, 8),
            markdownTableColumnWidths(listOf(10, 40, 5, 30, 20, 8), columnCount = 3),
        )
    }

    @Test
    fun columnWidths_singleColumn() {
        assertEquals(listOf(25), markdownTableColumnWidths(listOf(10, 25, 5), columnCount = 1))
    }

    @Test
    fun columnWidths_trailingPartialRowStillCounts() {
        // Defensive: 5 cells over 2 columns — the dangling 5th cell lands in
        // column 0 and can still widen it.
        assertEquals(
            listOf(50, 20),
            markdownTableColumnWidths(listOf(10, 20, 30, 5, 50), columnCount = 2),
        )
    }

    @Test
    fun columnWidths_zeroOrNegativeColumnCountIsEmpty() {
        assertTrue(markdownTableColumnWidths(listOf(10, 20), columnCount = 0).isEmpty())
        assertTrue(markdownTableColumnWidths(emptyList(), columnCount = -1).isEmpty())
    }

    @Test
    fun columnWidths_noCellsYieldsZeroWidths() {
        assertEquals(listOf(0, 0), markdownTableColumnWidths(emptyList(), columnCount = 2))
    }

    // MARK: - Alignment mapping

    /// Port of the mapping asserted by Apple
    /// `test_table_columnAlignmentMapsToParagraphAlignment` — the Swift
    /// `nsAlignment(_:)` analog.
    @Test
    fun textAlign_mapsAllAlignments() {
        assertEquals(TextAlign.Start, markdownTableTextAlign(MarkdownTableAlignment.Left))
        assertEquals(TextAlign.Center, markdownTableTextAlign(MarkdownTableAlignment.Center))
        assertEquals(TextAlign.End, markdownTableTextAlign(MarkdownTableAlignment.Right))
    }
}
