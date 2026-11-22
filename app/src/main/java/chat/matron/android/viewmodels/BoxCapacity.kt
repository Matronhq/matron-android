package chat.matron.android.viewmodels

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/// One usage-limit meter from a bridge's `limits.lines`
/// (spec: 2026-08-11-chooser-capacity-design.md).
data class LimitLine(val id: String, val label: String, val percent: Int, val resetsAt: Long?)

/// A box's own capacity report as the journal stores it (journal PR #82):
/// the bridge sends `box_status` on every hello, after each usage-limits
/// refresh and at shutdown, and the journal serves the latest one as
/// `status` on `GET /devices` and fans it live as a `box_status` frame. Same
/// blocks as a `recent_folders` reply, plus when the box reported them
/// ([reportedAtMs], epoch ms) — the journal's clock, so a sleeping box's
/// numbers carry an honest age. Port of matron-apple's `BoxStatus`.
data class BoxStatus(val reportedAtMs: Long, val capacity: BoxCapacity) {
    companion object {
        /// Parses a `status` object or a `box_status` frame (both carry
        /// `reported_at` in epoch ms beside the blocks). Null without a usable
        /// `reported_at` (absent, null, a string, a boolean): an unaged report
        /// can't be captioned honestly, so it is treated as no report at all.
        /// The blocks degrade as in [BoxCapacity.parse].
        fun parse(element: JsonElement): BoxStatus? {
            val obj = element as? JsonObject ?: return null
            val millis = obj.longOrNull("reported_at") ?: return null
            return BoxStatus(millis, BoxCapacity.parse(obj))
        }
    }
}

/// The capacity blocks a bridge attaches to its `recent_folders` reply.
/// Every block is optional wire-side, so parsing degrades per-block and can
/// never fail the folders parse it rides along with. Port of matron-apple's
/// `BoxCapacity`.
data class BoxCapacity(
    val liveSessions: Int?,
    val limitLines: List<LimitLine>,
    val accountEmail: String?,
    /// The `disk` block; null when the bridge sent none.
    val disk: BoxDisk? = null,
    /// `limits.as_of` (epoch ms): when the usage numbers themselves were
    /// read, which can be well before the report that carried them.
    val limitsAsOfMs: Long? = null,
    /// The `defaults` block; null for a bridge predating it.
    val defaults: BoxResolvedDefaults? = null,
) {
    companion object {
        /// Reads whatever capacity blocks are present in a `recent_folders`
        /// reply. A missing or wrong-typed block yields null/empty rather than
        /// an error; a malformed line drops that line only.
        fun parse(reply: JsonElement): BoxCapacity {
            val obj = reply as? JsonObject ?: return BoxCapacity(null, emptyList(), null)
            // A negative count is nonsense, not "zero" — treat it as absent.
            val live = obj.objectOrNull("activity")?.intOrNull("live_sessions")?.takeIf { it >= 0 }

            val limits = obj.objectOrNull("limits")
            val lines = limits?.arrayOrNull("lines")?.objects().orEmpty()
                .mapNotNull { line ->
                    val id = line.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val label = line.stringOrNull("label")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val percent = line.intOrNull("percent") ?: return@mapNotNull null
                    val resetsAt = line.stringOrNull("resets_at")?.let {
                        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                    }
                    LimitLine(id, label, percent.coerceIn(0, 999), resetsAt)
                }

            val email = obj.objectOrNull("account")?.stringOrNull("email")?.takeIf { it.isNotEmpty() }
            return BoxCapacity(
                live, lines, email,
                disk = BoxDisk.parse(obj.objectOrNull("disk")),
                limitsAsOfMs = limits?.wholeLongOrNull("as_of"),
                defaults = BoxResolvedDefaults.parse(obj.objectOrNull("defaults")),
            )
        }

        /// How long until a reset more than six hours off: "in 30h", "in 3d
        /// 4h", so which box's week ends first reads at a glance. Null for an
        /// unknown or nearer reset (its clock time is close enough to read).
        fun resetCountdown(resetsAt: Long?, nowMs: Long = System.currentTimeMillis()): String? {
            if (resetsAt == null) return null
            val interval = resetsAt - nowMs
            if (interval < 6 * 3_600_000L) return null
            val hours = interval / 3_600_000L
            if (hours < 48) return "in ${hours}h"
            val rest = hours % 24
            return "in ${hours / 24}d" + if (rest == 0L) "" else " ${rest}h"
        }

        /// A limit line's whole reset caption: [resetText], then the
        /// countdown when there is one: "resets Aug 15 · in 3d 4h".
        fun resetCaption(
            resetsAt: Long?,
            nowMs: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): String? {
            val text = resetText(resetsAt, nowMs, zone) ?: return null
            return resetCountdown(resetsAt, nowMs)?.let { "$text · $it" } ?: text
        }

        /// True when the line's reset moment is known and already behind
        /// `nowMs`: the limit has rolled over since the bridge cached this
        /// line, so the percent on screen predates the reset. Callers
        /// de-emphasise the stale number instead of presenting it as current.
        fun hasReset(resetsAt: Long?, nowMs: Long = System.currentTimeMillis()): Boolean {
            if (resetsAt == null) return false
            return resetsAt <= nowMs
        }

        /// "resets 11:59 PM" when the reset falls on today's local date,
        /// "resets Aug 15" otherwise. A reset already behind `nowMs` reads
        /// "reset" — showing a past moment as upcoming ("resets 5:30 PM" the
        /// day after) is how stale cache lines used to masquerade as live.
        /// Null when the bridge sent no timestamp, so the caller drops the
        /// trailing text (the limit line still renders).
        fun resetText(
            resetsAt: Long?,
            nowMs: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): String? {
            if (resetsAt == null) return null
            if (resetsAt <= nowMs) return "reset"
            val date = Instant.ofEpochMilli(resetsAt).atZone(zone)
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            val pattern = if (date.toLocalDate() == now.toLocalDate()) "h:mm a" else "MMM d"
            return "resets " + date.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
        }
    }
}

