package chat.matron.android.models

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.doubleOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The task & decision tracker's model layer (spec
// `2026-09-08-task-decision-tracker-design.md`, protocol.md "## Items").
// Ported from matron-apple's `Models/TrackerItem.swift`. Wire decoding is
// `fromJson` on each type (the Swift failable `init?(json:)`): a row missing
// a required key, or carrying an enum value this client doesn't know,
// decodes to `null` and is skipped by the caller — never a crash, never a
// half-built row.

enum class ItemKind(val wire: String) {
    TASK("task"), QUESTION("question"), DECISION("decision");

    companion object {
        fun fromWire(raw: String?): ItemKind? = entries.firstOrNull { it.wire == raw }
    }
}

enum class ItemState(val wire: String) {
    OPEN("open"), CLOSED("closed");

    companion object {
        fun fromWire(raw: String?): ItemState? = entries.firstOrNull { it.wire == raw }
    }
}

enum class ItemResolution(val wire: String) {
    DONE("done"), ANSWERED("answered"), DECIDED("decided"), REVERSED("reversed"), CANCELLED("cancelled");

    companion object {
        fun fromWire(raw: String?): ItemResolution? = entries.firstOrNull { it.wire == raw }
    }
}

enum class ItemAwaiting(val wire: String) {
    USER("user"), AGENT("agent");

    companion object {
        fun fromWire(raw: String?): ItemAwaiting? = entries.firstOrNull { it.wire == raw }
    }
}

enum class ItemAuthor(val wire: String) {
    USER("user"), AGENT("agent");

    companion object {
        fun fromWire(raw: String?): ItemAuthor? = entries.firstOrNull { it.wire == raw }
    }
}

/// Which items a list/query covers: one origin conversation, or every
/// conversation. Lives in `models` (not `journal`) so the design-system views
/// can take it without depending on the journal layer — same layering rule
/// as the Swift original.
sealed interface ItemsScope {
    data class Convo(val id: String) : ItemsScope
    data object All : ItemsScope
}

private fun msInstant(ms: Long?): Instant? = ms?.let { Instant.ofEpochMilli(it) }

/// One attachment on an item body or a comment. `@Serializable` (with the
/// wire's snake_case names) because the Room mirror stores attachment lists
/// as a JSON text column and the item outbox round-trips them the same way.
@Serializable
data class TrackerAttachment(
    @SerialName("blob_ref") val blobRef: String,
    val mime: String,
    val name: String = "",
    val size: Long = 0,
    val transcript: String? = null,
    /// The journal's own transcription job for this voice note: `"pending"`
    /// while it runs, then `"done"` or `"failed"`. `null` when the journal
    /// never took the job (the origin bridge transcribes instead) — which,
    /// like `"pending"`, still reads as "Transcribing…" until words arrive.
    @SerialName("transcript_status") val transcriptStatus: String? = null,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
    val isAudio: Boolean get() = mime.startsWith("audio/")

    /// Nobody produced words and nobody is still trying: the journal's job
    /// failed. (A bridge that then transcribes it itself flips this back to
    /// `"done"` server-side, and the next refresh shows the words.)
    val transcriptionFailed: Boolean
        get() = isAudio && transcript.isNullOrEmpty() && transcriptStatus == "failed"

    /// The full wire shape, transcript included — what the local mirror
    /// stores. Outgoing requests use [outgoingJson] instead.
    fun toJson(): JsonObject = buildJsonObject {
        put("blob_ref", blobRef); put("mime", mime); put("name", name); put("size", size)
        transcript?.let { put("transcript", it) }
        transcriptStatus?.let { put("transcript_status", it) }
    }

    /// Attachments never carry a transcript out of the app: the journal strips
    /// the field on the way in anyway, and only the bridge is allowed to set
    /// transcripts. Deliberately not [toJson] — this always drops it, even if
    /// a caller's local attachment happens to have one set.
    fun outgoingJson(): JsonObject = buildJsonObject {
        put("blob_ref", blobRef); put("mime", mime); put("name", name); put("size", size)
    }

    companion object {
        fun fromJson(json: JsonObject): TrackerAttachment? {
            val blobRef = json.stringOrNull("blob_ref") ?: return null
            val mime = json.stringOrNull("mime") ?: return null
            return TrackerAttachment(
                blobRef = blobRef, mime = mime, name = json.stringOrNull("name") ?: "",
                size = json.longOrNull("size") ?: 0, transcript = json.stringOrNull("transcript"),
                transcriptStatus = json.stringOrNull("transcript_status"),
            )
        }

        fun listFromJson(array: JsonArray?): List<TrackerAttachment> =
            array?.objects()?.mapNotNull(::fromJson) ?: emptyList()
    }
}

@Serializable
data class TrackerLink(val url: String, val title: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("url", url)
        title?.let { put("title", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): TrackerLink? {
            val url = json.stringOrNull("url") ?: return null
            return TrackerLink(url, json.stringOrNull("title"))
        }
    }
}

