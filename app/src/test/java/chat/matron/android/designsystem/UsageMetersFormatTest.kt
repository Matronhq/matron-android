package chat.matron.android.designsystem

import androidx.compose.ui.unit.dp
import chat.matron.android.models.SessionStatus
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

    // MARK: barFillWidth (matron-apple #98 port)

    @Test
    fun barFillWidthGuardsNearEmptyAndNearFull() {
        // Compact scale: 90dp bar, 3dp capsule.
        assertEquals(0.dp, UsageMetersFormat.barFillWidth(0, 90.dp, 3.dp))
        assertEquals(45.dp, UsageMetersFormat.barFillWidth(50, 90.dp, 3.dp))
        // Nonzero fills never shrink below the capsule's own diameter.
        assertEquals(3.dp, UsageMetersFormat.barFillWidth(1, 90.dp, 3.dp))
        // Near-full bars keep a capsule-height sliver of track visible —
        // 95% of 90dp is 85.5dp but caps at 87dp.
        assertEquals(85.5.dp, UsageMetersFormat.barFillWidth(95, 90.dp, 3.dp))
        assertEquals(87.dp, UsageMetersFormat.barFillWidth(99, 90.dp, 3.dp))
        // Only a true 100% (or beyond) touches the far edge.
        assertEquals(90.dp, UsageMetersFormat.barFillWidth(100, 90.dp, 3.dp))
        assertEquals(90.dp, UsageMetersFormat.barFillWidth(120, 90.dp, 3.dp))
        assertEquals(0.dp, UsageMetersFormat.barFillWidth(-5, 90.dp, 3.dp))
        // Regular scale: 160dp bar, 6dp capsule — cap is 154dp.
        assertEquals(154.dp, UsageMetersFormat.barFillWidth(99, 160.dp, 6.dp))
    }

    // MARK: homeAbbreviated + vitalsLine (matron-apple #90 port)

    @Test
    fun homeAbbreviatedHandlesMacAndLinuxHomes() {
        assertEquals("~/Dev/matron-apple", UsageMetersFormat.homeAbbreviated("/Users/dan/Dev/matron-apple"))
        assertEquals("~/work", UsageMetersFormat.homeAbbreviated("/home/dan/work"))
        assertEquals("~", UsageMetersFormat.homeAbbreviated("/Users/dan"))
        assertEquals("/opt/matron", UsageMetersFormat.homeAbbreviated("/opt/matron"))
        assertEquals("/Users/", UsageMetersFormat.homeAbbreviated("/Users/"))
    }

    @Test
    fun vitalsLineJoinsKnownHalvesAndDropsWhenEmpty() {
        assertEquals("CPU 12% · RAM 63%", UsageMetersFormat.vitalsLine(SessionStatus.Vitals(12, 63)))
        assertEquals("RAM 63%", UsageMetersFormat.vitalsLine(SessionStatus.Vitals(null, 63)))
        assertEquals("CPU 12%", UsageMetersFormat.vitalsLine(SessionStatus.Vitals(12, null)))
        assertNull(UsageMetersFormat.vitalsLine(SessionStatus.Vitals(null, null)))
    }
}
