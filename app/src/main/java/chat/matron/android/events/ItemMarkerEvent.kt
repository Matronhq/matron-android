package chat.matron.android.events

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.TrackerAttachment
import kotlinx.serialization.json.JsonObject

/// The `item` journal event (protocol.md "Marker event") — what the
/// conversation log records about a tracker item. Not the item itself: the
/// apps refetch the row from `/items/:id` on receipt. `reordered` and
/// `updated` markers exist only to invalidate the local cache and never
/// render. Ported from matron-apple's `Events/ItemMarkerEvent.swift`.
data class ItemMarkerEvent(
    val itemID: String,
    val num: Int,
    val kind: ItemKind,
    val title: String,
    val action: Action,
    val by: ItemAuthor,
    val awaiting: ItemAwaiting? = null,
    val resolution: ItemResolution? = null,
    val comment: Comment? = null,
) {
    enum class Action(val wire: String) {
        CREATED("created"), COMMENTED("commented"), CLOSED("closed"), REOPENED("reopened"),
        REORDERED("reordered"), UPDATED("updated");

        companion object {
            fun fromWire(raw: String?): Action? = entries.firstOrNull { it.wire == raw }
        }
    }

    /// Present only when the action carried comment text or attachments (a
    /// bare close/reopen with no note omits it).
    data class Comment(
        val id: String,
        val body: String,
        val attachments: List<TrackerAttachment> = emptyList(),
    )

    companion object {
        fun parse(payload: JsonObject): ItemMarkerEvent? {
            val itemID = payload.stringOrNull("item_id") ?: return null
            val num = payload.intOrNull("num") ?: return null
            val kind = ItemKind.fromWire(payload.stringOrNull("kind")) ?: return null
            val title = payload.stringOrNull("title") ?: return null
            val action = Action.fromWire(payload.stringOrNull("action")) ?: return null
            val by = ItemAuthor.fromWire(payload.stringOrNull("by")) ?: return null
            val comment = payload.objectOrNull("comment")?.let { c ->
                val cid = c.stringOrNull("id") ?: return@let null
                Comment(
                    id = cid, body = c.stringOrNull("body") ?: "",
                    attachments = TrackerAttachment.listFromJson(c.arrayOrNull("attachments")),
                )
            }
            return ItemMarkerEvent(
                itemID = itemID, num = num, kind = kind, title = title, action = action, by = by,
                awaiting = ItemAwaiting.fromWire(payload.stringOrNull("awaiting")),
                resolution = ItemResolution.fromWire(payload.stringOrNull("resolution")),
                comment = comment,
            )
        }
    }
}