/// A JSON number that has to be whole: byte counts and epoch milliseconds.
/// A fraction, a string or a boolean is a wire contract violation and reads
/// as absent rather than as an invented number.
internal fun JsonObject.wholeLongOrNull(key: String): Long? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.content.toLongOrNull()
}

/// A box's `disk` block: free and total bytes on its working volume.
data class BoxDisk(val freeBytes: Long, val totalBytes: Long) {
    companion object {
        /// Null unless both sizes are whole, non-negative and consistent.
        fun parse(obj: JsonObject?): BoxDisk? {
            val free = obj?.wholeLongOrNull("free_bytes") ?: return null
            val total = obj.wholeLongOrNull("total_bytes") ?: return null
            if (free < 0 || total <= 0 || free > total) return null
            return BoxDisk(free, total)
        }

        /// 1024-based and dense: "7.4G", "233G", "1.8T". One decimal under
        /// ten, whole numbers above; the reader is judging "is this box full".
        fun sizeText(bytes: Long): String {
            val units = listOf("B", "K", "M", "G", "T", "P")
            var value = bytes.toDouble()
            var index = 0
            while (value >= 1024 && index < units.size - 1) {
                value /= 1024
                index += 1
            }
            // The unit is picked before rounding, so 1023.9G must carry to 1.0T.
            if (value >= 1023.5 && index < units.size - 1) {
                value /= 1024
                index += 1
            }
            val number = if (value >= 10 || value == Math.rint(value)) {
                Math.round(value).toString()
            } else {
                String.format(Locale.US, "%.1f", value)
            }
            return number + units[index]
        }
    }
}

/// What a new session on the box starts as, resolved by the bridge (the
/// journal's box defaults, else the box's own environment).
data class BoxResolvedDefaults(val agent: String, val model: String?, val effort: String?) {
    companion object {
        fun parse(obj: JsonObject?): BoxResolvedDefaults? {
            val agent = obj?.stringOrNull("agent")?.takeIf { it.isNotEmpty() } ?: return null
            return BoxResolvedDefaults(agent, obj.stringOrNull("model"), obj.stringOrNull("effort"))
        }
    }
}
