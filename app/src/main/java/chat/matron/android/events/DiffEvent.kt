package chat.matron.android.events

import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonObject

/// Decoded form of a journal `diff` event payload — a file-edit snippet the
/// bridge publishes at tool_use time, replacing the old "✏️ Editing …" text
/// message. The pre-spec bare shape (`{diff}` or `{snippet}` alone) parses into
/// the same type with null metadata so there is exactly one render path.
data class DiffEvent(
    val filePath: String? = null,
    val displayPath: String? = null,
    val viewerURL: String? = null,
    val tool: String? = null,
    /// Subagent label; null for parent-agent edits.
    val label: String? = null,
    val diff: String,
    val added: Int? = null,
    val removed: Int? = null,
    val truncated: Boolean = false,
    val newFile: Boolean = false,
) {
    /// Header filename: last component of the display path (falling back to the
    /// absolute path); null when the payload carried no path at all.
    val filename: String?
        get() = (displayPath ?: filePath)?.substringAfterLast('/')

    companion object {
        /// Total parse — every field is optional metadata around the diff text,
        /// and a payload with neither `diff` nor `snippet` yields an empty
        /// string (the card renders header-only). No null return: the mapper has
        /// already routed on the event TYPE.
        fun parse(payload: JsonObject): DiffEvent = DiffEvent(
            filePath = payload.stringOrNull("file_path"),
            displayPath = payload.stringOrNull("display_path"),
            viewerURL = payload.stringOrNull("viewer_url"),
            tool = payload.stringOrNull("tool"),
            label = payload.stringOrNull("label"),
            diff = payload.stringOrNull("diff") ?: payload.stringOrNull("snippet") ?: "",
            added = payload.intOrNull("added"),
            removed = payload.intOrNull("removed"),
            truncated = payload.boolOrNull("truncated") ?: false,
            newFile = payload.boolOrNull("new_file") ?: false,
        )
    }
}
