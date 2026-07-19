package chat.matron.android.designsystem

import chat.matron.android.models.SessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactContextBannerTest {
    private fun context(tokens: Int) = SessionStatus.Context(tokens = tokens, window = 1_000_000, pct = 0)

    @Test
    fun shouldShowCompactHeader_isFalse_whenContextIsNull() {
        assertFalse(shouldShowCompactHeader(null))
    }

    @Test
    fun shouldShowCompactHeader_isFalse_atExactlyThreshold() {
        assertFalse(shouldShowCompactHeader(context(COMPACT_HEADER_TOKEN_THRESHOLD)))
    }

    @Test
    fun shouldShowCompactHeader_isTrue_oneTokenOverThreshold() {
        assertTrue(shouldShowCompactHeader(context(COMPACT_HEADER_TOKEN_THRESHOLD + 1)))
    }

    @Test
    fun shouldShowCompactHeader_ignoresWindowSize() {
        // A huge window does not suppress the header — the trigger is absolute.
        val ctx = SessionStatus.Context(tokens = 250_000, window = 1_000_000, pct = 25)
        assertTrue(shouldShowCompactHeader(ctx))
    }
}
