package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/// Pure formatter for the date-separator label shown between message clusters
/// from different days. Split out so the labelling logic unit-tests without
/// driving Compose. Output rules (Element / Telegram / iMessage conventions):
///   * same calendar day as `now` -> "Today"
///   * previous calendar day -> "Yesterday"
///   * inside the trailing 7 days -> weekday name ("Tuesday")
///   * older -> localised medium-style date ("24 Feb 2026")
///
/// `zone`/`locale` are injected so tests pin a deterministic timezone/locale.
object DateSeparatorLabel {
    fun format(
        date: Instant,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val dayThen = date.atZone(zone).toLocalDate()
        val dayNow = now.atZone(zone).toLocalDate()

        if (dayThen == dayNow) return "Today"
        if (dayThen == dayNow.minusDays(1)) return "Yesterday"

        // Within the trailing week -> weekday name. Compare start-of-day so a
        // separator written at 23:59 resolves to the right weekday.
        val days = ChronoUnit.DAYS.between(dayThen, dayNow)
        if (days in 1 until 7) {
            return dayThen.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                .let(::capitalizeWeekday)
        }

        // Older — localised medium date ("24 Feb 2026").
        return date.atZone(zone).format(
            mediumDateFormatter(locale)
        )
    }

    /// Some locales lower-case the weekday; the timeline wants it capitalised.
    private fun capitalizeWeekday(name: String): String =
        name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

    @Suppress("unused")
    private val fullWeekdays = DayOfWeek.entries
}

/// Centred capsule label shown between message clusters from different days.
/// Visual weight is deliberately subdued — a separator is a reading aid.
@Composable
fun DateSeparator(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(percent = 50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/// Per-locale cache for the separator's date formatter: every visible day
/// boundary formats on every list-body evaluation, and `ofLocalizedDate`
/// resolves the locale pattern each time it's built (apple #167).
private val mediumDateFormatters = java.util.concurrent.ConcurrentHashMap<java.util.Locale, DateTimeFormatter>()

private fun mediumDateFormatter(locale: java.util.Locale): DateTimeFormatter =
    mediumDateFormatters.getOrPut(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
