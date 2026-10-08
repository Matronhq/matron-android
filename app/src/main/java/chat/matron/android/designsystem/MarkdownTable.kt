package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/// Table chrome, mirroring the Mac table styling (port of apple #134):
/// hairline cell borders in a separator colour, compact cell padding, and a
/// header row shaded with an onSurface tint rather than a fixed colour so it
/// reads on either theme's bubble — the same reasoning as the Mac's
/// label-colour overlay.
private val tableBorderWidth = 0.5.dp
private val tableCellPaddingHorizontal = 8.dp
private val tableCellPaddingVertical = 4.dp
private const val tableHeaderTintAlpha = 0.05f

/// Per-column widths for a row-major grid of measured cell widths: each
/// column takes its widest cell (the "automatic layout" the Mac gets from
/// `NSTextTable` for free). Pure so it's unit-testable without composition;
/// trailing cells past a complete row (defensive) still land in their column.
internal fun markdownTableColumnWidths(cellWidths: List<Int>, columnCount: Int): List<Int> {
    if (columnCount <= 0) return emptyList()
    val widths = IntArray(columnCount)
    cellWidths.forEachIndexed { index, w ->
        val c = index % columnCount
        if (w > widths[c]) widths[c] = w
    }
    return widths.toList()
}

/// [TextAlign] for a parsed column alignment — the analog of the Swift
/// `nsAlignment(_:)` (port of apple #134). Pure for testability.
internal fun markdownTableTextAlign(alignment: MarkdownTableAlignment): TextAlign =
    when (alignment) {
        MarkdownTableAlignment.Left -> TextAlign.Start
        MarkdownTableAlignment.Center -> TextAlign.Center
        MarkdownTableAlignment.Right -> TextAlign.End
    }

/// Renders one parsed [MarkdownTable] as a bordered grid: bold shaded header
/// row, per-column alignment, hairline dividers. Wide tables scroll
/// horizontally like [CodeBlock] does — the Compose analog of the Mac
/// letting a table span the bubble. Cells keep their inline spans, so links
/// inside cells stay tappable via [onLinkClick].
@Composable
fun MarkdownTableBlock(
    table: MarkdownTable,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    onLinkClick: ((String) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val click: (String) -> Unit = onLinkClick ?: { url -> runCatching { uriHandler.openUri(url) } }
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.onSurface.copy(alpha = tableHeaderTintAlpha)
    val cellStyle = textStyle.merge(MaterialTheme.typography.bodyLarge)

    Row(modifier.horizontalScroll(rememberScrollState())) {
        TableGrid(table, borderColor, headerBg, cellStyle, click)
    }
}

/// The grid itself: a custom [Layout] because Compose has no text-table
/// primitive. Cells are measured at their intrinsic width, columns take
/// their widest cell ([markdownTableColumnWidths]), and every cell is then
/// re-measured to its column width and row height so backgrounds and drawn
/// borders fill the full cell rectangle.
@Composable
private fun TableGrid(
    table: MarkdownTable,
    borderColor: Color,
    headerBg: Color,
    cellStyle: TextStyle,
    onLinkClick: (String) -> Unit,
) {
    val columnCount = table.columnCount
    val allRows = listOf(table.header) + table.rows
    Layout(
        content = {
            allRows.forEachIndexed { r, row ->
                row.forEachIndexed { c, cell ->
                    TableCell(
                        text = cell,
                        isHeader = r == 0,
                        align = markdownTableTextAlign(
                            table.alignments.getOrElse(c) { MarkdownTableAlignment.Left }
                        ),
                        // Interior edges only: the outer border is one drawn
                        // rectangle, so shared hairlines never double up.
                        drawEndEdge = c < columnCount - 1,
                        drawBottomEdge = r < allRows.size - 1,
                        borderColor = borderColor,
                        headerBg = headerBg,
                        style = cellStyle,
                        onLinkClick = onLinkClick,
                    )
                }
            }
        },
        modifier = Modifier.drawBehind {
            drawRect(borderColor, style = Stroke(tableBorderWidth.toPx()))
        },
    ) { measurables, _ ->
        val columnWidths = markdownTableColumnWidths(
            measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) },
            columnCount,
        )
        val rowHeights = allRows.indices.map { r ->
            (0 until columnCount).maxOf { c ->
                measurables[r * columnCount + c].minIntrinsicHeight(columnWidths[c])
            }
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(
                Constraints.fixed(columnWidths[index % columnCount], rowHeights[index / columnCount])
            )
        }
        layout(columnWidths.sum(), rowHeights.sum()) {
            var index = 0
            var y = 0
            for (r in allRows.indices) {
                var x = 0
                for (c in 0 until columnCount) {
                    placeables[index].place(x, y)
                    x += columnWidths[c]
                    index++
                }
                y += rowHeights[r]
            }
        }
    }
}

@Composable
private fun TableCell(
    text: AnnotatedString,
    isHeader: Boolean,
    align: TextAlign,
    drawEndEdge: Boolean,
    drawBottomEdge: Boolean,
    borderColor: Color,
    headerBg: Color,
    style: TextStyle,
    onLinkClick: (String) -> Unit,
) {
    val stroke = with(LocalDensity.current) { tableBorderWidth.toPx() }
    Box(
        Modifier
            .then(if (isHeader) Modifier.background(headerBg) else Modifier)
            .drawBehind {
                if (drawEndEdge) {
                    drawLine(borderColor, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
                }
                if (drawBottomEdge) {
                    drawLine(borderColor, Offset(0f, size.height), Offset(size.width, size.height), stroke)
                }
            }
            .padding(horizontal = tableCellPaddingHorizontal, vertical = tableCellPaddingVertical),
    ) {
        ClickableText(
            text = text,
            style = style.merge(TextStyle(textAlign = align)),
            modifier = Modifier.fillMaxWidth(),
            onClick = { offset ->
                text.getStringAnnotations("URL", offset, offset)
                    .firstOrNull()?.let { onLinkClick(it.item) }
            },
        )
    }
}
