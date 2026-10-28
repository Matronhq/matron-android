package chat.matron.android.designsystem

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import chat.matron.android.journal.TrackerLinkInfo
import chat.matron.android.journal.TrackerLinkTarget
import chat.matron.android.journal.TrackerLinkTitleMatch
import chat.matron.android.journal.TrackerLinkTitles

/// The session's tracker link lookups, installed by the signed-in shell.
/// `null` (previews, tests, signed out) renders links exactly as before.
val LocalTrackerLinkTitles = compositionLocalOf<TrackerLinkTitles?> { null }

/// What follows a tracker link that does not already name its target: the
/// target's kind and title. [mismatch]: the link's text names another title or
/// number, or the number does not exist.
data class TrackerLinkChip(val text: String, val mismatch: Boolean)

/// [normal] for a chip that just informs, [mismatch] for one that warns.
data class TrackerLinkChipStyle(val normal: SpanStyle, val mismatch: SpanStyle)

/// One tracker link in parsed text: its target, its text (empty for a bare
/// URL, whose text is the URL itself) and where it ends.
data class TrackerLinkSpan(val target: TrackerLinkTarget, val text: String, val end: Int)

object TrackerLinkChips {

    /// The chip for a link, or `null` when there is nothing worth saying (the
    /// journal could not be asked, or the link's text already names its
    /// target).
    fun chipFor(info: TrackerLinkInfo, num: Int, linkText: String): TrackerLinkChip? {
        val found = when (info) {
            TrackerLinkInfo.Failed -> return null
            TrackerLinkInfo.Missing -> return TrackerLinkChip("not found", mismatch = true)
            is TrackerLinkInfo.Found -> info
        }
        val kind = found.kind.wire
        val written = TrackerLinkTitleMatch.writtenLink(linkText)
        val wrongNumber = written.num != null && written.num != num
        val title = found.title ?: return TrackerLinkChip(kind, mismatch = wrongNumber)
        // The link already says what it points at: a chip would only repeat it.
        if (!wrongNumber && written.title.isNotEmpty() && TrackerLinkTitleMatch.titleMatches(written.title, title)) return null
        // Link text that names something ("#12 Some title", or a few words)
        // and does not match is flagged; "here" or "read more" is not a
        // claim about the target.
        val mismatch = wrongNumber || (TrackerLinkTitleMatch.claims(written) && written.title.isNotEmpty())
        return TrackerLinkChip("$kind · $title", mismatch)
    }

    /// The tracker links in [text], in order, from its `URL` annotations.
    fun links(text: AnnotatedString, target: (String) -> TrackerLinkTarget?): List<TrackerLinkSpan> =
        text.getStringAnnotations("URL", 0, text.length)
            .sortedBy { it.start }
            .mapNotNull { a ->
                val t = target(a.item) ?: return@mapNotNull null
                val label = text.text.substring(a.start, a.end)
                TrackerLinkSpan(t, if (label.trim() == a.item.trim()) "" else label, a.end)
            }

    /// [text] with each tracker link's chip spliced in right after it. The
    /// links' own annotations and styles are untouched (they only move
    /// along), and a chip carries no annotation, so a tap on it is a tap on
    /// plain text. No chips: [text] comes back as it was.
    fun decorate(
        text: AnnotatedString,
        target: (String) -> TrackerLinkTarget?,
        style: TrackerLinkChipStyle,
        chip: (TrackerLinkSpan) -> TrackerLinkChip?,
    ): AnnotatedString {
        val placed = links(text, target).mapNotNull { span -> chip(span)?.let { span to it } }
        if (placed.isEmpty()) return text
        return buildAnnotatedString {
            var cursor = 0
            for ((span, c) in placed) {
                if (span.end < cursor) continue
                append(text.subSequence(cursor, span.end))
                append(" ")
                withStyle(if (c.mismatch) style.mismatch else style.normal) {
                    append(" ")
                    append(if (c.mismatch) "⚠ ${c.text}" else c.text)
                    append(" ")
                }
                cursor = span.end
            }
            append(text.subSequence(cursor, text.length))
        }
    }
}
