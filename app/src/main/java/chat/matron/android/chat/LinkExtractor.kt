package chat.matron.android.chat

/// Extracts http(s) links from message bodies for the media browser's Links
/// tab. Port of matron-apple's `LinkExtractor` (apple #142). The Swift
/// original wraps `NSDataDetector`; Android has no JVM-safe analogue
/// (`android.util.Patterns` needs the framework), so this is a small regex +
/// trailing-punctuation trim that resolves the same shapes the ported tests
/// pin: bare URLs, markdown suffixes, and trailing punctuation. The scheme
/// filter is the contract — agent chats are full of file://, mailto: and
/// ssh: strings nobody wants in a link list; the regex only ever matches
/// http/https so they never surface.
///
/// URLs are plain `String`s (not `HttpUrl`/`URI`), matching how attachment
/// URLs flow through the rest of the Android app (`MediaService`,
/// `TimelineItem.Kind.Image.url`).
object LinkExtractor {
    private val urlRegex = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

    /// Trailing characters that read as sentence punctuation, not URL: the
    /// detector-equivalent of NSDataDetector's boundary handling.
    private const val TRAILING_PUNCTUATION = ".,;:!?'\"`*"

    fun links(body: String): List<String> =
        urlRegex.findAll(body).mapNotNull { trimmed(it.value) }.toList()

    /// Strips trailing punctuation and unbalanced closing brackets — a
    /// markdown link's `](https://…)` or a parenthesised aside leaves the
    /// closing bracket glued to the match. A `)`/`]` is kept when the URL
    /// body itself opened one (Wikipedia-style paths).
    private fun trimmed(raw: String): String? {
        var candidate = raw
        while (candidate.isNotEmpty()) {
            val last = candidate.last()
            val drop = when {
                last in TRAILING_PUNCTUATION -> true
                last == ')' -> candidate.count { it == '(' } < candidate.count { it == ')' }
                last == ']' -> candidate.count { it == '[' } < candidate.count { it == ']' }
                else -> false
            }
            if (!drop) break
            candidate = candidate.dropLast(1)
        }
        // A scheme with nothing after it isn't a link.
        val host = candidate.substringAfter("://", missingDelimiterValue = "")
        return candidate.takeIf { host.isNotEmpty() }
    }
}
