package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `ToolStreamCardSliceTests.swift` (Apple PR #130).
/// Pins the collapsed-pane tail cap ([collapsedSlice]): a collapsed card must
/// not re-parse + re-lay-out the full 64 KiB stream tail on every append
/// (Apple's 2026-08-10 main-thread stalls), so it renders only the last
/// [COLLAPSED_DISPLAY_CAP_CHARS], opened at a line boundary, and reports the
/// cut so the truncation notice shows. Pure string function — no Compose rig.
class ToolStreamCardSliceTest {
    /// Port of Apple's `test_shortText_passesThroughUncut`.
    @Test
    fun shortTextPassesThroughUncut() {
        val (text, cut) = collapsedSlice("line one\nline two\n")
        assertEquals("line one\nline two\n", text)
        assertFalse(cut)
    }

    /// Port of Apple's `test_textAtCap_passesThroughUncut`.
    @Test
    fun textAtCapPassesThroughUncut() {
        val exact = "x".repeat(COLLAPSED_DISPLAY_CAP_CHARS)
        val (text, cut) = collapsedSlice(exact)
        assertEquals(exact.length, text.length)
        assertFalse(cut)
    }

    /// Port of Apple's `test_longText_cutsToCapAndOpensAtLineBoundary`.
    @Test
    fun longTextCutsToCapAndOpensAtLineBoundary() {
        // 200 numbered 40-char lines ≈ 8 KiB — over the 4 KiB cap.
        val lines = (0 until 200).map { "%04d ".format(it) + "a".repeat(35) }
        val (text, cut) = collapsedSlice(lines.joinToString("\n"))
        assertTrue(cut)
        assertTrue(text.length <= COLLAPSED_DISPLAY_CAP_CHARS)
        // Opens on a complete line: the cap lands mid-line, and the partial
        // line up to the next newline is dropped.
        assertTrue("expected a full numbered line at the head, got: ${text.take(12)}…", text.startsWith("0"))
        // The tail (newest output) is always preserved verbatim.
        assertTrue(text.endsWith(lines.last()))
    }

    /// Port of Apple's `test_singleGiantLine_keepsCapWorthOfTail`.
    @Test
    fun singleGiantLineKeepsCapWorthOfTail() {
        // No newline anywhere in the suffix — nothing to trim to, keep the cap.
        val giant = "y".repeat(3 * COLLAPSED_DISPLAY_CAP_CHARS)
        val (text, cut) = collapsedSlice(giant)
        assertTrue(cut)
        assertEquals(COLLAPSED_DISPLAY_CAP_CHARS, text.length)
    }

    /// Port of Apple's `test_newlineOnlyAtSliceEnd_neverEmptiesTheSlice`
    /// (Bugbot, Apple PR #130): a giant line whose ONLY newline lands at the
    /// very end of the suffix must not be trimmed into emptiness — the trim
    /// scan is bounded to the head of the slice.
    @Test
    fun newlineOnlyAtSliceEndNeverEmptiesTheSlice() {
        val giant = "z".repeat(3 * COLLAPSED_DISPLAY_CAP_CHARS) + "\n"
        val (text, cut) = collapsedSlice(giant)
        assertTrue(cut)
        assertTrue(text.length > COLLAPSED_DISPLAY_CAP_CHARS / 2)
    }

    /// Port of Apple's `test_lateNewline_isNotTrimmedThrough`: a newline
    /// beyond the 512-char scan window means the visible tail is one
    /// effectively-giant line; trimming through it would drop most of the
    /// pane's content, so it stays.
    @Test
    fun lateNewlineIsNotTrimmedThrough() {
        val cap = COLLAPSED_DISPLAY_CAP_CHARS
        val text = "a".repeat(cap) + "\n" + "b".repeat(600)
        // Suffix = tail of the a-run + "\n" + 600 b's: the newline sits ~600
        // chars from the END, i.e. past the head scan window.
        val (shown, cut) = collapsedSlice(text)
        assertTrue(cut)
        assertEquals(cap, shown.length)
        assertTrue("the late newline is kept, not trimmed through", shown.contains("\n"))
    }
}
