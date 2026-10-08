package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
import chat.matron.android.chat.MediaFetchOutcome
import chat.matron.android.features.chat.FileTap
import chat.matron.android.models.TrackerAttachment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The markdown attachment preview's pure half: which attachments qualify,
/// the sanitiser that keeps remote images from ever loading, the loader and
/// its cache, and the open/replace/close state.
class MarkdownPreviewTest {
    private val mb2 = MarkdownPreview.MAX_BYTES

    private fun previewable(name: String, mime: String?, size: Long? = 100) =
        MarkdownPreview.isPreviewableMarkdown(name, mime, size)

    // MARK: - isPreviewableMarkdown

    @Test
    fun markdownMimesQualifyWhateverTheName() {
        assertTrue(previewable("notes", "text/markdown"))
        assertTrue(previewable("notes.txt", "text/x-markdown"))
        assertTrue(previewable("README", "TEXT/Markdown; charset=utf-8"))
    }

    @Test
    fun markdownExtensionsQualifyUnderGenericMimes() {
        for (name in listOf("a.md", "a.markdown", "a.mdown", "A.MD", "Plan.Markdown")) {
            for (mime in listOf(null, "", "text/plain", "application/octet-stream")) {
                assertTrue("$name / $mime", previewable(name, mime))
            }
        }
    }

    @Test
    fun markdownExtensionUnderASpecificOtherMimeDoesNot() {
        assertFalse(previewable("a.md", "application/pdf"))
        assertFalse(previewable("a.md", "image/png"))
        assertFalse(previewable("a.md", "text/html"))
    }

    @Test
    fun nonMarkdownFilesDoNot() {
        assertFalse(previewable("report.pdf", "application/pdf"))
        assertFalse(previewable("notes.txt", "text/plain"))
        assertFalse(previewable("photo.png", "image/png"))
        assertFalse(previewable("archive.md.zip", "application/zip"))
        assertFalse(previewable("md", null))
        assertFalse(previewable("file.mdx", "text/plain"))
    }

    @Test
    fun sizeCapIsTwoMegabytesInclusive() {
        assertTrue(previewable("a.md", "text/markdown", mb2))
        assertFalse(previewable("a.md", "text/markdown", mb2 + 1))
        assertFalse(previewable("a.md", null, 10 * mb2))
        // Unknown size qualifies; the loader enforces the cap on the bytes.
        assertTrue(previewable("a.md", null, null))
    }

    @Test
    fun trackerAttachmentTreatsZeroSizeAsUnknown() {
        assertTrue(MarkdownPreview.isPreviewableMarkdown(TrackerAttachment("b", "text/markdown", "a.md", 0)))
        assertTrue(MarkdownPreview.isPreviewableMarkdown(TrackerAttachment("b", "application/octet-stream", "a.md", 12)))
        assertFalse(MarkdownPreview.isPreviewableMarkdown(TrackerAttachment("b", "text/markdown", "a.md", mb2 + 1)))
        assertFalse(MarkdownPreview.isPreviewableMarkdown(TrackerAttachment("b", "image/png", "a.png", 12)))
    }

    @Test
    fun chatFileTapUsesTheSamePredicate() {
        assertTrue(FileTap("u", "a.md").isPreviewableMarkdown)
        assertTrue(FileTap("u", "a.md", null, 0).isPreviewableMarkdown)
        assertFalse(FileTap("u", "a.md", null, mb2 + 1).isPreviewableMarkdown)
        assertFalse(FileTap("u", "a.pdf", "application/pdf", 10).isPreviewableMarkdown)
    }

    // MARK: - Sanitising

    private val colors = MarkdownColors(Color.Black, Color.Gray, Color.LightGray, Color.Blue)

    private fun urlsIn(markdown: String): List<String> =
        MarkdownAttributed.parse(markdown, colors).blocks.flatMap { block ->
            block.text.getStringAnnotations("URL", 0, block.text.length).map { it.item }
        }

    @Test
    fun remoteImagesBecomeTheirAltText() {
        val out = MarkdownPreview.sanitise("Look: ![a cat](https://evil.example/cat.png) here")
        assertEquals("Look: a cat here", out)
        assertTrue(urlsIn(out).isEmpty())
    }

    @Test
    fun imagesWithNoAltShowTheirUrlTextAndAreNotLinked() {
        val out = MarkdownPreview.sanitise("![](https://x.example/p.png \"title\")")
        assertEquals("https://x.example/p.png", out)
        assertTrue(urlsIn(out).isEmpty())
    }

    @Test
    fun relativeAndAttachmentImagesNeverRenderAsImages() {
        assertEquals("diagram", MarkdownPreview.sanitise("![diagram](./img/d.png)"))
        assertEquals("shot", MarkdownPreview.sanitise("![shot](attachment:abc123)"))
        assertEquals("x", MarkdownPreview.sanitise("![x](data:image/png;base64,AAAA)"))
    }

    @Test
    fun imageInsideALinkKeepsTheLink() {
        val out = MarkdownPreview.sanitise("[![badge](https://ci.example/b.svg)](https://ci.example)")
        assertEquals("[badge](https://ci.example)", out)
        assertEquals(listOf("https://ci.example"), urlsIn(out))
    }

