package chat.matron.android.designsystem

import chat.matron.android.models.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// Pins the show/hide predicate and label wording for the tap-to-compact strip.
/// Mirrors the Apple `CompactContextBannerTests` so the two clients stay
/// behaviourally identical (same absolute threshold, same copy). Threshold
/// raised 200k → 400k to match apple #176.
class CompactContextBannerTest {
    private fun context(tokens: Int) = SessionStatus.Context(tokens = tokens, window = 1_000_000, pct = 0)

    @Test
    fun threshold_is400k() {
        assertEquals(400_000, COMPACT_HEADER_TOKEN_THRESHOLD)
    }

    @Test
    fun shouldShowCompactHeader_isFalse_whenContextIsNull() {
        assertFalse(shouldShowCompactHeader(null))
    }

    @Test
    fun shouldShowCompactHeader_isFalse_atExactlyThreshold() {
        assertFalse(shouldShowCompactHeader(context(400_000)))
    }

    @Test
    fun shouldShowCompactHeader_isTrue_oneTokenOverThreshold() {
        assertTrue(shouldShowCompactHeader(context(400_001)))
    }

    @Test
    fun shouldShowCompactHeader_isFalse_atOldThreshold() {
        // 200k–400k used to fire the banner; it now stays quiet there.
        assertFalse(shouldShowCompactHeader(context(265_400)))
    }

    @Test
    fun shouldShowCompactHeader_isFalse_wellBelowThreshold() {
        assertFalse(shouldShowCompactHeader(context(50_000)))
    }

    @Test
    fun shouldShowCompactHeader_ignoresWindowSize() {
        // A huge window does not suppress the header — the trigger is absolute.
        val ctx = SessionStatus.Context(tokens = 450_000, window = 1_000_000, pct = 45)
        assertTrue(shouldShowCompactHeader(ctx))
    }

    @Test
    fun title_usesCompactTokens_withoutActionCopy() {
        assertEquals("Large conversation (265k)", compactBannerTitle(265_400))
    }

    @Test
    fun actionLabel_isCompact() {
        assertEquals("Compact", COMPACT_BANNER_ACTION)
    }
}
