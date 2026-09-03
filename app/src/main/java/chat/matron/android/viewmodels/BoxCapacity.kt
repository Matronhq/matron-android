package chat.matron.android.viewmodels

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/// One usage-limit meter from a bridge's `limits.lines`
/// (spec: 2026-08-11-chooser-capacity-design.md).
data class LimitLine(val id: String, val label: String, val percent: Int, val resetsAt: Long?)

/// The capacity blocks a bridge attaches to its `recent_folders` reply.
/// Every block is optional wire-side, so parsing degrades per-block and can
/// never fail the folders parse it rides along with. Port of matron-apple's
/// `BoxCapacity`.
data class BoxCapacity(
    val liveSessions: Int?,
    val limitLines: List<LimitLine>,
    val accountEmail: String?,
) {
    companion object {
        /// Reads whatever capacity blocks are present in a `recent_folders`
        /// reply. A missing or wrong-typed block yields null/empty rather than
        /// an error; a malformed line drops that line only.
        fun parse(reply: JsonElement): BoxCapacity {
            val obj = reply as? JsonObject ?: return BoxCapacity(null, emptyList(), null)
            // A negative count is nonsense, not "zero" — treat it as absent.
            val live = obj.objectOrNull("activity")?.intOrNull("live_sessions")?.takeIf { it >= 0 }

            val lines = obj.objectOrNull("limits")?.arrayOrNull("lines")?.objects().orEmpty()
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
            return BoxCapacity(live, lines, email)
        }

        /// "resets 11:59 PM" when the reset falls on today's local date,
        /// "resets Aug 15" otherwise; null when the bridge sent no timestamp.
        fun resetText(
            resetsAt: Long?,
            nowMs: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): String? {
            if (resetsAt == null) return null
            val date = Instant.ofEpochMilli(resetsAt).atZone(zone)
            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            val pattern = if (date.toLocalDate() == now.toLocalDate()) "h:mm a" else "MMM d"
            return "resets " + date.format(DateTimeFormatter.ofPattern(pattern, Locale.US))
        }
    }
}
