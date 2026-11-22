package chat.matron.android.journal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant

// The `/search` route's app modes: the journal's full-text search over every
// message the user has, answered the same on every device. `mode=chats`
// groups matches one row per conversation; `mode=recent` lists matching
// messages newest first (optionally inside one conversation, which is what
// find-in-chat steps through). The route lives on [JournalApi]; the decoder
// is pure so tests pin the shape without a server.

/// One match from `GET /search?mode=recent`.
data class JournalSearchHit(
    val convoID: String,
    val seq: Long,
    val ts: Instant,
    val sender: String,
)

/// One conversation from `GET /search?mode=chats`. [excerpt] is up to about
/// 1 KB of the newest matching message around the first occurrence of the
/// query; the preview line is cut and highlighted from it on the device.
data class JournalSearchChat(
    val convoID: String,
    val count: Int,
    val isExact: Boolean,
    val top: JournalSearchHit,
    val excerpt: String,
)

/// The journal's message search. [JournalApi] implements it; tests fake it.
interface JournalSearching {
    suspend fun searchChats(query: String, limit: Int, excludeSubagents: Boolean): List<JournalSearchChat>

    suspend fun searchRecent(
        query: String,
        convoID: String?,
        limit: Int,
        excludeSubagents: Boolean,
    ): List<JournalSearchHit>
}

object JournalSearchDecoding {
    fun chats(obj: JsonObject?): List<JournalSearchChat> {
        val rows = obj?.arrayOrNull("chats") ?: return emptyList()
        return rows.mapNotNull { element -> runCatching { chat(element.jsonObject) }.getOrNull() }
    }

    fun hits(obj: JsonObject?): List<JournalSearchHit> {
        val rows = obj?.arrayOrNull("hits") ?: return emptyList()
        return rows.mapNotNull { element -> runCatching { hit(element.jsonObject, convoID = null) }.getOrNull() }
    }

    fun chat(obj: JsonObject): JournalSearchChat? {
        val convoID = obj.stringOrNull("convo_id")?.takeIf { it.isNotEmpty() } ?: return null
        val count = obj.intOrNull("count") ?: return null
        val topObj = obj.objectOrNull("top") ?: return null
        val top = hit(topObj, convoID = convoID) ?: return null
        return JournalSearchChat(
            convoID = convoID,
            count = count,
            isExact = obj.boolOrNull("exact") ?: false,
            top = top,
            excerpt = topObj.stringOrNull("excerpt") ?: "",
        )
    }

    /// [convoID] is the enclosing chat's id for a `chats` row's `top`, which
    /// does not repeat it; a `recent` hit carries its own.
    fun hit(obj: JsonObject, convoID: String?): JournalSearchHit? {
        val convo = convoID ?: obj.stringOrNull("convo_id")?.takeIf { it.isNotEmpty() } ?: return null
        val seq = obj.longOrNull("seq") ?: return null
        val ts = obj.longOrNull("ts") ?: return null
        val sender = obj.stringOrNull("sender") ?: return null
        return JournalSearchHit(convoID = convo, seq = seq, ts = Instant.ofEpochMilli(ts), sender = sender)
    }
}
