package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Unit tests for the custom Markdown → [AnnotatedString] converter. Ported
/// from the Mac-only Swift `MarkdownAttributedTests`, with `NSAttributedString`
/// attribute lookups adapted to Compose [SpanStyle] span checks and the
/// AppKit-only `size(for:)` measurement tests dropped (no Compose analog).
class MarkdownAttributedTest {
    private val colors = MarkdownColors(
        onSurface = Color.Black,
        secondary = Color(0xFF888888),
        codeBg = Color(0xFFEEEEEE),
        link = Color(0xFF0B6E7D),
    )

    private fun parse(source: String) = MarkdownAttributed.parse(source, colors)

    /// Merged [SpanStyle] covering the first char of [sub] in [this].
    private fun AnnotatedString.styleAt(sub: String): SpanStyle {
        val idx = text.indexOf(sub)
        assertTrue("substring '$sub' not found in '${text}'", idx >= 0)
        return spanStyles.filter { idx >= it.start && idx < it.end }
            .fold(SpanStyle()) { acc, r -> acc.merge(r.item) }
    }

    // MARK: - Inline intents

    @Test
    fun bold_setsBoldWeight() {
        val block = parse("This is **bold** text.").blocks.first()
        assertEquals(FontWeight.Bold, block.text.styleAt("bold").fontWeight)
    }

    @Test
    fun italic_setsItalicStyle() {
        val block = parse("This is *slanted* text.").blocks.first()
        assertEquals(FontStyle.Italic, block.text.styleAt("slanted").fontStyle)
    }

    @Test
    fun inlineCode_monospacedWithBackground() {
        val block = parse("Run `swift test` now.").blocks.first()
        val style = block.text.styleAt("swift test")
        assertEquals(FontFamily.Monospace, style.fontFamily)
        assertEquals(colors.codeBg, style.background)
    }

    @Test
    fun strikethrough_setsLineThrough() {
        val block = parse("This is ~~gone~~ now.").blocks.first()
        assertEquals(TextDecoration.LineThrough, block.text.styleAt("gone").textDecoration)
    }

    // MARK: - Lists

    @Test
    fun unorderedList_getsBulletPrefix() {
        val doc = parse("- One\n- Two")
        assertTrue(doc.annotated.text.contains("• One"))
        assertTrue(doc.annotated.text.contains("• Two"))
    }

    @Test
    fun orderedList_getsNumberPrefix() {
        val doc = parse("1. First\n2. Second")
        assertTrue(doc.annotated.text.contains("1. First"))
        assertTrue(doc.annotated.text.contains("2. Second"))
    }

    // MARK: - Links

    @Test
    fun httpsLink_getsLinkAnnotationAndAccentColour() {
        val block = parse("See the [docs](https://example.com/help) here.").blocks.first()
        val text = block.text
        val idx = text.text.indexOf("docs")
        val annotations = text.getStringAnnotations("URL", idx, idx + 4)
        assertEquals("https://example.com/help", annotations.firstOrNull()?.item)
        assertEquals(colors.link, text.styleAt("docs").color)
    }

    @Test
    fun matrixLink_suppressedButAccentColoured() {
        val block = parse("Jump to [room](matrix:r/foo:example.com) now.").blocks.first()
        val text = block.text
        val idx = text.text.indexOf("room")
        assertTrue("matrix: links must not become clickable", text.getStringAnnotations("URL", idx, idx + 4).isEmpty())
        assertEquals(colors.link, text.styleAt("room").color)
    }

    // MARK: - Headers

    @Test
    fun headerSizesStepUp() {
        val base = MarkdownAttributed.baseFontSize
        assertEquals((base * 1.3f).sp, parse("# Big").blocks.first().text.styleAt("Big").fontSize)
        assertEquals(FontWeight.Bold, parse("# Big").blocks.first().text.styleAt("Big").fontWeight)
        assertEquals((base * 1.15f).sp, parse("## Medium").blocks.first().text.styleAt("Medium").fontSize)
        assertEquals((base * 1.05f).sp, parse("### Small").blocks.first().text.styleAt("Small").fontSize)
    }

    @Test
    fun headerAfterParagraph_getsSpacingBefore() {
        val header = parse("Intro paragraph.\n\n## Section\n\nBody text.")
            .blocks.first { it.kind == MarkdownBlockKind.Header }
        assertEquals(MarkdownAttributed.headerSpacingBefore, header.spacingBefore)
        assertEquals(MarkdownAttributed.headerSpacingAfter, header.spacingAfter)
    }

    @Test
    fun leadingHeader_suppressesSpacingBefore() {
        val header = parse("# Title\n\nBody text.")
            .blocks.first { it.kind == MarkdownBlockKind.Header }
        assertEquals(0f, header.spacingBefore)
    }

    // MARK: - Code blocks

