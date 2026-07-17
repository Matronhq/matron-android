package chat.matron.android.designsystem

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Ported from the Swift `UsageMetersFormatTests`.
class UsageMetersFormatTest {
    @Test
    fun compactTokens() {
        assertEquals("0", UsageMetersFormat.compactTokens(0))
        assertEquals("999", UsageMetersFormat.compactTokens(999))
        assertEquals("1k", UsageMetersFormat.compactTokens(1_000))
        assertEquals("265k", UsageMetersFormat.compactTokens(265_400))
        assertEquals("1000k", UsageMetersFormat.compactTokens(999_500))
        assertEquals("200k", UsageMetersFormat.compactTokens(200_000))
        assertEquals("1m", UsageMetersFormat.compactTokens(1_000_000))
        assertEquals("1.5m", UsageMetersFormat.compactTokens(1_500_000))
    }

    @Test
    fun spokenTokens() {
        assertEquals("265 thousand", UsageMetersFormat.spokenTokens(265_400))
        assertEquals("1 million", UsageMetersFormat.spokenTokens(1_000_000))
        assertEquals("1.5 million", UsageMetersFormat.spokenTokens(1_500_000))
        assertEquals("500", UsageMetersFormat.spokenTokens(500))
    }

    @Test
    fun barLabelMapping() {
        assertEquals("Session", UsageMetersFormat.barLabel("Session"))
        assertEquals("Week", UsageMetersFormat.barLabel("Week (all models)"))
        assertEquals("Fable", UsageMetersFormat.barLabel("Week (Fable)"))
        assertEquals("Sonnet 5", UsageMetersFormat.barLabel("Week (Sonnet 5)"))
        assertEquals("Something else", UsageMetersFormat.barLabel("Something else"))
        assertEquals("", UsageMetersFormat.barLabel(""))
    }

    @Test
    fun barColorThresholds() {
        assertEquals(UsageMetersFormat.green, UsageMetersFormat.barColor(0))
        assertEquals(UsageMetersFormat.green, UsageMetersFormat.barColor(49))
        assertEquals(UsageMetersFormat.orange, UsageMetersFormat.barColor(50))
        assertEquals(UsageMetersFormat.orange, UsageMetersFormat.barColor(79))
        assertEquals(UsageMetersFormat.red, UsageMetersFormat.barColor(80))
        assertEquals(UsageMetersFormat.red, UsageMetersFormat.barColor(100))
    }

    @Test
    fun resetDisplay() {
        val now = Instant.ofEpochSecond(1_760_000_000)
        val utc = ZoneId.of("UTC")

        // No timestamp -> raw fallback (null raw -> null).
        assertEquals("soon", UsageMetersFormat.resetDisplay(null, "soon", now))
        assertNull(UsageMetersFormat.resetDisplay(null, null, now))

        // Already passed / imminent -> "now".
        assertEquals("now", UsageMetersFormat.resetDisplay(now.minusSeconds(300), null, now, utc))
        assertEquals("now", UsageMetersFormat.resetDisplay(now.plusSeconds(30), null, now, utc))

        // Under an hour -> minutes.
        assertEquals("45m", UsageMetersFormat.resetDisplay(now.plusSeconds(45 * 60), null, now, utc))

        // Under six hours -> XhMM countdown.
        assertEquals("3h20", UsageMetersFormat.resetDisplay(now.plusSeconds(3 * 3600 + 20 * 60L), null, now, utc))
        assertEquals("5h05", UsageMetersFormat.resetDisplay(now.plusSeconds(5 * 3600 + 5 * 60L), null, now, utc))

        // Six hours or more -> weekday + hour in the given time zone.
        // 1_760_000_000 is Thu 2025-10-09 08:53:20 UTC; +3 days -> Sun 08:53 -> "Sun 8am".
        assertEquals("Sun 8am", UsageMetersFormat.resetDisplay(now.plusSeconds(3 * 24 * 3600L), null, now, utc))
    }
}
