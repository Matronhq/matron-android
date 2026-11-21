package chat.matron.android.events

import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonObject

/// A bridge notice about a turn the bridge injected into the session — a
/// consent request, a routine fire, a room message, a spawn outcome, a
/// reminder. The bridge publishes it as a `text` event and tags it with
/// `payload.notice = {kind, icon, summary}`; the timeline shows it as one
/// short row (icon, summary, time) that expands to the full `body`. Agent
/// replies never carry the tag, so they keep rendering as text. Ported from
/// matron-apple's `Events/SystemNoticeEvent.swift`.
data class SystemNoticeEvent(
    /// `consent`, `routine`, `room`, `spawn`… — informational; an unknown
    /// kind renders the same way.
    val kind: String,
    /// The emoji the row leads with.
    val icon: String,
    /// One line, at most [SUMMARY_MAX] characters.
    val summary: String,
    /// The notice's full text, shown when the row is expanded.
    val body: String,
) {
    companion object {
        const val SUMMARY_MAX = 200
        const val ICON_MAX = 8
        const val KIND_MAX = 32
        const val FALLBACK_ICON = "ℹ️"
        const val FALLBACK_KIND = "notice"

        /// The tag on a text payload, or `null` when it has none (or an
        /// unusable one) — the event then renders as ordinary text.
        fun parse(payload: JsonObject): SystemNoticeEvent? {
            val notice = payload.objectOrNull("notice") ?: return null
            val summary = oneLine(notice.stringOrNull("summary"), SUMMARY_MAX)
            if (summary.isEmpty()) return null
            val icon = oneLine(notice.stringOrNull("icon"), ICON_MAX)
            val kind = oneLine(notice.stringOrNull("kind"), KIND_MAX)
            return SystemNoticeEvent(
                kind = kind.ifEmpty { FALLBACK_KIND },
                icon = icon.ifEmpty { FALLBACK_ICON },
                summary = summary,
                body = payload.stringOrNull("body") ?: "",
            )
        }

        /// [value] with every run of whitespace folded to one space, capped
        /// at [max] characters (the last one an ellipsis when cut). Counts
        /// code points, so a cut never splits an emoji's surrogate pair.
        internal fun oneLine(value: String?, max: Int): String {
            if (value == null) return ""
            val flat = value.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            val count = flat.codePointCount(0, flat.length)
            if (count <= max) return flat
            return flat.substring(0, flat.offsetByCodePoints(0, max - 1)) + "…"
        }
    }
}
