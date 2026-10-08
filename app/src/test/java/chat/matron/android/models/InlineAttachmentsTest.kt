package chat.matron.android.models

import chat.matron.android.models.InlineSegment.Attachment
import chat.matron.android.models.InlineSegment.Text
import org.junit.Assert.assertEquals
import org.junit.Test

/// The shared "inline images" spec's test vectors, plus the edges around
/// them. Every client pins the same seven.
class InlineAttachmentsTest {
    private val aa11 = TrackerAttachment(blobRef = "aa11", mime = "image/png", name = "a.png")
    private val bb22 = TrackerAttachment(blobRef = "bb22", mime = "image/png", name = "b.png")

    @Test
    fun vector1_anImageBetweenParagraphs() {
        val split = splitInlineAttachments("Before\n\n![login](attachment:aa11)\n\nAfter", listOf(aa11, bb22))
        assertEquals(listOf(Text("Before"), Attachment(aa11, "login"), Text("After")), split.segments)
        assertEquals(listOf(bb22), split.trailing)
    }

    @Test
    fun vector2_anUnresolvedRefBecomesItsCaption() {
        val split = splitInlineAttachments("See ![x](attachment:zz99) here", listOf(aa11))
        assertEquals(listOf(Text("See x here")), split.segments)
        assertEquals(listOf(aa11), split.trailing)
    }

    @Test
    fun vector3_aRepeatedRefBecomesItsCaption() {
        val split = splitInlineAttachments("![a](attachment:aa11) and again ![b](attachment:aa11)", listOf(aa11))
        assertEquals(listOf(Attachment(aa11, "a"), Text("and again b")), split.segments)
        assertEquals(emptyList<TrackerAttachment>(), split.trailing)
    }

    @Test
    fun vector4_aRefInAFencedCodeBlockIsLiteral() {
        val body = "```\n![a](attachment:aa11)\n```"
        val split = splitInlineAttachments(body, listOf(aa11))
        assertEquals(listOf(Text(body)), split.segments)
        assertEquals(listOf(aa11), split.trailing)
    }

    @Test
    fun vector5_aRefInAnInlineCodeSpanIsLiteral() {
        val body = "`![a](attachment:aa11)`"
        val split = splitInlineAttachments(body, listOf(aa11))
        assertEquals(listOf(Text(body)), split.segments)
        assertEquals(listOf(aa11), split.trailing)
    }

    @Test
    fun vector6_anEmptyBodyLeavesEveryAttachmentTrailing() {
        val split = splitInlineAttachments("", listOf(aa11, bb22))
        assertEquals(emptyList<InlineSegment>(), split.segments)
        assertEquals(listOf(aa11, bb22), split.trailing)
    }

    @Test
    fun vector7_anEmptyCaptionStillResolves() {
        val split = splitInlineAttachments("![](attachment:aa11)", listOf(aa11))
        assertEquals(listOf(Attachment(aa11)), split.segments)
        assertEquals(emptyList<TrackerAttachment>(), split.trailing)
    }

    @Test
    fun anUnresolvedRefWithAnEmptyCaptionRendersNothing() {
        val split = splitInlineAttachments("A ![](attachment:zz99)B", emptyList())
        assertEquals(listOf(Text("A B")), split.segments)
    }

    @Test
    fun aTildeFenceAndAnUnclosedFenceAreLiteral() {
        val tilde = "~~~\n![a](attachment:aa11)\n~~~\n![b](attachment:bb22)"
        assertEquals(
            listOf(Text("~~~\n![a](attachment:aa11)\n~~~"), Attachment(bb22, "b")),
            splitInlineAttachments(tilde, listOf(aa11, bb22)).segments,
        )
        val unclosed = "```\n![a](attachment:aa11)"
        assertEquals(listOf(aa11), splitInlineAttachments(unclosed, listOf(aa11)).trailing)
    }

    @Test
    fun anUnmatchedBacktickDoesNotHideARef() {
        val split = splitInlineAttachments("a ` b ![x](attachment:aa11)", listOf(aa11))
        assertEquals(listOf(Text("a ` b"), Attachment(aa11, "x")), split.segments)
    }

    @Test
    fun indentationAfterAnImageLineSurvives() {
        val split = splitInlineAttachments("![a](attachment:aa11)\n    code", listOf(aa11))
        assertEquals(listOf(Attachment(aa11, "a"), Text("    code")), split.segments)
    }

    @Test
    fun nonImageAttachmentsAlsoPlaceInline() {
        val pdf = TrackerAttachment(blobRef = "pdf1", mime = "application/pdf", name = "r.pdf")
        val split = splitInlineAttachments("Report: ![r](attachment:pdf1)", listOf(pdf))
        assertEquals(listOf(Text("Report:"), Attachment(pdf, "r")), split.segments)
    }

    @Test
    fun plainTextSurfacesShowCaptionsNotMarkup() {
        assertEquals("See login here", inlineAttachmentRefsAsCaptions("See ![login](attachment:aa11) here"))
        assertEquals("`![a](attachment:aa11)`", inlineAttachmentRefsAsCaptions("`![a](attachment:aa11)`"))
        assertEquals("plain", inlineAttachmentRefsAsCaptions("plain"))
    }

    @Test
    fun aLongerFenceOnlyClosesOnARunAtLeastAsLong() {
        val body = "````\n```\n![a](attachment:aa11)\n````\n![b](attachment:bb22)"
        val split = splitInlineAttachments(body, listOf(aa11, bb22))
        assertEquals(listOf(Text("````\n```\n![a](attachment:aa11)\n````"), Attachment(bb22, "b")), split.segments)
        assertEquals(listOf(aa11), split.trailing)
        val mixed = "```\n~~~\n![a](attachment:aa11)\n```"
        assertEquals(listOf(aa11), splitInlineAttachments(mixed, listOf(aa11)).trailing)
    }

    @Test
    fun anEmptyCaptionOnlyBodyHasNoPlainText() {
        assertEquals("", inlineAttachmentRefsAsCaptions("![](attachment:aa11)"))
    }

    @Test
    fun vector8_aCodeSpanWhollyInsideTheCaptionDoesNotHideTheRef() {
        val split = splitInlineAttachments("![run `make`](attachment:aa11)", listOf(aa11))
        assertEquals(listOf(Attachment(aa11, "run `make`")), split.segments)
        assertEquals(emptyList<TrackerAttachment>(), split.trailing)
        assertEquals("run `make`", inlineAttachmentRefsAsCaptions("![run `make`](attachment:aa11)"))
    }

    @Test
    fun aSpanOpeningInsideTheRefAndRunningPastItHidesIt() {
        val body = "![a `x](attachment:aa11) y`"
        val split = splitInlineAttachments(body, listOf(aa11))
        assertEquals(listOf(Text(body)), split.segments)
        assertEquals(listOf(aa11), split.trailing)
    }
}
