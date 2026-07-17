package chat.matron.android.designsystem

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/// Coarse-grained relative-time label for chat-list rows:
///   - "now" when < 1 minute ago
///   - "Nm" when < 1 hour
///   - "Nh" when < 24 hours
///   - "Nd" when < 7 days
///   - localised short date otherwise
///
/// Minute resolution is the right granularity for "how stale is this
/// conversation" — replaces a per-second relative clock that read as jittery.
object RelativeMinuteTime {
    fun format(
        source: Instant,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val interval = now.epochSecond - source.epochSecond
        if (interval < 60) return "now"
        if (interval < 3600) return "${interval / 60}m"
        if (interval < 86400) return "${interval / 3600}h"
        if (interval < 86400 * 7) return "${interval / 86400}d"
        return source.atZone(zone).format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
        )
    }
}

/// Self-refreshing minute-resolution relative-time label. Re-evaluates once a
/// minute so "5m" → "6m" transitions stay current without a per-second tick.
@Composable
fun RelativeMinuteTimeView(
    source: Instant,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val label by produceState(RelativeMinuteTime.format(source, Instant.now()), source) {
        while (true) {
            value = RelativeMinuteTime.format(source, Instant.now())
            delay(60_000)
        }
    }
    Text(label, modifier = modifier, style = style)
}