/// One tracker item — a task, a question or a decision (spec: Data model).
data class TrackerItem(
    val id: String,
    val num: Int,
    val kind: ItemKind,
    val state: ItemState = ItemState.OPEN,
    val resolution: ItemResolution? = null,
    val awaiting: ItemAwaiting? = null,
    val rank: Double = 1024.0,
    val title: String,
    val body: String = "",
    val labels: List<String> = emptyList(),
    val links: List<TrackerLink> = emptyList(),
    val attachments: List<TrackerAttachment> = emptyList(),
    val supersedes: String? = null,
    val originConvoID: String,
    val createdBy: ItemAuthor = ItemAuthor.AGENT,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val closedAt: Instant? = null,
    val commentCount: Int = 0,
    val lastCommentAt: Instant? = null,
    val hasImage: Boolean = false,
    /// The mission this item belongs to, defaulted by the journal from the
    /// origin conversation. The apps only ever DISPLAY it; both are `null`
    /// for an item filed in a conversation with no mission — and the id can
    /// be present while the mission itself is invisible to this caller, so
    /// never assume a local mission row exists for it.
    val missionID: String? = null,
    val missionNum: Int? = null,
) {
    /// The `from`/`to` halves of a status comment's `meta`.
    data class StatusSnapshot(
        val state: ItemState?,
        val resolution: ItemResolution?,
        val awaiting: ItemAwaiting?,
    ) {
        companion object {
            fun fromJson(json: JsonObject?): StatusSnapshot? {
                if (json == null) return null
                return StatusSnapshot(
                    state = ItemState.fromWire(json.stringOrNull("state")),
                    resolution = ItemResolution.fromWire(json.stringOrNull("resolution")),
                    awaiting = ItemAwaiting.fromWire(json.stringOrNull("awaiting")),
                )
            }
        }
    }

    /// `awaiting='user'` on an open item is the "Needs you" set across all kinds.
    val needsUser: Boolean get() = state == ItemState.OPEN && awaiting == ItemAwaiting.USER

    companion object {
        fun fromJson(json: JsonObject): TrackerItem? {
            val id = json.stringOrNull("id") ?: return null
            val num = json.intOrNull("num") ?: return null
            val kind = ItemKind.fromWire(json.stringOrNull("kind")) ?: return null
            val state = ItemState.fromWire(json.stringOrNull("state")) ?: return null
            val title = json.stringOrNull("title") ?: return null
            val origin = json.stringOrNull("origin_convo_id") ?: return null
            val createdAt = msInstant(json.longOrNull("created_at")) ?: return null
            val updatedAt = msInstant(json.longOrNull("updated_at")) ?: return null
            // SQLite hands `has_image` back as 0/1; a JSON boolean is accepted too.
            val hasImage = json.boolOrNull("has_image") ?: ((json.intOrNull("has_image") ?: 0) != 0)
            return TrackerItem(
                id = id, num = num, kind = kind, state = state,
                resolution = ItemResolution.fromWire(json.stringOrNull("resolution")),
                awaiting = ItemAwaiting.fromWire(json.stringOrNull("awaiting")),
                rank = json.doubleOrNull("rank") ?: 0.0, title = title,
                body = json.stringOrNull("body") ?: "",
                labels = json.arrayOrNull("labels")?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ?: emptyList(),
                links = json.arrayOrNull("links")?.objects()?.mapNotNull(TrackerLink::fromJson) ?: emptyList(),
                attachments = TrackerAttachment.listFromJson(json.arrayOrNull("attachments")),
                supersedes = json.stringOrNull("supersedes"), originConvoID = origin,
                createdBy = ItemAuthor.fromWire(json.stringOrNull("created_by")) ?: ItemAuthor.AGENT,
                createdAt = createdAt, updatedAt = updatedAt, closedAt = msInstant(json.longOrNull("closed_at")),
                commentCount = json.intOrNull("comment_count") ?: 0,
                lastCommentAt = msInstant(json.longOrNull("last_comment_at")),
                hasImage = hasImage,
                missionID = json.stringOrNull("mission_id"),
                missionNum = json.intOrNull("mission_num"),
            )
        }
    }
}

/// One row of an item's thread: a person's comment, or the synthetic
/// `status` row a close/reopen writes (with `statusFrom`/`statusTo`).
data class TrackerComment(
    val id: String,
    val itemID: String,
    val author: ItemAuthor,
    val deviceID: Long = 0,
    val kind: Kind = Kind.COMMENT,
    val body: String,
    val attachments: List<TrackerAttachment> = emptyList(),
    val statusFrom: TrackerItem.StatusSnapshot? = null,
    val statusTo: TrackerItem.StatusSnapshot? = null,
    val createdAt: Instant = Instant.now(),
) {
    enum class Kind(val wire: String) {
        COMMENT("comment"), STATUS("status");

        companion object {
            fun fromWire(raw: String?): Kind? = entries.firstOrNull { it.wire == raw }
        }
    }

    companion object {
        fun fromJson(json: JsonObject): TrackerComment? {
            val id = json.stringOrNull("id") ?: return null
            val itemID = json.stringOrNull("item_id") ?: return null
            val author = ItemAuthor.fromWire(json.stringOrNull("author")) ?: return null
            val kind = Kind.fromWire(json.stringOrNull("kind")) ?: return null
            val createdAt = msInstant(json.longOrNull("created_at")) ?: return null
            val meta = json.objectOrNull("meta")
            return TrackerComment(
                id = id, itemID = itemID, author = author, deviceID = json.longOrNull("device_id") ?: 0,
                kind = kind, body = json.stringOrNull("body") ?: "",
                attachments = TrackerAttachment.listFromJson(json.arrayOrNull("attachments")),
                statusFrom = TrackerItem.StatusSnapshot.fromJson(meta?.objectOrNull("from")),
                statusTo = TrackerItem.StatusSnapshot.fromJson(meta?.objectOrNull("to")),
                createdAt = createdAt,
            )
        }
    }
}
