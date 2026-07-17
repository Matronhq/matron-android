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

    // MARK: - Cache

    @Test
    fun sameSource_returnsCachedInstance() {
        val source = "A cached body with `code` and **bold**."
        assertTrue(parse(source) === parse(source))
    }
}
