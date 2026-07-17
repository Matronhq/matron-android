package chat.matron.android.designsystem

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/// Pins [DateSeparatorLabel.format] so the chat timeline's separator copy
/// doesn't silently drift across locales / timezones. Injects an explicit
/// zone + locale so the assertions don't depend on the host runtime. Ported
/// from the Swift `DateSeparatorLabelTests`.
class DateSeparatorLabelTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val locale: Locale = Locale.UK

    /// Wednesday 2026-03-04 12:00 UTC.
    private val now: Instant = Instant.parse("2026-03-04T12:00:00Z")

    private fun label(date: Instant): String =
        DateSeparatorLabel.format(date, now, zone, locale)

    @Test
    fun today() {
        assertEquals("Today", label(now.minus(3, ChronoUnit.HOURS)))
    }

    @Test
    fun yesterday() {
        assertEquals("Yesterday", label(now.minus(1, ChronoUnit.DAYS)))
    }

    @Test
    fun weekdayWithinLastSevenDays() {
        // Three days back from Wednesday is the previous Sunday.
        assertEquals("Sunday", label(now.minus(3, ChronoUnit.DAYS)))
    }

    @Test
    fun weekdayAtSixDayBoundaryStillWeekday() {
        // Six full days back from Wednesday 2026-03-04 = Thursday 2026-02-26.
        assertEquals("Thursday", label(now.minus(6, ChronoUnit.DAYS)))
    }

    @Test
    fun olderThanWeekFallsBackToLocalisedDate() {
        // Eight days back → 2026-02-24, past the trailing-week window.
        assertEquals("24 Feb 2026", label(now.minus(8, ChronoUnit.DAYS)))
    }
}
