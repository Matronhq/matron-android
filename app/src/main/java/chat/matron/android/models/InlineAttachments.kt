package chat.matron.android.models

/// One piece of an item or comment body, in reading order: markdown text for
/// the existing renderer, or an attachment shown in place.
sealed interface InlineSegment {
    data class Text(val markdown: String) : InlineSegment
    /// [caption] is the ref's caption text, as written (may be empty).
    data class Attachment(val attachment: TrackerAttachment, val caption: String = "") : InlineSegment
}

/// [segments] in order, then the [trailing] attachments no ref placed —
/// those render after the body, as they always have.
data class InlineSplit(val segments: List<InlineSegment>, val trailing: List<TrackerAttachment>)

/// The stored inline form, `![caption](attachment:<blob_ref>)`. Every client
/// uses this exact grammar (the shared "inline images" spec).
val INLINE_ATTACHMENT_REF = Regex("""!\[([^\]\n]*)\]\(attachment:([A-Za-z0-9_-]{1,128})\)""")

/// Splits [body] into text and attachment segments per the shared spec:
/// - a ref resolves only against [attachments] — THIS body's own list;
/// - each attachment shows once, at its first resolving ref; an unresolved
///   or repeated ref becomes its caption text (nothing if the caption is
///   empty);
/// - refs inside fenced code blocks or inline code spans stay literal (a
///   code span wholly inside a caption does not hide its ref);
/// - empty / whitespace-only text segments are dropped and blank lines at a
///   text segment's edges are trimmed;
/// - attachments no ref used come back as [InlineSplit.trailing].
fun splitInlineAttachments(body: String, attachments: List<TrackerAttachment>): InlineSplit {
    val byRef = LinkedHashMap<String, TrackerAttachment>()
    for (a in attachments) byRef.putIfAbsent(a.blobRef, a)
    val used = HashSet<String>()
    val segments = mutableListOf<InlineSegment>()
    if (body.isEmpty()) return InlineSplit(segments, attachments)

    val code = codeRegions(body)
    val text = StringBuilder()
    // Whether the text being collected starts mid-line, right after an
    // attachment: its leading spaces are then the gap beside the image, not
    // markdown indentation.
    var startsMidLine = false
    var cursor = 0

    fun flush() {
        val markdown = trimSegment(text.toString(), startsMidLine)
        if (markdown.isNotBlank()) segments += InlineSegment.Text(markdown)
        text.setLength(0)
    }

    for (match in INLINE_ATTACHMENT_REF.findAll(body)) {
        if (code.hides(match.range)) continue
        text.append(body, cursor, match.range.first)
        cursor = match.range.last + 1
        val caption = match.groupValues[1]
        val ref = match.groupValues[2]
        val attachment = byRef[ref]
        if (attachment != null && used.add(ref)) {
            flush()
            segments += InlineSegment.Attachment(attachment, caption)
            startsMidLine = true
        } else {
            text.append(caption)
        }
    }
    text.append(body, cursor, body.length)
    flush()

    return InlineSplit(segments, attachments.filter { it.blobRef !in used })
}

/// [body] with every inline ref (outside code) replaced by its caption
/// text — for surfaces that show a body as plain text with no attachment
/// views (a list row's one-line preview, a chat marker's reply block), so
/// they never show the raw `![caption](attachment:ref)` markup.
fun inlineAttachmentRefsAsCaptions(body: String): String {
    if (!body.contains("](attachment:")) return body
    val code = codeRegions(body)
    return INLINE_ATTACHMENT_REF.replace(body) { match ->
        if (code.hides(match.range)) match.value else match.groupValues[1]
    }
}

private val LEADING_BLANK_LINES = Regex("""^(?:[ \t]*\r?\n)+""")

private fun trimSegment(raw: String, startsMidLine: Boolean): String {
    val withoutBlankLines = LEADING_BLANK_LINES.replace(raw, "")
    // Dropping blank lines leaves the text at a line start, where leading
    // spaces are indentation and must survive.
    val head = if (startsMidLine && withoutBlankLines.length == raw.length) {
        withoutBlankLines.trimStart(' ', '\t')
    } else {
        withoutBlankLines
    }
    return head.trimEnd()
}

/// Where [body] is code: [fenced] marks every character of a fenced code
/// block; [spans] are the inline code spans outside them, backticks included.
private class CodeRegions(val fenced: BooleanArray, val spans: List<IntRange>) {
    /// Whether a ref at [ref] is literal: it sits in a fenced block (a ref
    /// never spans lines, so its start decides), a code span covers its
    /// start, or a span opens inside it and runs past its end. A span wholly
    /// inside the caption does not hide it (spec addendum, vector 8).
    fun hides(ref: IntRange): Boolean =
        fenced[ref.first] || spans.any { span ->
            (span.first < ref.first && ref.first <= span.last) ||
                (span.first in ref && span.last > ref.last)
        }
}

/// Finds the code in [body]: fenced code blocks
/// (a line starting with ``` or ~~~ through the closing fence — a line
/// starting with a run of the same character at least as long — or the end)
/// and inline code spans outside them.
private fun codeRegions(body: String): CodeRegions {
    val mask = BooleanArray(body.length)
    val prose = mutableListOf<IntRange>()
    var fence: String? = null
    fun fenceRun(line: String): String? {
        val c = line.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val run = line.takeWhile { it == c }
        return run.takeIf { it.length >= 3 }
    }
    var proseStart = 0
    var i = 0
    while (i < body.length) {
        val newline = body.indexOf('\n', i)
        val lineEnd = if (newline < 0) body.length else newline + 1
        val line = body.substring(i, lineEnd)
        if (fence == null) {
            val opener = fenceRun(line)
            if (opener != null) {
                fence = opener
                if (i > proseStart) prose += proseStart until i
                for (k in i until lineEnd) mask[k] = true
            }
        } else {
            for (k in i until lineEnd) mask[k] = true
            val closer = fenceRun(line)
            if (closer != null && closer[0] == fence[0] && closer.length >= fence.length) {
                fence = null
                proseStart = lineEnd
            }
        }
        i = lineEnd
    }
    if (fence == null && proseStart < body.length) prose += proseStart until body.length
    val spans = mutableListOf<IntRange>()
    for (range in prose) collectCodeSpans(body, range, spans)
    return CodeRegions(mask, spans)
}

/// A run of N backticks opens a code span that closes at the next run of
/// exactly N backticks; a run with no closer is ordinary text.
private fun collectCodeSpans(body: String, range: IntRange, spans: MutableList<IntRange>) {
    fun runLength(from: Int): Int {
        var n = 0
        while (from + n <= range.last && body[from + n] == '`') n++
        return n
    }
    var j = range.first
    while (j <= range.last) {
        if (body[j] != '`') { j++; continue }
        val n = runLength(j)
        var k = j + n
        var close = -1
        while (k <= range.last) {
            if (body[k] == '`') {
                val m = runLength(k)
                if (m == n) { close = k; break }
                k += m
            } else {
                k++
            }
        }
        if (close < 0) {
            j += n
        } else {
            spans += j until close + n
            j = close + n
        }
    }
}
