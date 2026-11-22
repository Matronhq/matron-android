package chat.matron.android.models

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/// One finished weekly window of a usage meter: what was left unused when it
/// reset. Times are epoch ms.
data class UsageWeek(
    val resetsAt: Long,
    /// 0 to 100: the allowance left when the week reset.
    val unused: Int,
    /// The highest reading of the week, as a percentage used.
    val peak: Int,
    /// When the last reading before the reset was taken.
    val lastReadingAt: Long?,
)

/// The week still running: what is left so far.
data class UsageWeekInProgress(val resetsAt: Long, val unused: Int, val asOf: Long?)

/// One weekly meter of an account ("Week (all models)", "Week (Example)").
data class UsageMeter(
    val id: String,
    val label: String,
    /// The account's headline meter.
    val isPrimary: Boolean,
    val current: UsageWeekInProgress?,
    /// Finished weeks, newest first.
    val weeks: List<UsageWeek>,
)

/// One login's allowance. Boxes sharing a login are one account.
data class UsageAccount(
    val id: String,
    /// "claude" or "codex".
    val agent: String,
    /// Null for a box that reports no login: it is then its own account.
    val email: String?,
    val boxes: List<String>,
    val meters: List<UsageMeter>,
) {
    /// The login, else the box, else a stand-in for a box since removed.
    val title: String get() = email ?: boxes.firstOrNull() ?: "Removed box"

    /// "Claude · 3 boxes", or "Codex" for a box that is its own account.
    val subtitle: String
        get() {
            val name = if (agent == "codex") "Codex" else "Claude"
            if (email == null || boxes.isEmpty()) return name
            return "$name · ${boxes.size} ${if (boxes.size == 1) "box" else "boxes"}"
        }
}

