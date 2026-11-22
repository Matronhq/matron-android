package chat.matron.android.models

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.doubleOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/// What a triggered routine fires on instead of a clock (journal
/// "Coordinator routines → Triggers"). Each subject — a session or a box —
/// fires once per crossing. Ported from matron-apple's `RoutineTrigger`.
sealed interface RoutineTrigger {
    /// A live session's context gauge at or past [pct] (1–99) of its window.
    data class ContextOver(val pct: Int) : RoutineTrigger

    /// A session stalled on a usage limit whose reset is at least
    /// [resetMinutes] (0–10080) away, or unknown.
    data class Stalled(val resetMinutes: Int) : RoutineTrigger

    /// An agent box with under [pct] (1–99) percent free disk.
    data class DiskUnder(val pct: Int) : RoutineTrigger

    /// A weekly limit that resets between [fromHours] and [toHours] from now
    /// with under [pct] (1–99) percent used. The window is kept exactly as
    /// received (`null` when the journal sent none), so an edit of the
    /// percent sends it back unchanged.
    data class AllowanceExpiring(val pct: Int, val fromHours: Double? = null, val toHours: Double? = null) : RoutineTrigger

    /// The tiered form of the same trigger: met by any tier — at least
    /// [Tier.unusedPct] of the meter left with its reset at most
    /// [Tier.hoursLeft] away. The editor shows it but does not change it.
    data class AllowanceTiers(val tiers: List<Tier>) : RoutineTrigger {
        data class Tier(val unusedPct: Int, val hoursLeft: Int)
    }

    enum class Kind(val wire: String, val label: String) {
        CONTEXT_OVER("context_over", "Session context"),
        STALLED("stalled", "Stalled session"),
        DISK_UNDER("disk_under", "Low disk"),
        ALLOWANCE_EXPIRING("allowance_expiring", "Spare weekly allowance");

        /// The value a new trigger of this kind starts with: the starter
        /// routines' thresholds.
        val defaultTrigger: RoutineTrigger
            get() = when (this) {
                CONTEXT_OVER -> ContextOver(40)
                STALLED -> Stalled(120)
                DISK_UNDER -> DiskUnder(20)
                ALLOWANCE_EXPIRING -> AllowanceExpiring(70)
            }

        /// The journal's ranges (`validateTrigger`).
        val thresholdRange: IntRange
            get() = if (this == STALLED) 0..RoutineRules.STALLED_MAX_MINUTES else 1..99

        companion object {
            fun fromWire(raw: String?): Kind? = entries.firstOrNull { it.wire == raw }
        }
    }

    val kind: Kind
        get() = when (this) {
            is ContextOver -> Kind.CONTEXT_OVER
            is Stalled -> Kind.STALLED
            is DiskUnder -> Kind.DISK_UNDER
            is AllowanceExpiring, is AllowanceTiers -> Kind.ALLOWANCE_EXPIRING
        }

    /// The trigger's one number: a percentage, or minutes for [Stalled].
    /// `null` for the tiered form, which has several.
    val threshold: Int?
        get() = when (this) {
            is ContextOver -> pct
            is Stalled -> resetMinutes
            is DiskUnder -> pct
            is AllowanceExpiring -> pct
            is AllowanceTiers -> null
        }

    /// Whether the editor can change this trigger's threshold.
    val isEditable: Boolean get() = this !is AllowanceTiers

    fun toJson(): JsonObject = when (this) {
        is ContextOver -> buildJsonObject { put("kind", kind.wire); put("pct", pct) }
        is Stalled -> buildJsonObject { put("kind", kind.wire); put("reset_minutes", resetMinutes) }
        is DiskUnder -> buildJsonObject { put("kind", kind.wire); put("pct", pct) }
        // The window goes back only as the journal sent it. The journal
        // keeps whole hours, so a whole value goes back as an integer.
        is AllowanceExpiring -> buildJsonObject {
            put("kind", kind.wire); put("pct", pct)
            fromHours?.let { put("from_hours", hoursJson(it)) }
            toHours?.let { put("to_hours", hoursJson(it)) }
        }
        is AllowanceTiers -> buildJsonObject {
            put("kind", kind.wire)
            put(
                "tiers",
                JsonArray(tiers.map { buildJsonObject { put("unused_pct", it.unusedPct); put("hours_left", it.hoursLeft) } }),
            )
        }
    }

