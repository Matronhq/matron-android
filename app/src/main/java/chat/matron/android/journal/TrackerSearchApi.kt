package chat.matron.android.journal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// The `/search/all` route's shapes: the journal's tracker-wide search over
// items, missions, memories and projects, best match first, each hit the
// entity a match rolled up to (a milestone's mission, a comment's item).
// The route lives on [JournalApi]; the decoder is pure so tests pin the
// shape without a server.

/// What a hit is. A kind this build does not know is dropped by the
/// decoder rather than guessed at.
enum class TrackerSearchKind(val wire: String) {
    ITEM("item"),
    MISSION("mission"),
    PROJECT("project"),
    MEMORY("memory"),
}

/// One hit. A memory has a [name] and no [num]; its [title] is the
/// memory's one-line description. [why] is the tier it was found in
/// ("number", "exact", "words", "stemmed", "similar", "some_words");
/// [snippet] carries `**…**` match markers.
data class TrackerSearchHit(
    val kind: TrackerSearchKind,
    val id: String,
    val num: Int? = null,
    val name: String? = null,
    val title: String,
    val state: String? = null,
    val why: String = "exact",
    val snippet: String? = null,
)

/// `GET /search/all?q=…&kinds=…&chats=0`. [JournalApi] implements it; tests fake it.
interface TrackerSearching {
    suspend fun searchAll(query: String, kinds: Set<TrackerSearchKind>, limit: Int = 30): List<TrackerSearchHit>
}

object TrackerSearchDecoding {
    fun hits(obj: JsonObject?): List<TrackerSearchHit> {
        val results = obj?.arrayOrNull("results") ?: return emptyList()
        return results.mapNotNull { element -> runCatching { hit(element.jsonObject) }.getOrNull() }
    }

    fun hit(obj: JsonObject): TrackerSearchHit? {
        val kind = TrackerSearchKind.entries.firstOrNull { it.wire == obj.stringOrNull("kind") } ?: return null
        val id = obj.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
        val title = obj.stringOrNull("title") ?: return null
        return TrackerSearchHit(
            kind = kind,
            id = id,
            num = obj.intOrNull("num"),
            name = obj.stringOrNull("name")?.takeIf { it.isNotEmpty() },
            title = title,
            state = obj.stringOrNull("state"),
            why = obj.stringOrNull("why") ?: "exact",
            snippet = obj.stringOrNull("snippet")?.takeIf { it.isNotEmpty() },
        )
    }

    /// The journal runs a hit's own name and title into its snippet
    /// ("**Postgres** 16 migration Move the app…"). The row already shows the
    /// title, so drop those leading words (markers and all); a snippet that
    /// is only the title is `null`. The web's `snippetBeyondTitle`.
    fun snippetBeyondTitle(snippet: String, heads: List<String?>): String? {
        var rest = snippet
        for (head in heads) {
            if (head.isNullOrBlank()) continue
            val words = head.trim().split(Regex("\\s+"))
            var cursor = rest.trimStart()
            var matched = 0
            for (word in words) {
                val bare = cursor.removePrefix("**")
                if (!bare.lowercase().startsWith(word.lowercase())) break
                cursor = bare.substring(word.length).removePrefix("**").trimStart()
                matched += 1
            }
            if (matched == words.size) rest = cursor
        }
        rest = rest.trim()
        return rest.takeUnless { it.isEmpty() || it == "\u2026" }
    }

    /// The tiers a person should be told about; the strict ones need no label.
    fun whyLabel(why: String): String? = when (why) {
        "stemmed" -> "similar words"
        "similar" -> "similar meaning"
        "some_words" -> "some words"
        else -> null
    }
}