    @Test
    fun codeBlock_monospacedWithBackground() {
        val doc = parse("Here:\n```swift\nlet x = 1\n```")
        val block = doc.blocks.first { it.kind == MarkdownBlockKind.CodeBlock }
        val style = block.text.styleAt("let x = 1")
        assertEquals(FontFamily.Monospace, style.fontFamily)
        assertEquals(MarkdownAttributed.codeBlockFontSize.sp, style.fontSize)
        assertEquals(colors.codeBg, style.background)
    }

    // MARK: - Trailing newlines

    @Test
    fun output_neverEndsWithNewline() {
        val endings = listOf(
            "para\n\n```swift\nlet x = 1\nlet y = 2\n```",
            "para\n\n```swift\nlet x = 1\n```\n",
            "para\n\n- alpha\n- beta",
            "para\n\n## Trailing heading",
            "Closing paragraph.",
            "Closing paragraph.\n\n\n",
        )
        for (source in endings) {
            assertFalseEndsNewline(parse(source).annotated.text, source)
        }
    }

    private fun assertFalseEndsNewline(text: String, source: String) {
        assertTrue(
            "converted string must not end with a newline for source ending '${source.takeLast(20)}'",
            !text.endsWith("\n"),
        )
    }

    @Test
    fun codeBlockBeforeParagraph_singleNewlineBetween() {
        val doc = parse("Intro line.\n\n```swift\nlet x = 1\n```\n\nClosing paragraph.")
        assertTrue(doc.annotated.text.contains("let x = 1\nClosing paragraph."))
    }

    @Test
    fun codeBlock_keepsInteriorBlankLines() {
        val doc = parse("```swift\nlet a = 1\n\nlet b = 2\n```")
        assertTrue(doc.annotated.text.contains("let a = 1\n\nlet b = 2"))
    }

    // MARK: - Tables (port of apple #134)

    private val tableSource = "| Repo | PR |\n| :--- | ---: |\n| bridge | **215** |\n| apple | 133 |"

    /// Port of Apple `test_tableCell_classifiedWithRowColumnHeader`: the
    /// header/body/alignment structure the Swift `BlockKind.tableCell` carries
    /// per cell lives on the Android block's [MarkdownTable].
    @Test
    fun table_parsesHeaderBodyAndAlignments() {
        val doc = parse(tableSource)
        assertEquals(1, doc.blocks.size)
        val block = doc.blocks.first()
        assertEquals(MarkdownBlockKind.Table, block.kind)
        val table = block.table!!
        assertEquals(2, table.columnCount)
        assertEquals(
            listOf(MarkdownTableAlignment.Left, MarkdownTableAlignment.Right),
            table.alignments,
        )
        assertEquals(listOf("Repo", "PR"), table.header.map { it.text })
        assertEquals(2, table.rows.size)
        assertEquals(listOf("bridge", "215"), table.rows[0].map { it.text })
        assertEquals(listOf("apple", "133"), table.rows[1].map { it.text })
    }

    /// Port of Apple `test_table_cellsCarryTableBlocks` (styling half):
    /// header cells bold, body cells not.
    @Test
    fun table_headerCellsBoldBodyCellsNot() {
        val table = parse(tableSource).blocks.first().table!!
        assertEquals(FontWeight.Bold, table.header[0].styleAt("Repo").fontWeight)
        assertTrue(table.rows[1][0].styleAt("apple").fontWeight != FontWeight.Bold)
    }

    /// Port of Apple `test_inlineStylesInsideCells_keepAttributes`: `**215**`
    /// inside a cell keeps its bold span (and loses its markers).
    @Test
    fun table_inlineStylesInsideCellsKeepSpans() {
        val table = parse(tableSource).blocks.first().table!!
        val cell = table.rows[0][1]
        assertEquals("215", cell.text)
        assertEquals(FontWeight.Bold, cell.styleAt("215").fontWeight)
    }

    /// Port of Apple `test_twoAdjacentTables_getSeparateTextTables`: two
    /// blank-line-separated tables parse as two Table blocks, each with its
    /// own column count.
    @Test
    fun table_twoAdjacentTablesStaySeparateBlocks() {
        val doc = parse(tableSource + "\n\n| X |\n| --- |\n| y |")
        val tables = doc.blocks.filter { it.kind == MarkdownBlockKind.Table }
        assertEquals(2, tables.size)
        assertEquals(2, tables[0].table!!.columnCount)
        assertEquals(1, tables[1].table!!.columnCount)
        assertEquals("y", tables[1].table!!.rows[0][0].text)
    }

    /// Port of Apple `test_reconstruct_fullTable_roundTripsPipesAndAlignment`
    /// adapted to the flat-copy model: the table block degrades to pipe text
    /// with the delimiter row rebuilt from alignments (left stays plain
    /// `---`). Inline markers are gone — the Android flat string is display
    /// text with spans, unlike the Mac's marker-reconstructing copy path.
    @Test
    fun table_annotatedDegradesToPipeText() {
        assertEquals(
            "| Repo | PR |\n| --- | ---: |\n| bridge | 215 |\n| apple | 133 |",
            parse(tableSource).annotated.text,
        )
    }

