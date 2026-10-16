package chat.matron.android.models

import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/// Who wrote something into a mission someone else owns: a contact holding a
/// `contribute` grant, by hand or through one of their agents (protocol.md
/// *Contributors*, "Attribution"). The journal sends it as `contributor:
/// {name, via}` on items, comments and milestones, and leaves the key out
/// everywhere else — so an absent key decodes to null.
data class Contributor(val name: String, val via: Via) {
    enum class Via(val wire: String) {
        USER("user"),
        AGENT("agent"),
        ;

        companion object {
            fun parse(raw: String?): Via? = entries.firstOrNull { it.wire == raw }
        }
    }

    /// "bob" or "bob's agent".
    val label: String get() = if (via == Via.AGENT) "$name's agent" else name

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("via", via.wire)
    }

    companion object {
        /// Decodes `contributor`; null when absent or nameless. An unknown
        /// `via` reads as the person themself.
        fun fromJson(json: JsonObject?): Contributor? {
            val name = json?.stringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Contributor(name, Via.parse(json.stringOrNull("via")) ?: Via.USER)
        }

        /// Reads `contributor` off a row.
        fun on(row: JsonObject): Contributor? = fromJson(row.objectOrNull("contributor"))
    }
}

/// The "by …" line for a row someone else wrote: the journal's own
/// `attribution` words when present (a hand-over copy carries only those),
/// else the contributor's. Null when neither is set — the row is the
/// owner's own.
fun attributionLine(contributor: Contributor?, attribution: String?): String? {
    attribution?.trim()?.takeIf { it.isNotEmpty() }?.let { return "by $it" }
    return contributor?.let { "by ${it.label}" }
}

/// "Status by bob" under a mission status a contributor set; null when the
/// owner (or their agent) set it.
fun missionStatusByline(statusContributor: String?): String? =
    statusContributor?.trim()?.takeIf { it.isNotEmpty() }?.let { "Status by $it" }
