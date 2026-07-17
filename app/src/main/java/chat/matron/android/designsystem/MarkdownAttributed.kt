package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/// The block-level markdown structure this converter renders. Mirrors the
/// Swift `MarkdownAttributed` `BlockKind`, distilled to what a chat message
/// needs.
enum class MarkdownBlockKind { Paragraph, Header, CodeBlock, BlockQuote, ListItem }

/// One rendered block. [text] is the display-ready [AnnotatedString] (list
/// markers already prepended). [spacingBefore]/[spacingAfter] are the visual
/// gaps (in dp) the renderer leaves around the block — carried as data because
/// Compose has no paragraph-spacing text attribute, so the Column applies them.
data class MarkdownBlock(
    val kind: MarkdownBlockKind,
    val text: AnnotatedString,
    val headerLevel: Int? = null,
    val language: String? = null,
    val spacingBefore: Float = 0f,
    val spacingAfter: Float = 0f,
)

/// Parsed markdown message: the ordered [blocks] the [MarkdownText] renderer
/// walks, plus a single flattened [annotated] string for the selectable
/// (SelectionContainer) path — the analog of the Mac's one-flat-NSAttributedString.
class MarkdownDocument(val blocks: List<MarkdownBlock>) {
    /// One flat string: blocks joined by a single newline, no trailing newline
    /// (a message ending in a code block must not leave dead space). Interior
    /// blank lines inside a code block are preserved — only block-boundary
    /// plumbing is normalised.
    val annotated: AnnotatedString = buildAnnotatedString {
        blocks.forEachIndexed { i, block ->
            append(block.text)
            if (i < blocks.size - 1) append("\n")
        }
    }

    val plainText: String get() = annotated.text
}

/// The theme colours the parser bakes into spans. Passed in (rather than read
/// from a CompositionLocal) so conversion stays a pure function — testable and
/// cacheable off the SwiftUI/Compose tree.
data class MarkdownColors(
    val onSurface: Color,
    val secondary: Color,
    val codeBg: Color,
    val link: Color,
)

/// Custom Markdown → [AnnotatedString] converter (block + inline). Apple's
/// `AttributedString(markdown:)` has no Compose analog, so the parsing rules
/// are reimplemented here: bold/italic/inline-code/strikethrough/links inline;
/// headings, fenced code blocks, ordered/unordered lists and block quotes as
/// blocks. Output is memoised on (source, colours) — messages are immutable, so
/// the same body converts once.
object MarkdownAttributed {
    /// Base body size (sp). Headers step up over this; inline code steps to
    /// 0.92×; code blocks render flat at [codeBlockFontSize].
    const val baseFontSize: Float = 15f
    const val codeBlockFontSize: Float = 12f
    private const val inlineCodeScale: Float = 0.92f

    /// Visual gaps (dp) the renderer applies around blocks — see [MarkdownBlock].
    const val paragraphSpacing: Float = 8f
    const val headerSpacingBefore: Float = 10f
    const val headerSpacingAfter: Float = 6f
    private const val listSpacing: Float = 2f