/// `GET /usage/history`: every account's weekly meters over the last weeks.
/// Port of matron-apple's `UsageHistory`.
data class UsageHistory(val accounts: List<UsageAccount>) {
    companion object {
        /// Null when the reply has no `accounts` list. A malformed account,
        /// meter or week is skipped, not the whole reply.
        fun parse(obj: JsonObject): UsageHistory? {
            val rows = obj.arrayOrNull("accounts")?.objects() ?: return null
            return UsageHistory(rows.mapNotNull(::parseAccount))
        }

        private fun parseAccount(row: JsonObject): UsageAccount? {
            val key = row.stringOrNull("key")?.takeIf { it.isNotEmpty() } ?: return null
            val boxes = row.arrayOrNull("boxes")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            return UsageAccount(
                id = key,
                agent = row.stringOrNull("agent") ?: "claude",
                email = row.stringOrNull("email")?.takeIf { it.isNotEmpty() },
                boxes = boxes.orEmpty(),
                meters = row.arrayOrNull("meters")?.objects().orEmpty().mapNotNull(::parseMeter),
            )
        }

        private fun parseMeter(row: JsonObject): UsageMeter? {
            val id = row.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
            val weeks = row.arrayOrNull("windows")?.objects().orEmpty().mapNotNull { week ->
                val resetsAt = week.instant("resets_at") ?: return@mapNotNull null
                val unused = week.whole("unused")?.toInt() ?: return@mapNotNull null
                UsageWeek(
                    resetsAt = resetsAt,
                    unused = clamp(unused),
                    peak = clamp(week.whole("peak")?.toInt() ?: (100 - unused)),
                    lastReadingAt = week.whole("last_at"),
                )
            }
            val current = row.objectOrNull("current")?.let { now ->
                val resetsAt = now.instant("resets_at") ?: return@let null
                val unused = now.whole("unused")?.toInt() ?: return@let null
                UsageWeekInProgress(resetsAt, clamp(unused), now.whole("as_of"))
            }
            return UsageMeter(
                id = id,
                label = row.stringOrNull("label")?.takeIf { it.isNotEmpty() } ?: id,
                isPrimary = row.boolOrNull("primary") ?: false,
                current = current,
                weeks = weeks.sortedByDescending { it.resetsAt },
            )
        }

        private fun clamp(percent: Int) = percent.coerceIn(0, 100)

        /// A whole JSON number: a fraction, a string or a boolean reads as absent.
        private fun JsonObject.whole(key: String): Long? {
            val p = this[key] as? JsonPrimitive ?: return null
            if (p is JsonNull || p.isString) return null
            return p.content.toLongOrNull()
        }

        private fun JsonObject.instant(key: String): Long? =
            stringOrNull(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
}

/// Which meter the usage history shows: each account's headline meter, or
/// one of the model meters by its label.
data class UsageMeterChoice(val id: String, val title: String) {
    val isPrimary: Boolean get() = id == PRIMARY_ID

    companion object {
        const val PRIMARY_ID = "primary"
        val primary = UsageMeterChoice(PRIMARY_ID, "All models")
    }
}

/// The usage history as a grid: an account per row, a calendar week per
/// column. Off the views so it unit-tests. Port of matron-apple's
/// `UsageHistoryTable`.
data class UsageHistoryTable(
    /// The Monday (00:00 UTC, epoch ms) each column's week starts on, oldest first.
    val columns: List<Long>,
    val rows: List<Row>,
    /// The mean unused across accounts per column; null where no account has a reading.
    val columnAverages: List<Int?>,
) {
    data class Row(
        val id: String,
        val title: String,
        val subtitle: String,
        /// One per column, oldest first; null for a week with no reading.
        val cells: List<UsageWeek?>,
        val current: UsageWeekInProgress?,
        /// The mean unused over the weeks shown; null when there are none.
        val averageUnused: Int?,
    ) {
        /// The newest finished week on show.
        val latest: UsageWeek? get() = cells.lastOrNull { it != null }

        /// The column [latest] sits in; null when there is none. Only the
        /// last column is "last week": after a week with no reading, the
        /// newest reading is older.
        val latestColumn: Int? get() = cells.indexOfLast { it != null }.takeIf { it >= 0 }
        val latestIsLastWeek: Boolean get() = cells.isNotEmpty() && cells.last() != null
    }

    companion object {
        const val DEFAULT_WEEKS = 12
        private const val WEEK_MS = 7 * 86_400_000L

        /// The headline meter first, then each model meter any account has.
        fun choices(history: UsageHistory): List<UsageMeterChoice> {
            val choices = mutableListOf(UsageMeterChoice.primary)
            for (meter in history.accounts.flatMap { it.meters }) {
                if (meter.isPrimary || choices.any { it.id == meter.label }) continue
                choices += UsageMeterChoice(meter.label, shortTitle(meter.label))
            }
            return choices
        }

        /// "Week (Example)" reads "Example"; any other label is kept whole.
        fun shortTitle(label: String): String =
            if (label.startsWith("Week (") && label.endsWith(")")) label.substring(6, label.length - 1) else label

        /// A week sits in the column of the calendar week (Monday to Sunday,
        /// UTC) its reset falls in. An account resets once a week, so it has
        /// at most one finished week per column; if two land in one, the later
        /// reset is shown. Accounts without the chosen meter are left out.
        fun build(
            history: UsageHistory,
            choice: UsageMeterChoice,
            weeks: Int = DEFAULT_WEEKS,
            nowMs: Long = System.currentTimeMillis(),
        ): UsageHistoryTable {
            val thisWeek = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val columns = (maxOf(weeks, 1) - 1 downTo 0).map { thisWeek - it * WEEK_MS }
            val rows = history.accounts.mapNotNull { account ->
                val meter = if (choice.isPrimary) {
                    account.meters.firstOrNull { it.isPrimary }
                } else {
                    account.meters.firstOrNull { !it.isPrimary && it.label == choice.id }
                } ?: return@mapNotNull null
                // Weeks are newest first, so the first match is the later reset.
                val cells = columns.map { start -> meter.weeks.firstOrNull { it.resetsAt >= start && it.resetsAt < start + WEEK_MS } }
                Row(
                    id = account.id,
                    title = account.title,
                    subtitle = account.subtitle,
                    cells = cells,
                    current = meter.current,
                    averageUnused = mean(cells.mapNotNull { it?.unused }),
                )
            }
            val averages = columns.indices.map { index -> mean(rows.mapNotNull { it.cells[index]?.unused }) }
            return UsageHistoryTable(columns, rows, averages)
        }

        private fun mean(values: List<Int>): Int? =
            if (values.isEmpty()) null else (values.sum().toDouble() / values.size).roundToInt()
    }
}