    /// "When a session passes 40% of its context window", "When a box drops
    /// under 20% free disk", and so on.
    val words: String
        get() = when (this) {
            is ContextOver -> "When a session passes $pct% of its context window"
            is Stalled ->
                if (resetMinutes <= 0) "When a session stalls on a usage limit"
                else "When a session stalls on a usage limit with no reset within ${duration(resetMinutes)}"
            is DiskUnder -> "When a box drops under $pct% free disk"
            is AllowanceExpiring ->
                "When a weekly limit resets within ${hours(fromHours ?: DEFAULT_FROM_HOURS)}–${hours(toHours ?: DEFAULT_TO_HOURS)} h and is under $pct%"
            is AllowanceTiers ->
                "When a weekly limit has " +
                    joinWords(tiers.map { "${it.unusedPct}% left within ${duration(it.hoursLeft * 60)}" }, "or") +
                    " of its reset"
        }

    companion object {
        const val DEFAULT_FROM_HOURS = 6.0
        const val DEFAULT_TO_HOURS = 12.0

        fun make(kind: Kind, threshold: Int): RoutineTrigger = when (kind) {
            Kind.CONTEXT_OVER -> ContextOver(threshold)
            Kind.STALLED -> Stalled(threshold)
            Kind.DISK_UNDER -> DiskUnder(threshold)
            Kind.ALLOWANCE_EXPIRING -> AllowanceExpiring(threshold)
        }

        /// `null` for a kind this build doesn't know or a row missing its
        /// number: the routine decoder drops it rather than guessing.
        fun fromJson(json: JsonObject): RoutineTrigger? {
            return when (Kind.fromWire(json.stringOrNull("kind")) ?: return null) {
                Kind.STALLED -> Stalled(json.intOrNull("reset_minutes") ?: 120)
                Kind.CONTEXT_OVER -> ContextOver(json.intOrNull("pct") ?: return null)
                Kind.DISK_UNDER -> DiskUnder(json.intOrNull("pct") ?: return null)
                Kind.ALLOWANCE_EXPIRING -> {
                    val tiers = json.arrayOrNull("tiers")
                    if (tiers != null) {
                        val parsed = tiers.objects().map { t ->
                            AllowanceTiers.Tier(
                                unusedPct = t.intOrNull("unused_pct") ?: return null,
                                hoursLeft = t.intOrNull("hours_left") ?: return null,
                            )
                        }
                        if (parsed.isEmpty() || parsed.size != tiers.size) null else AllowanceTiers(parsed)
                    } else {
                        AllowanceExpiring(
                            pct = json.intOrNull("pct") ?: return null,
                            fromHours = json.doubleOrNull("from_hours"),
                            toHours = json.doubleOrNull("to_hours"),
                        )
                    }
                }
            }
        }

        /// 6 → "6", 1.5 → "1.5".
        fun hours(value: Double): String =
            if (value == Math.floor(value) && !value.isInfinite() && kotlin.math.abs(value) < 1e15) value.toLong().toString()
            else value.toString()

        private fun hoursJson(value: Double): JsonPrimitive =
            if (value == Math.floor(value) && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

        /// "45 min", "2 h", "1 h 30 min", "3 d".
        fun duration(minutes: Int): String {
            if (minutes < 60) return "$minutes min"
            if (minutes % 1440 == 0) return "${minutes / 1440} d"
            val h = minutes / 60
            val rest = minutes % 60
            return if (rest == 0) "$h h" else "$h h $rest min"
        }
    }
}

/// "a", "a and b", "a, b and c" (or "or").
internal fun joinWords(parts: List<String>, conjunction: String = "and"): String = when (parts.size) {
    0 -> ""
    1 -> parts[0]
    else -> parts.dropLast(1).joinToString(", ") + " $conjunction " + parts.last()
}

/// One Coordinator routine (journal `GET /routines`): a prompt the journal
/// fires into the Coordinator conversation on a schedule or when a trigger
/// trips. Scheduled or triggered for life: exactly one of [schedule] and
/// [trigger] is set, and an edit can never swap one for the other. Ported
/// from matron-apple's `Routine`.
data class Routine(
    val id: String,
    /// Slug, unique per user, not editable.
    val name: String,
    val title: String,
    /// Five cron fields, evaluated in [tz]. `null` for a triggered routine.
    val schedule: String? = null,
    val trigger: RoutineTrigger? = null,
    val tz: String = RoutineRules.DEFAULT_ZONE,
    val prompt: String,
    val enabled: Boolean = true,
    /// `seed`, `user` or `agent`.
    val origin: String = "user",
    /// The next scheduled fire; `null` while paused, and always for a
    /// triggered routine.
    val nextAt: Instant? = null,
    val lastFiredAt: Instant? = null,
    /// `applied now`, `applied deferred`, `failed <code>`, `no_coordinator`
    /// or `missed`.
    val lastOutcome: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val isTriggered: Boolean get() = trigger != null

    /// What sets it off, in words: "Every day at 07:05 (Europe/London)", or
    /// the trigger's sentence.
    val whenWords: String
        get() {
            trigger?.let { return it.words }
            val schedule = schedule ?: return ""
            return "${CronWords.describe(schedule) ?: "Cron $schedule"} ($tz)"
        }

    /// The last fire's outcome for the list: "Delivered", "Queued for the
    /// next idle point", "Missed", "Not delivered: no Coordinator box",
    /// "Failed: agent_unreachable".
    val lastOutcomeWords: String?
        get() {
            val outcome = lastOutcome?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                outcome == "applied now" -> "Delivered"
                outcome == "applied deferred" -> "Queued for the next idle point"
                outcome == "missed" -> "Missed"
                outcome == "no_coordinator" -> "Not delivered: no Coordinator box"
                outcome.startsWith("failed") -> {
                    val code = outcome.removePrefix("failed").trim()
                    if (code.isEmpty()) "Failed" else "Failed: $code"
                }
                else -> outcome
            }
        }

