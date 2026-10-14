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
    /// Consent mirror metadata; absent from ordinary items and older journals.
    val consent: String? = null,
    val consentAsk: String? = null,
    val consentOutcome: String? = null,
    val decidedBy: String? = null,
) {
    /// Restate a card from later lifecycle/reply markers, keeping its identity
    /// and closing note unless the item has since reopened.
    fun restated(latest: ItemMarkerEvent): ItemMarkerEvent {
        val closed = latest.action == Action.CLOSED ||
            (latest.action != Action.REOPENED && latest.resolution != null)
        return copy(
            title = latest.title,
            action = if (closed) Action.CLOSED else Action.CREATED,
            awaiting = latest.awaiting,
            resolution = if (closed) latest.resolution ?: resolution else null,
            comment = if (closed == (action == Action.CLOSED)) comment else null,
        )
    }

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
                consent = payload.stringOrNull("consent")?.takeIf { it.isNotEmpty() },
                consentAsk = payload.stringOrNull("consent_ask")?.takeIf { it.isNotEmpty() },
                consentOutcome = payload.stringOrNull("consent_outcome")?.takeIf { it.isNotEmpty() },
                decidedBy = payload.stringOrNull("decided_by")?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
