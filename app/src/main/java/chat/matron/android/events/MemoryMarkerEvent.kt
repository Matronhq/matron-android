package chat.matron.android.events

import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.models.ItemAuthor
import kotlinx.serialization.json.JsonObject

/// The `memory` journal event (protocol.md "Memories → Marker event"): the
/// user's memories changed. Purely an invalidation signal — the app
/// re-reads `GET /memories` rather than trusting anything here — and quiet
/// bookkeeping in the transcript (no row). [name] is OPTIONAL: the journal
/// drops name, type and description at write time when the memory was saved
/// from a private device and the conversation being written to is not
/// private-owned. [memoryID] and [action] always survive.
data class MemoryMarkerEvent(
    val memoryID: String,
    val action: Action,
    val created: Boolean = false,
    val by: ItemAuthor = ItemAuthor.AGENT,
    val name: String? = null,
) {
    enum class Action(val wire: String) {
        SAVED("saved"), DELETED("deleted");

        companion object {
            fun fromWire(raw: String?): Action? = entries.firstOrNull { it.wire == raw }
        }
    }

    companion object {
        fun parse(payload: JsonObject): MemoryMarkerEvent? {
            val memoryID = payload.stringOrNull("memory_id") ?: return null
            val action = Action.fromWire(payload.stringOrNull("action")) ?: return null
            return MemoryMarkerEvent(
                memoryID = memoryID, action = action,
                created = payload.boolOrNull("created") ?: false,
                by = ItemAuthor.fromWire(payload.stringOrNull("by")) ?: ItemAuthor.AGENT,
                name = payload.stringOrNull("name"),
            )
        }
    }
}