    private val cache = object : LinkedHashMap<Pair<String, MarkdownColors>, MarkdownDocument>(
        16, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<String, MarkdownColors>, MarkdownDocument>?
        ): Boolean = size > 400
    }

    /// Converts markdown [source] to a display-ready [MarkdownDocument].
    /// Memoised: the same (source, colours) returns the same instance.
    fun parse(source: String, colors: MarkdownColors): MarkdownDocument {
        val key = source to colors
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val built = MarkdownDocument(buildBlocks(source, colors))
        synchronized(cache) {
            cache[key]?.let { return it }
            cache[key] = built
        }
        return built
    }

    private fun headerFontSize(level: Int): Float = when (level) {
        1 -> baseFontSize * 1.3f
        2 -> baseFontSize * 1.15f
        3 -> baseFontSize * 1.05f
        else -> baseFontSize
    }

    private fun buildBlocks(source: String, colors: MarkdownColors): List<MarkdownBlock> {
        val lines = source.split("\n")
        val blocks = mutableListOf<MarkdownBlock>()
        var i = 0
        var isFirstBlock = true

        val headerRegex = Regex("^(#{1,6})\\s+(.*)$")
        val unorderedRegex = Regex("^\\s*[-*+]\\s+(.*)$")
        val orderedRegex = Regex("^\\s*(\\d+)\\.\\s+(.*)$")
        val fenceRegex = Regex("^\\s*```(.*)$")

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block.
            val fence = fenceRegex.find(line)
            if (fence != null) {
                val language = fence.groupValues[1].trim().ifEmpty { null }
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && fenceRegex.find(lines[i]) == null) {
                    body.add(lines[i]); i++
                }
                if (i < lines.size) i++ // consume closing fence
                val codeStyle = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = codeBlockFontSize.sp,
                    color = colors.onSurface,
                    background = colors.codeBg,
                )
                val text = buildAnnotatedString {
                    pushStyle(codeStyle); append(body.joinToString("\n")); pop()
                }
                blocks.add(MarkdownBlock(MarkdownBlockKind.CodeBlock, text, language = language, spacingAfter = paragraphSpacing))
                isFirstBlock = false
                continue
            }

            // Blank line — block separator.
            if (line.isBlank()) { i++; continue }

            // Heading.
            val header = headerRegex.find(line)
            if (header != null) {
                val level = header.groupValues[1].length
                val base = SpanStyle(
                    fontSize = headerFontSize(level).sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                )
                val text = buildAnnotatedString { appendInline(header.groupValues[2], base, colors) }
                blocks.add(
                    MarkdownBlock(
                        MarkdownBlockKind.Header, text, headerLevel = level,
                        spacingBefore = if (isFirstBlock) 0f else headerSpacingBefore,
                        spacingAfter = headerSpacingAfter,
                    )
                )
                isFirstBlock = false
                i++
                continue
            }

            // Block quote — consecutive `>` lines.
            if (line.trimStart().startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteLines.add(lines[i].trimStart().removePrefix(">").removePrefix(" "))
                    i++
                }
                val base = SpanStyle(fontSize = baseFontSize.sp, color = colors.secondary)
                val text = buildAnnotatedString { appendInline(quoteLines.joinToString(" "), base, colors) }
                blocks.add(MarkdownBlock(MarkdownBlockKind.BlockQuote, text, spacingAfter = paragraphSpacing))
                isFirstBlock = false
                continue
            }

            // List item (each item is its own block, matching the Swift model).
            val unordered = unorderedRegex.find(line)
            val ordered = orderedRegex.find(line)
            if (unordered != null || ordered != null) {
                val marker = if (ordered != null) "${ordered.groupValues[1]}. " else "• "
                val content = if (ordered != null) ordered.groupValues[2] else unordered!!.groupValues[1]
                val base = SpanStyle(fontSize = baseFontSize.sp, color = colors.onSurface)
                val text = buildAnnotatedString {
                    pushStyle(base); append(marker); pop()
                    appendInline(content, base, colors)
                }
                blocks.add(MarkdownBlock(MarkdownBlockKind.ListItem, text, spacingAfter = listSpacing))
                isFirstBlock = false
                i++
                continue
            }

            // Paragraph — gather consecutive plain lines (soft-wrapped to one).
            val paraLines = mutableListOf<String>()
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank() || headerRegex.find(l) != null || fenceRegex.find(l) != null ||
                    unorderedRegex.find(l) != null || orderedRegex.find(l) != null ||
                    l.trimStart().startsWith(">")
                ) break
                paraLines.add(l); i++
            }
            val base = SpanStyle(fontSize = baseFontSize.sp, color = colors.onSurface)
            val text = buildAnnotatedString { appendInline(paraLines.joinToString(" "), base, colors) }
            blocks.add(MarkdownBlock(MarkdownBlockKind.Paragraph, text, spacingAfter = paragraphSpacing))
            isFirstBlock = false
        }

        return blocks
    }

    /// Inline parser: bold (`**`/`__`), italic (`*`/`_`), inline code, strike
    /// (`~~`), and `[text](url)` links, applied over [base]. Recurses so nested
    /// emphasis composes. Link scheme policy mirrors `MarkdownText.handle`:
    /// `matrix:`/`mxc:` links render as accent text with NO click annotation;
    /// everything else gets an underlined, accent, tappable link.
    private fun AnnotatedString.Builder.appendInline(
        text: String,
        base: SpanStyle,
        colors: MarkdownColors,
    ) {
        var i = 0
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isEmpty()) return
            pushStyle(base); append(plain.toString()); pop()
            plain.setLength(0)
        }

        while (i < text.length) {
            val rest = text.substring(i)
            // Inline code — literal inner content, no further parsing.
            if (text[i] == '`') {
                val close = text.indexOf('`', i + 1)
                if (close != -1) {
                    flushPlain()
                    val codeStyle = base.merge(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseFontSize * inlineCodeScale).sp,
                            background = colors.codeBg,
                        )
                    )
                    pushStyle(codeStyle); append(text.substring(i + 1, close)); pop()
                    i = close + 1
                    continue
                }
            }
            // Bold (** or __).
            if (rest.startsWith("**") || rest.startsWith("__")) {
                val marker = rest.substring(0, 2)
                val close = text.indexOf(marker, i + 2)
                if (close != -1) {
                    flushPlain()
                    appendInline(text.substring(i + 2, close), base.merge(SpanStyle(fontWeight = FontWeight.Bold)), colors)
                    i = close + 2
                    continue
                }
            }
            // Strikethrough.
            if (rest.startsWith("~~")) {
                val close = text.indexOf("~~", i + 2)
                if (close != -1) {
                    flushPlain()
                    appendInline(text.substring(i + 2, close), base.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)), colors)
                    i = close + 2
                    continue
                }
            }
            // Italic (* or _).
            if (text[i] == '*' || text[i] == '_') {
                val close = text.indexOf(text[i], i + 1)
                if (close != -1 && close > i + 1) {
                    flushPlain()
                    appendInline(text.substring(i + 1, close), base.merge(SpanStyle(fontStyle = FontStyle.Italic)), colors)
                    i = close + 1
                    continue
                }
            }
            // Link [label](url).
            if (text[i] == '[') {
                val match = linkRegex.find(rest)
                if (match != null && match.range.first == 0) {
                    val label = match.groupValues[1]
                    val url = match.groupValues[2]
                    flushPlain()
                    val scheme = url.substringBefore(':', "").lowercase()
                    val suppressed = scheme == "matrix" || scheme == "mxc"
                    val linkStyle = base.merge(
                        SpanStyle(
                            color = colors.link,
                            textDecoration = if (suppressed) null else TextDecoration.Underline,
                        )
                    )
                    val start = length
                    pushStyle(linkStyle); append(label); pop()
                    if (!suppressed) addStringAnnotation("URL", url, start, length)
                    i += match.value.length
                    continue
                }
            }
            plain.append(text[i])
            i++
        }
        flushPlain()
    }

    private val linkRegex = Regex("^\\[([^\\]]*)\\]\\(([^)]*)\\)")
}
