package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Pins the pure [splitLeadingGlyph] helper that drives glyph/text alignment
/// across a stack of ask-user answer buttons. Ported from the Swift
/// `AskUserGlyphTests`.
class AskUserGlyphTest {
    @Test
    fun glyphFollowedBySpaceSplits() {
        val (glyph, text) = splitLeadingGlyph("✕ Cancel")
        assertEquals("✕", glyph)
        assertEquals("Cancel", text)
    }

    @Test
    fun singleScalarSymbolGlyphSplits() {
        val (glyph, text) = splitLeadingGlyph("⚡ Send now")
        assertEquals("⚡", glyph)
        assertEquals("Send now", text)
    }

    @Test
    fun multiScalarEmojiGlyphSplits() {
        val (glyph, text) = splitLeadingGlyph("👍 Approve")
        assertEquals("👍", glyph)
        assertEquals("Approve", text)
    }

    @Test
    fun noGlyphReturnsWholeLabel() {
        val (glyph, text) = splitLeadingGlyph("Other action")
        assertNull(glyph)
        assertEquals("Other action", text)
    }

    @Test
    fun alphanumericFirstCharReturnsWholeLabel() {
        val (glyph, text) = splitLeadingGlyph("1 apple")
        assertNull(glyph)
        assertEquals("1 apple", text)
    }

    @Test
    fun glyphWithNoFollowingSpaceReturnsWholeLabel() {
        val (glyph, text) = splitLeadingGlyph("⚡Send")
        assertNull(glyph)
        assertEquals("⚡Send", text)
    }

    @Test
    fun wholeLabelIsGlyphReturnsWholeLabel() {
        val (glyph, text) = splitLeadingGlyph("⚡")
        assertNull(glyph)
        assertEquals("⚡", text)
    }
}
