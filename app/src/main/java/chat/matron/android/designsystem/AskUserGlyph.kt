package chat.matron.android.designsystem

import java.text.BreakIterator

/// Splits a leading "glyph" (a single non-alphanumeric grapheme — an emoji or
/// symbol such as ✕, ⚡, ✓, 👍) off the front of a choice/answer label so
/// callers can render it in a fixed-width slot and keep the following text
/// aligned across a stack of buttons regardless of glyph width.
///
/// A glyph is recognised only when the label starts with exactly one
/// non-alphanumeric grapheme cluster (so a multi-scalar emoji counts as one)
/// immediately followed by whitespace. Otherwise the whole label comes back as
/// `text` with `glyph` null — covering a plain label ("Other action"), an
/// alphanumeric first char ("1 apple"), a glyph with no trailing space
/// ("⚡Send"), and a label that is nothing but a glyph ("⚡").
///
/// Returns `(glyph, text)`.
fun splitLeadingGlyph(label: String): Pair<String?, String> {
    if (label.isEmpty()) return null to label

    // First grapheme cluster (BreakIterator so a surrogate-pair emoji is one).
    val breaker = BreakIterator.getCharacterInstance()
    breaker.setText(label)
    val firstEnd = breaker.next()
    if (firstEnd == BreakIterator.DONE || firstEnd <= 0) return null to label

    val firstCodePoint = label.codePointAt(0)
    if (Character.isWhitespace(firstCodePoint) || Character.isLetterOrDigit(firstCodePoint)) {
        return null to label
    }

    // Whole label is the glyph (no following char).
    if (firstEnd >= label.length) return null to label

    val first = label.substring(0, firstEnd)
    val rest = label.substring(firstEnd)
    if (!Character.isWhitespace(rest.codePointAt(0))) return null to label

    val text = rest.trimStart()
    if (text.isEmpty()) return null to label
    return first to text
}