    /// Port of Apple `test_reconstruct_tableBetweenParagraphs_blankLineSeparated`
    /// (Android's flat string joins blocks with a single newline, not a blank
    /// line — the existing [MarkdownDocument.annotated] convention).
    @Test
    fun table_betweenParagraphsKeepsBlockOrder() {
        val doc = parse("Before.\n\n| A | B |\n| --- | --- |\n| c | d |\n\nAfter.")
        assertEquals(
            listOf(MarkdownBlockKind.Paragraph, MarkdownBlockKind.Table, MarkdownBlockKind.Paragraph),
            doc.blocks.map { it.kind },
        )
        assertEquals("Before.\n| A | B |\n| --- | --- |\n| c | d |\nAfter.", doc.annotated.text)
    }

    /// Port of Apple `test_messageEndingInTable_keepsSingleTerminatorNewline`,
    /// adapted: the Android flat model needs no cell terminator, so a message
    /// ending in a table simply must not end with a newline.
    @Test
    fun table_messageEndingInTableHasNoTrailingNewline() {
        val doc = parse("Intro.\n\n" + tableSource)
        assertTrue(doc.annotated.text.endsWith("| apple | 133 |"))
        assertTrue(!doc.annotated.text.endsWith("\n"))
    }

    // MARK: - Table parsing edge cases (GFM rules Apple inherits from cmark-gfm)

    /// A delimiter row whose cell count differs from the header's is not a
    /// table — the lines stay paragraphs (GFM rejects the table).
    @Test
    fun table_delimiterColumnMismatchStaysParagraph() {
        val doc = parse("| A | B |\n| --- |\n| c | d |")
        assertTrue(doc.blocks.none { it.kind == MarkdownBlockKind.Table })
    }

    /// Header + delimiter alone is a valid, body-less table.
    @Test
    fun table_headerAndDelimiterOnlyYieldsEmptyBody() {
        val table = parse("| A | B |\n| --- | --- |").blocks.first().table!!
        assertEquals(listOf("A", "B"), table.header.map { it.text })
        assertTrue(table.rows.isEmpty())
    }

    /// GFM row normalisation: short rows pad with empty cells, long rows drop
    /// the excess.
    @Test
    fun table_rowsNormaliseToHeaderColumnCount() {
        val table = parse("| A | B |\n| --- | --- |\n| only |\n| c | d | extra |").blocks.first().table!!
        assertEquals(listOf("only", ""), table.rows[0].map { it.text })
        assertEquals(listOf("c", "d"), table.rows[1].map { it.text })
    }

    /// `\|` is a literal pipe inside a cell, not a cell boundary.
    @Test
    fun table_escapedPipeStaysInsideCell() {
        val table = parse("| A | B |\n| --- | --- |\n| a \\| b | c |").blocks.first().table!!
        assertEquals("a | b", table.rows[0][0].text)
    }

    /// GFM makes the outer pipes optional.
    @Test
    fun table_outerPipesOptional() {
        val table = parse("A | B\n--- | ---\n1 | 2").blocks.first().table!!
        assertEquals(2, table.columnCount)
        assertEquals(listOf("1", "2"), table.rows[0].map { it.text })
    }

    /// GFM tables interrupt paragraphs — no blank line required before the
    /// header row.
    @Test
    fun table_interruptsParagraph() {
        val doc = parse("Intro line\n| A | B |\n| --- | --- |\n| c | d |")
        assertEquals(
            listOf(MarkdownBlockKind.Paragraph, MarkdownBlockKind.Table),
            doc.blocks.map { it.kind },
        )
        assertEquals("Intro line", doc.blocks[0].text.text)
    }

    /// GFM spec example 205: a plain line directly after the table (no blank
    /// line) is swallowed as a one-cell row.
    @Test
    fun table_plainLineWithoutBlankBecomesRow() {
        val table = parse("| A | B |\n| --- | --- |\n| c | d |\nplain").blocks.first().table!!
        assertEquals(2, table.rows.size)
        assertEquals(listOf("plain", ""), table.rows[1].map { it.text })
    }

    /// A pipe-bearing line NOT followed by a delimiter row is an ordinary
    /// paragraph.
    @Test
    fun table_pipeLineWithoutDelimiterStaysParagraph() {
        val doc = parse("a | b\nnot a delimiter")
        assertEquals(listOf(MarkdownBlockKind.Paragraph), doc.blocks.map { it.kind })
    }

    /// A link inside a cell keeps its URL annotation for the tappable path.
    @Test
    fun table_linkInsideCellKeepsAnnotation() {
        val table = parse("| Repo | PR |\n| --- | --- |\n| bridge | [#215](https://example.com) |")
            .blocks.first().table!!
        val cell = table.rows[0][1]
        val idx = cell.text.indexOf("#215")
        assertEquals("https://example.com", cell.getStringAnnotations("URL", idx, idx + 1).firstOrNull()?.item)
    }

    // MARK: - Cache

    @Test
    fun sameSource_returnsCachedInstance() {
        val source = "A cached body with `code` and **bold**."
        assertTrue(parse(source) === parse(source))
    }
}
