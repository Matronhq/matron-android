package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/// Pure formatting for the usage/context meters — kept off the composables so
/// the label mapping, countdown wording, and thresholds unit-test without
/// rendering. Thresholds mirror the bridge's /usage colours (usage-limits.js
/// percentColor): green < 50, orange < 80, red >= 80.
object UsageMetersFormat {
    /// Bar colours (iOS system green/orange/red). Referenced by the tests, so
    /// [barColor] and the assertions stay in lockstep.
    val green: Color = MatronGreen
    val orange: Color = MatronOrange
    val red: Color = MatronRed

    /// 265_400 -> "265k", 1_000_000 -> "1m", 1_500_000 -> "1.5m".
    fun compactTokens(n: Int): String {
        if (n < 1000) return "$n"
        if (n < 1_000_000) return "${(n / 1000.0).roundToInt()}k"
        val millions = (n / 1_000_000.0 * 10).roundToLong() / 10.0
        return if (millions == kotlin.math.floor(millions)) "${millions.toInt()}m"
        else String.format(Locale.US, "%.1fm", millions)
    }

    /// VoiceOver / TalkBack variant: "265 thousand", "1 million".
    fun spokenTokens(n: Int): String {
        if (n < 1000) return "$n"
        if (n < 1_000_000) return "${(n / 1000.0).roundToInt()} thousand"
        val millions = (n / 1_000_000.0 * 10).roundToLong() / 10.0
        return if (millions == kotlin.math.floor(millions)) "${millions.toInt()} million"
        else String.format(Locale.US, "%.1f million", millions)
    }

    /// "Session" -> "Session"; "Week (all models)" -> "Week"; any other label
    /// ending in a parenthesised name -> the inner name, so an upstream model
    /// rename never needs an app change.
    fun barLabel(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.endsWith(")")) return trimmed
        val open = trimmed.lastIndexOf('(')
        if (open == -1) return trimmed
        val inner = trimmed.substring(open + 1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return trimmed
        return if (inner.lowercase() == "all models") trimmed.substring(0, open).trim() else inner
    }

    fun barColor(percent: Int): Color = when {
        percent < 50 -> green
        percent < 80 -> orange
        else -> red
    }

    /// Reset time for a bar's trailing text. Near resets read as a countdown,
    /// far ones as local weekday + hour; no timestamp falls back to the raw
    /// text the bridge scraped.
    fun resetDisplay(
        resetsAt: Instant?,
        raw: String?,
        now: Instant,
        timeZone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        resetsAt ?: return raw
        val intervalSeconds = resetsAt.epochSecond - now.epochSecond
        if (intervalSeconds < 60) return "now"
        val totalMinutes = (intervalSeconds / 60).toInt()
        if (intervalSeconds < 3600) return "${totalMinutes}m"
        if (intervalSeconds < 6 * 3600) {
            return String.format(Locale.US, "%dh%02d", totalMinutes / 60, totalMinutes % 60)
        }
        val zdt = resetsAt.atZone(timeZone)
        val dow = zdt.format(DateTimeFormatter.ofPattern("EEE", Locale.US))
        val hour = zdt.hour
        val period = if (hour < 12) "am" else "pm"
        val h12 = ((hour + 11) % 12) + 1
        return "$dow $h12$period"
    }
}