    /// Whether the last fire did not reach the Coordinator.
    val lastFireFailed: Boolean
        get() {
            val outcome = lastOutcome ?: return false
            return outcome == "missed" || outcome == "no_coordinator" || outcome.startsWith("failed")
        }

    companion object {
        /// `null` when a field the list needs is missing, or when the row has
        /// neither a schedule nor a trigger this build understands.
        fun fromJson(json: JsonObject): Routine? {
            val id = json.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
            val name = json.stringOrNull("name")?.takeIf { it.isNotEmpty() } ?: return null
            val title = json.stringOrNull("title") ?: return null
            val prompt = json.stringOrNull("prompt") ?: return null
            val createdAt = json.longOrNull("created_at")?.let(Instant::ofEpochMilli) ?: return null
            val updatedAt = json.longOrNull("updated_at")?.let(Instant::ofEpochMilli) ?: return null
            val schedule = json.stringOrNull("schedule")?.takeIf { it.isNotEmpty() }
            val trigger = json.objectOrNull("trigger")?.let(RoutineTrigger::fromJson)
            if ((schedule == null) == (trigger == null)) return null
            return Routine(
                id = id, name = name, title = title, schedule = schedule, trigger = trigger,
                tz = json.stringOrNull("tz") ?: RoutineRules.DEFAULT_ZONE, prompt = prompt,
                enabled = json.boolOrNull("enabled") ?: true,
                origin = json.stringOrNull("origin") ?: "user",
                nextAt = json.longOrNull("next_at")?.let(Instant::ofEpochMilli),
                lastFiredAt = json.longOrNull("last_fired_at")?.let(Instant::ofEpochMilli),
                lastOutcome = json.stringOrNull("last_outcome"),
                createdAt = createdAt, updatedAt = updatedAt,
            )
        }
    }
}

