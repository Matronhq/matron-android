package chat.matron.android.journal

import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import chat.matron.android.models.TrackerLink
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The `/items` routes' request/response shapes (protocol.md "Items →
// Routes"). Ported from matron-apple's `Journal/JournalAPI+Items.swift`; the
// route implementations themselves live on [JournalApi] (its request
// plumbing is private), which implements [ItemsProviding].

data class ItemsListQuery(
    val convoID: String? = null,
    val kind: ItemKind? = null,
    /// Optional and omitted from the query when null — the journal has no
    /// `state=any`; leaving it off means "don't filter by state" server-side.
    val state: ItemState? = null,
    val awaiting: ItemAwaiting? = null,
    val label: String? = null,
    val sort: Sort = Sort.RANK,
    val since: Instant? = null,
    val limit: Int = 100,
    val cursor: String? = null,
) {
    enum class Sort(val wire: String) { RANK("rank"), UPDATED("updated") }

    /// The query string, in the Swift original's order.
    val queryItems: List<Pair<String, String>>
        get() = buildList {
            add("sort" to sort.wire); add("limit" to limit.toString())
            convoID?.let { add("convo" to it) }
            kind?.let { add("kind" to it.wire) }
            state?.let { add("state" to it.wire) }
            awaiting?.let { add("awaiting" to it.wire) }
            label?.let { add("label" to it) }
            since?.let { add("since" to it.toEpochMilli().toString()) }
            cursor?.let { add("cursor" to it) }
        }
}

data class ItemsPage(val items: List<TrackerItem>, val nextCursor: String?)

/// `GET /items/:id`: the item plus its full thread.
data class ItemDetail(val item: TrackerItem, val comments: List<TrackerComment>)

/// `POST /items/:id/comments`: the comment as stored plus the item it left behind.
data class ItemCommentResult(val item: TrackerItem, val comment: TrackerComment)

/// `POST /items` body. [awaiting] is tri-state like the Swift `ItemAwaiting??`:
/// [awaitingSet] false ⇒ key omitted (server default), true with a null
/// [awaiting] ⇒ an explicit `null`.
data class NewItem(
    val kind: ItemKind,
    val title: String,
    val body: String = "",
    val labels: List<String> = emptyList(),
    val links: List<TrackerLink> = emptyList(),
    val attachments: List<TrackerAttachment> = emptyList(),
    val awaiting: ItemAwaiting? = null,
    val awaitingSet: Boolean = false,
    val position: String? = null,
    val convoID: String,
    val supersedes: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.wire); put("title", title); put("body", body); put("convo_id", convoID)
        if (labels.isNotEmpty()) put("labels", buildJsonArray { labels.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        if (links.isNotEmpty()) put("links", buildJsonArray { links.forEach { add(it.toJson()) } })
        if (attachments.isNotEmpty()) put("attachments", buildJsonArray { attachments.forEach { add(it.outgoingJson()) } })
        if (awaitingSet) { if (awaiting != null) put("awaiting", awaiting.wire) else put("awaiting", JsonNull) }
        position?.let { put("position", it) }
        supersedes?.let { put("supersedes", it) }
    }
}

/// `PATCH /items/:id` body: only the fields present are sent.
data class ItemPatch(
    val title: String? = null,
    val body: String? = null,
    val labels: List<String>? = null,
    val links: List<TrackerLink>? = null,
    val awaiting: ItemAwaiting? = null,
    val awaitingSet: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        title?.let { put("title", it) }
        body?.let { put("body", it) }
        labels?.let { l -> put("labels", buildJsonArray { l.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) }
        links?.let { l -> put("links", buildJsonArray { l.forEach { add(it.toJson()) } }) }
        if (awaitingSet) { if (awaiting != null) put("awaiting", awaiting.wire) else put("awaiting", JsonNull) }
    }
}

/// `POST /items/:id/rank` body. `position` is exclusive of `after`/`before`
/// (the server 400s the combination); the panel VM only ever sends one form.
data class ItemRankChange(
    val position: String? = null,
    val after: String? = null,
    val before: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        position?.let { put("position", it) }
        after?.let { put("after", it) }
        before?.let { put("before", it) }
    }
}

/// The tracker's network surface, as an interface so `ItemsSync` and the view
/// models can be tested against a scriptable fake. [JournalApi] implements it.
interface ItemsProviding {
    suspend fun listItems(query: ItemsListQuery): ItemsPage
    suspend fun item(id: String): ItemDetail
    suspend fun createItem(new: NewItem, idempotencyKey: String?): TrackerItem
    suspend fun updateItem(id: String, patch: ItemPatch): TrackerItem
    suspend fun commentItem(id: String, body: String, attachments: List<TrackerAttachment>, idempotencyKey: String?): ItemCommentResult
    suspend fun closeItem(id: String, resolution: ItemResolution, comment: String?): TrackerItem
    suspend fun reopenItem(id: String, comment: String?): TrackerItem
    suspend fun rankItem(id: String, change: ItemRankChange): TrackerItem
    /// Uploads raw bytes through `POST /media` and returns the blob ref an
    /// item/comment attachment carries. [progress] is the composer's upload
    /// meter; the tracker's callers leave it null.
    suspend fun uploadMedia(data: ByteArray, contentType: String, progress: ((Double) -> Unit)? = null): String
}