    @Test
    fun webAndMailLinksStay() {
        val src = "[site](https://a.example) [plain](http://b.example) [mail](mailto:x@y.z) [item](matron://item/5)"
        assertEquals(src, MarkdownPreview.sanitise(src))
        assertEquals(
            listOf("https://a.example", "http://b.example", "mailto:x@y.z", "matron://item/5"),
            urlsIn(MarkdownPreview.sanitise(src)),
        )
    }

    @Test
    fun relativeAnchorAndOtherSchemeLinksBecomePlainText() {
        assertEquals("see other", MarkdownPreview.sanitise("see [other](./other.md)"))
        assertEquals("top", MarkdownPreview.sanitise("[top](#top)"))
        assertEquals("x", MarkdownPreview.sanitise("[x](javascript:alert)"))
        assertEquals("f", MarkdownPreview.sanitise("[f](file:///etc/passwd)"))
        assertTrue(urlsIn(MarkdownPreview.sanitise("[a](../a.md) and [b](b.md)")).isEmpty())
    }

    @Test
    fun nestedBracketsCannotSmuggleARelativeLink() {
        val out = MarkdownPreview.sanitise("[a [b](./rel)](./other)")
        assertTrue(urlsIn(out).isEmpty())
    }

    @Test
    fun codeIsLeftLiteral() {
        val fenced = "```\n![x](https://e.example/a.png)\n[y](./z)\n```\nafter ![i](https://e.example/i.png)"
        assertEquals("```\n![x](https://e.example/a.png)\n[y](./z)\n```\nafter i", MarkdownPreview.sanitise(fenced))
        val inline = "use `![x](https://e.example/a.png)` but ![y](https://e.example/b.png)"
        assertEquals("use `![x](https://e.example/a.png)` but y", MarkdownPreview.sanitise(inline))
    }

    @Test
    fun tildeFencesAreNotCodeToTheRendererSoTheyAreSanitised() {
        val src = "~~~\n![x](https://e.example/a.png) [y](./z)\n~~~"
        assertEquals("~~~\nx y\n~~~", MarkdownPreview.sanitise(src))
        assertTrue(urlsIn(MarkdownPreview.sanitise(src)).isEmpty())
    }

    @Test
    fun unclosedBacktickFenceRunsToTheEndLikeTheRenderer() {
        val src = "  ```kotlin\n![x](https://e.example/a.png)"
        assertEquals(src, MarkdownPreview.sanitise(src))
    }

    @Test
    fun linkAllowListIsSchemeBased() {
        assertTrue(MarkdownPreview.isAllowedLink("HTTPS://a.example"))
        assertTrue(MarkdownPreview.isAllowedLink("mailto:a@b.c"))
        assertFalse(MarkdownPreview.isAllowedLink("./a.md"))
        assertFalse(MarkdownPreview.isAllowedLink("#anchor"))
        assertFalse(MarkdownPreview.isAllowedLink("a.md#x:y"))
        assertFalse(MarkdownPreview.isAllowedLink("javascript:alert(1)"))
        assertFalse(MarkdownPreview.isAllowedLink("attachment:abc"))
    }

    @Test
    fun decodeReplacesInvalidUtf8AndDropsTheBom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte(), 0xFF.toByte(), 'i'.code.toByte())
        assertEquals("h\uFFFDi", MarkdownPreview.decode(bytes))
    }

    // MARK: - Loader and cache

    @Test
    fun loadFetchesOnceThenServesTheCache() = runTest {
        val cache = MarkdownPreviewCache()
        var fetches = 0
        val fetch: suspend () -> MediaFetchOutcome = { fetches++; MediaFetchOutcome.Data("# Hi".toByteArray()) }
        assertEquals(MarkdownPreview.Content.Loaded("# Hi"), MarkdownPreview.load("k", cache, fetch))
        assertEquals(MarkdownPreview.Content.Loaded("# Hi"), MarkdownPreview.load("k", cache, fetch))
        assertEquals(1, fetches)
    }

    @Test
    fun loadOverTheCapFallsBack() = runTest {
        val cache = MarkdownPreviewCache()
        val big = ByteArray((mb2 + 1).toInt()) { 'a'.code.toByte() }
        assertEquals(MarkdownPreview.Content.TooLarge, MarkdownPreview.load("k", cache) { MediaFetchOutcome.Data(big) })
        assertNull(cache["k"])
    }

    @Test
    fun loadFailuresAreNotCached() = runTest {
        val cache = MarkdownPreviewCache()
        assertEquals(MarkdownPreview.Content.Failed(expired = true), MarkdownPreview.load("k", cache) { MediaFetchOutcome.NotFound })
        assertEquals(MarkdownPreview.Content.Failed(expired = false), MarkdownPreview.load("k", cache) { MediaFetchOutcome.Failure })
        assertNull(cache["k"])
    }

    @Test
    fun loadedContentRendersTheSanitisedText() {
        val c = MarkdownPreview.Content.Loaded("![a](https://e.example/a.png)")
        assertEquals("![a](https://e.example/a.png)", c.raw)
        assertEquals("a", c.rendered)
    }

    // MARK: - Panel state

    @Test
    fun stateOpensReplacesAndCloses() {
        val state = MarkdownPreviewState()
        assertNull(state.target)
        state.open(MarkdownPreviewTarget("u1", "a.md"))
        assertEquals("a.md", state.target?.name)
        state.open(MarkdownPreviewTarget("u2", "b.md"))
        assertEquals(MarkdownPreviewTarget("u2", "b.md"), state.target)
        state.close()
        assertNull(state.target)
    }
}