/// The fields a create or edit sends. On an edit, `null` leaves a field
/// alone; [schedule] and [trigger] go only to the kind the routine already
/// is.
data class RoutineDraft(
    val title: String,
    val prompt: String,
    val tz: String,
    val schedule: String? = null,
    val trigger: RoutineTrigger? = null,
    val enabled: Boolean? = null,
) {
    /// The request body. A triggered routine sends no zone: it has no clock.
    fun requestBody(): JsonObject = buildJsonObject {
        put("title", title.trim())
        put("prompt", prompt.trim())
        schedule?.let {
            put("schedule", it.trim().split(Regex("\\s+")).joinToString(" "))
            put("tz", tz)
        }
        trigger?.let { put("trigger", it.toJson()) }
        enabled?.let { put("enabled", it) }
    }
}

/// The journal's routine rules (`src/routines.js`), restated so the editor
/// refuses a bad value with a reason before any request goes out. The
/// journal answers a bare 400 for every one of them.
object RoutineRules {
    const val MAX_ROUTINES = 50
    const val TITLE_MAX = 200
    const val PROMPT_MAX = 2000
    const val STALLED_MAX_MINUTES = 7 * 24 * 60
    const val DEFAULT_ZONE = "Europe/London"
    /// Consecutive fires at least this far apart.
    const val MIN_GAP_MINUTES = 15

    private val NAME_RE = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

    fun isValidName(name: String): Boolean = NAME_RE.matches(name)

    fun isValidZone(tz: String): Boolean {
        if (tz.isEmpty() || tz.length > 64) return false
        return try {
            ZoneId.of(tz); true
        } catch (_: DateTimeException) {
            false
        }
    }

    /// Why this schedule can't be sent, or `null`. Checks the shape and the
    /// cases where every minute field fires too often; the journal checks
    /// the real spacing over the next five fires.
    fun scheduleError(schedule: String): String? {
        val fields = schedule.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (fields.size != 5) return "A schedule is five cron fields: minute, hour, day of month, month and day of week."
        val minute = fields[0]
        val tooOften = when {
            minute == "*" -> true
            minute.startsWith("*/") -> minute.drop(2).toIntOrNull()?.let { it < MIN_GAP_MINUTES } ?: false
            else -> false
        }
        return if (tooOften) "A routine fires at most every $MIN_GAP_MINUTES minutes." else null
    }

    /// Why this form can't be saved, or `null`.
    fun formError(name: String, title: String, prompt: String, tz: String, schedule: String?, trigger: RoutineTrigger?): String? {
        if (!isValidName(name)) {
            return "The name must be lowercase letters, digits and dashes, start with a letter or digit, and be at most 64 characters."
        }
        val t = title.trim()
        if (t.isEmpty()) return "Give the routine a title."
        if (t.length > TITLE_MAX) return "The title can be at most $TITLE_MAX characters."
        if (t.any { it == '\n' || it == '\r' || it == ' ' || it == ' ' }) return "The title must be one line."
        val p = prompt.trim()
        if (p.isEmpty()) return "Write the prompt the Coordinator receives."
        if (p.length > PROMPT_MAX) return "The prompt can be at most $PROMPT_MAX characters."
        if (schedule != null) {
            if (!isValidZone(tz)) return "\"$tz\" is not a time zone. Use a name such as Europe/London."
            scheduleError(schedule)?.let { return it }
        }
        if (trigger != null) {
            val threshold = trigger.threshold
            val range = trigger.kind.thresholdRange
            if (threshold != null && threshold !in range) {
                return if (trigger.kind == RoutineTrigger.Kind.STALLED) "The reset window is 0 to ${range.last} minutes."
                else "The threshold is a percentage from ${range.first} to ${range.last}."
            }
        }
        return null
    }
}

