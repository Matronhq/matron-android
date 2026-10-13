package chat.matron.android.journal

import kotlinx.serialization.json.JsonObject

// The `/lookup` route's shapes: "what is #N?". Items, missions, milestones
// and projects share one per-user number space, so a bare number says
// nothing about where to open it; the journal answers with the kind and id.
// The route implementation lives on [JournalApi] (its request plumbing is
// private), which implements [NumberLookupProviding]; the decoder is pure so
// tests pin the shape without a server.

/// What a number names. A kind this build does not know decodes to `null`
/// in [NumberLookupDecoding.kind], which fails the lookup rather than
/// guessing a screen for it.
enum class NumberKind(val wire: String, val label: String) {
    ITEM("item", "Item"),
    MISSION("mission", "Mission"),
    MILESTONE("milestone", "Milestone"),
    PROJECT("project", "Project"),
}

/// One `GET /lookup` answer. Everything but [kind] and [id] is optional:
/// older journals omit [num] and [title], and [missionID] / [missionNum]
/// appear only for a milestone, naming the mission whose page shows it.
/// [mergedFrom] is set when the number asked for was folded into this one.
data class NumberLookup(
    val kind: NumberKind,
    val id: String,
    val num: Int? = null,
    val title: String? = null,
    val missionID: String? = null,
    val missionNum: Int? = null,
    val mergedFrom: String? = null,
    val ownerUserID: Long? = null,
    val ownerName: String? = null,
)

/// `GET /lookup?user=<login name>&num=<n>`. 404 ([JournalApiError.NotFound])
/// means no such number, and is also what a journal predating the route
/// answers. [JournalApi] implements it; tests fake it.
interface NumberLookupProviding {
    suspend fun lookup(user: String, num: Int): NumberLookup
}

object NumberLookupDecoding {
    fun kind(raw: String?): NumberKind? = NumberKind.entries.firstOrNull { it.wire == raw }

    /// `null` when the object has no known `kind` or no non-empty `id` —
    /// without both there is nothing to open. Empty optional strings are
    /// read as absent so a caller never renders a blank title.
    fun lookup(obj: JsonObject?): NumberLookup? {
        obj ?: return null
        val kind = kind(obj.stringOrNull("kind")) ?: return null
        val id = obj.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
        val mission = obj.objectOrNull("mission")
        val owner = obj.objectOrNull("owner")
        return NumberLookup(
            kind = kind,
            id = id,
            num = obj.intOrNull("num"),
            title = obj.stringOrNull("title")?.takeIf { it.isNotBlank() },
            missionID = mission?.stringOrNull("id")?.takeIf { it.isNotEmpty() },
            missionNum = mission?.intOrNull("num"),
            mergedFrom = obj.stringOrNull("merged_from")?.takeIf { it.isNotEmpty() },
            ownerUserID = owner?.longOrNull("user_id"),
            ownerName = owner?.stringOrNull("name")?.takeIf { it.isNotEmpty() },
        )
    }
}
