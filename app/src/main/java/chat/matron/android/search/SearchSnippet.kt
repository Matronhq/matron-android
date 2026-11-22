package chat.matron.android.search

/// What the user typed, split into words the way the journal's search reads
/// them: a message matches when it contains every word; only the word still
/// being typed (the last one, when the text does not end in a space) also
/// matches as the start of a longer word. Words with no letter or digit
/// (a bare `++`) are dropped. Ported from matron-apple's `SearchQuery`
/// (the parts the preview needs).
data class SearchTerms(val terms: List<String>, val lastTermIsPrefix: Boolean) {
    companion object {
        fun parse(text: String): SearchTerms? {
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val kept = words.filter { word -> word.any { it.isLetterOrDigit() } }
            if (kept.isEmpty()) return null
            // A dropped trailing word (punctuation only) still means the word
            // before it is finished.
            val endsInSpace = text.lastOrNull()?.isWhitespace() ?: true
            return SearchTerms(kept, lastTermIsPrefix = !endsInSpace && words.last() == kept.last())
        }
    }
}

/// Builds the preview line for a search hit from a message body (or the
/// journal's excerpt of one): a window around the first place the query
/// appears, the typed words wrapped in `<mark>…</mark>` — the markup
/// [chat.matron.android.designsystem.SearchResultRow] renders. The highlight
/// is what was typed, and the preview opens on the phrase when the message
/// has it. Ported from matron-apple's `SearchSnippet`.
object SearchSnippet {
    /// Characters of context kept before the first match.
    internal const val LEADING_CONTEXT = 40

    /// Total characters of body shown.
    internal const val WINDOW_LENGTH = 160

    private data class Needle(val text: String, val wholeWord: Boolean)

    fun make(body: String, query: SearchTerms): String {
        // One line, and no markup of its own: a message that quotes `<mark>`
        // must not be read as a highlight.
        val flat = body.replace("<mark>", "").replace("</mark>", "")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val needles = query.terms.mapIndexed { i, term ->
            Needle(term, wholeWord = !(query.lastTermIsPrefix && i == query.terms.lastIndex))
        }
        val phrase = Needle(query.terms.joinToString(" "), wholeWord = !query.lastTermIsPrefix)
        val anchor = first(phrase, flat, 0, flat.length)?.first
            ?: needles.mapNotNull { occurrences(it, flat, 0, flat.length).firstOrNull()?.first }.minOrNull()

        var start = 0
        if (anchor != null && anchor > LEADING_CONTEXT) {
            start = anchor - LEADING_CONTEXT
            // Open on a whole word.
            val space = flat.indexOf(' ', start)
            if (space in start until anchor) start = space + 1
        }
        val end = minOf(flat.length, start + WINDOW_LENGTH)

        val result = StringBuilder(if (start > 0) "…" else "")
        var cursor = start
        for (range in highlightRanges(flat, start, end, needles)) {
            result.append(flat, cursor, range.first)
            result.append("<mark>").append(flat, range.first, range.last + 1).append("</mark>")
            cursor = range.last + 1
        }
        result.append(flat, cursor, end)
        if (end < flat.length) result.append("…")
        return result.toString()
    }

    /// Where [needle] appears in `text[from, to)`. A finished word that
    /// appears only inside a longer one ("time" through "times") falls back
    /// to those word starts, so the row still shows why it matched.
    private fun occurrences(needle: Needle, text: String, from: Int, to: Int): List<IntRange> {
        fun all(n: Needle): List<IntRange> {
            val found = mutableListOf<IntRange>()
            var at = from
            while (true) {
                val range = first(n, text, at, to) ?: break
                found.add(range)
                at = range.last + 1
            }
            return found
        }
        val strict = all(needle)
        if (strict.isNotEmpty() || !needle.wholeWord) return strict
        return all(needle.copy(wholeWord = false))
    }

    /// Every occurrence of every word in the window, merged where they
    /// overlap or sit side by side (a phrase is marked as the phrase).
    private fun highlightRanges(text: String, from: Int, to: Int, needles: List<Needle>): List<IntRange> {
        val ranges = needles.flatMap { occurrences(it, text, from, to) }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null &&
                (range.first <= last.last + 1 || text.substring(last.last + 1, range.first).isBlank())
            ) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    /// The first place [needle] appears in `text[from, to)` at the start of a
    /// word (and, for a whole-word needle, ending one), ignoring case.
    /// Mid-word occurrences are skipped: the index matches words and word
    /// prefixes, never infixes.
    private fun first(needle: Needle, text: String, from: Int, to: Int): IntRange? {
        if (needle.text.isEmpty()) return null
        var at = from
        while (at < to) {
            val i = text.indexOf(needle.text, at, ignoreCase = true)
            if (i < 0) return null
            val endExclusive = i + needle.text.length
            if (endExclusive > to) return null
            val startsWord = i == 0 || !text[i - 1].isLetterOrDigit()
            val endsWord = endExclusive == text.length || !text[endExclusive].isLetterOrDigit()
            if (startsWord && (endsWord || !needle.wholeWord)) return i until endExclusive
            at = i + 1
        }
        return null
    }
}