/// Plain words for the five-field cron the journal fires routines on —
/// enough for the shapes people write ("5 7 * * *", "0 */2 * * *",
/// "30 18 * * 1-5"). `null` for anything it can't say plainly; the caller
/// shows the raw fields instead. Ported from matron-apple's `CronWords`.
object CronWords {
    private val dayNames = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val monthNames = listOf(
        "January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December",
    )

    fun describe(schedule: String): String? {
        val f = schedule.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (f.size != 5) return null
        val (minute, hour, dom, month, dow) = f
        val (timeText, isInterval) = timeWords(minute, hour) ?: return null
        var days = when {
            dom == "*" && dow == "*" -> "Every day"
            dom == "*" -> weekdayWords(dow) ?: return null
            dow == "*" -> {
                val d = numberList(dom, 1..31) ?: return null
                "On day ${joinWords(d.map { it.toString() })} of the month"
            }
            else -> return null
        }
        if (month != "*") {
            val m = numberList(month, 1..12) ?: return null
            days += " in " + joinWords(m.map { monthNames[it - 1] })
        }
        // "Every 2 hours, every day" reads badly: drop "every day" there.
        if (days == "Every day" && isInterval) return timeText.replaceFirstChar { it.uppercase() }
        return "$days $timeText"
    }

    private fun timeWords(minute: String, hour: String): Pair<String, Boolean>? {
        if (minute.startsWith("*/") && hour == "*") {
            val step = minute.drop(2).toIntOrNull()
            if (step != null && step > 0) return "every $step minutes" to true
        }
        val minutes = numberList(minute, 0..59) ?: return null
        if (hour == "*") {
            if (minutes.size != 1) return null
            return (if (minutes[0] == 0) "every hour, on the hour" else "every hour at ${minutes[0]} past") to true
        }
        if (hour.startsWith("*/") && minutes.size == 1) {
            val step = hour.drop(2).toIntOrNull()
            if (step != null && step > 0) {
                val at = if (minutes[0] == 0) "on the hour" else "at ${minutes[0]} past"
                return (if (step == 1) "every hour, $at" else "every $step hours, $at") to true
            }
        }
        val hours = numberList(hour, 0..23) ?: return null
        val times = hours.flatMap { h -> minutes.map { m -> "%02d:%02d".format(h, m) } }
        if (times.size > 6) return null
        return "at " + joinWords(times) to false
    }

    private fun weekdayWords(field: String): String? {
        if (field == "1-5") return "Monday to Friday"
        if (field == "0,6" || field == "6,0" || field == "6-7") return "Saturday and Sunday"
        val r = field.split("-")
        if (r.size == 2) {
            val a = r[0].toIntOrNull()
            val b = r[1].toIntOrNull()
            if (a != null && b != null && a in 0..7 && b in 0..7 && a < b) return "${dayNames[a % 7]} to ${dayNames[b % 7]}"
        }
        val days = numberList(field, 0..7) ?: return null
        return "Every " + joinWords(days.map { dayNames[it % 7] })
    }

    /// "5", "8,17", "1-3", "8,12-14" → the sorted numbers; `null` for steps,
    /// names or anything out of range.
    private fun numberList(field: String, range: IntRange): List<Int>? {
        val out = mutableListOf<Int>()
        for (part in field.split(",")) {
            val bounds = part.split("-")
            if (bounds.size == 1) {
                val n = bounds[0].toIntOrNull()?.takeIf { it in range } ?: return null
                out.add(n)
            } else if (bounds.size == 2) {
                val a = bounds[0].toIntOrNull()
                val b = bounds[1].toIntOrNull()
                if (a == null || b == null || a !in range || b !in range || a > b || b - a >= 24) return null
                out.addAll(a..b)
            } else {
                return null
            }
        }
        return out.toSortedSet().toList().ifEmpty { null }
    }
}
