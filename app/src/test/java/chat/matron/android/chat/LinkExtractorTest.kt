package chat.matron.android.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Pins link extraction for the Links tab: the scheme filter and multi-URL
/// behavior. Ported from matron-apple's `LinkExtractorTests` (apple #142) —
/// the Swift suite asserts `hasPrefix` where NSDataDetector's punctuation
/// handling varies by OS build; the Kotlin extractor is deterministic, so the
/// markdown case pins the exact trimmed URL as well.
class LinkExtractorTest {
    /// Ports apple #142 `testBareURL`.
    @Test
    fun bareURL() {
        assertEquals(
            listOf("https://example.com/app"),
            LinkExtractor.links("deployed to https://example.com/app"),
        )
    }

    /// Ports apple #142 `testMarkdownAndTrailingPunctuation`.
    @Test
    fun markdownAndTrailingPunctuation() {
        val links = LinkExtractor.links("see [docs](https://example.com/docs). Done.")
        assertEquals(1, links.size)
        assertTrue(links[0].startsWith("https://example.com/docs"))
        assertEquals("https://example.com/docs", links[0])
    }

    /// Ports apple #142 `testNonHTTPSchemesRejected`.
    @Test
    fun nonHTTPSchemesRejected() {
        assertEquals(
            emptyList<String>(),
            LinkExtractor.links("mail me mailto:a@b.c or ftp://files.example"),
        )
    }

    /// Ports apple #142 `testMultipleURLsKeepDocumentOrder`.
    @Test
    fun multipleURLsKeepDocumentOrder() {
        assertEquals(
            listOf("https://a.example", "http://b.example"),
            LinkExtractor.links("first https://a.example then http://b.example"),
        )
    }

    /// Ports apple #142 `testPlainTextYieldsNothing`.
    @Test
    fun plainTextYieldsNothing() {
        assertEquals(emptyList<String>(), LinkExtractor.links("http on its own is not a link"))
    }

    /// Android-side addition: a bare scheme with nothing after it must not
    /// surface (the regex alone would match "https://" glued to whitespace
    /// trimming edge cases).
    @Test
    fun bareSchemeYieldsNothing() {
        assertEquals(emptyList<String>(), LinkExtractor.links("broken https:// link"))
    }
}
