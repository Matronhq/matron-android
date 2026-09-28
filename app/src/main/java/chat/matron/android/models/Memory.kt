package chat.matron.android.models

import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/// A memory's kind, the `type` column of the journal's `memories` table
/// (spec 2026-09-27 memories) — the Claude Code memory-file taxonomy.
enum class MemoryType(val wire: String) {
    USER("user"), FEEDBACK("feedback"), PROJECT("project"), REFERENCE("reference");

    companion object {
        fun fromWire(raw: String?): MemoryType? = entries.firstOrNull { it.wire == raw }
    }
}

/// One of the user's memories: a standing rule or fact every agent may save
/// and the Coordinator reads at spawn. `name` is the key (kebab-case, unique
/// per user); `PUT /memories/:name` overwrites the whole memory. Mirrors the
/// journal row (protocol.md "Memories") and matron-web's `Memory`.
data class Memory(
    val id: String,
    val name: String,
    val type: MemoryType,
    /// One line, at most [DESCRIPTION_MAX] chars — what the Coordinator sees.
    val description: String,
    /// Markdown, at most [BODY_MAX_BYTES] UTF-8 bytes, may be empty.
    val body: String = "",
    val originConvoID: String? = null,
    val createdBy: ItemAuthor = ItemAuthor.AGENT,
    val updatedBy: ItemAuthor = ItemAuthor.AGENT,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        val NAME_RE = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        const val DESCRIPTION_MAX = 200
        const val BODY_MAX_BYTES = 8192

        fun isValidName(name: String): Boolean = NAME_RE.matches(name)

        /// The first problem with a memory form, or `null` when the fields
        /// are acceptable — the journal's own rules, checked before a
        /// request goes out so a bad value gets a reason, not a 400.
        fun formError(name: String, description: String, body: String): String? {
            if (!isValidName(name)) {
                return "Name must be lowercase letters, digits and dashes (up to 64), starting with a letter or digit."
            }
            val desc = description.trim()
            if (desc.isEmpty()) return "Description is required."
            if (desc.length > DESCRIPTION_MAX) return "Description must be at most $DESCRIPTION_MAX characters."
            if (desc.any { it == '\n' || it == '\r' || it == ' ' || it == ' ' }) return "Description must be a single line."
            if (body.toByteArray(Charsets.UTF_8).size > BODY_MAX_BYTES) return "Notes must be at most 8 KB."
            return null
        }

        /// Strict on the keys the journal always writes; `null` for a row
        /// this build cannot read (an unknown type, say) — the list decoder
        /// drops it rather than blanking the list.
        fun fromJson(json: JsonObject): Memory? {
            val id = json.stringOrNull("id") ?: return null
            val name = json.stringOrNull("name") ?: return null
            val type = MemoryType.fromWire(json.stringOrNull("type")) ?: return null
            val description = json.stringOrNull("description") ?: return null
            val createdAt = json.longOrNull("created_at")?.let(Instant::ofEpochMilli) ?: return null
            val updatedAt = json.longOrNull("updated_at")?.let(Instant::ofEpochMilli) ?: return null
            return Memory(
                id = id, name = name, type = type, description = description,
                body = json.stringOrNull("body") ?: "",
                originConvoID = json.stringOrNull("origin_convo_id"),
                createdBy = ItemAuthor.fromWire(json.stringOrNull("created_by")) ?: ItemAuthor.AGENT,
                updatedBy = ItemAuthor.fromWire(json.stringOrNull("updated_by")) ?: ItemAuthor.AGENT,
                createdAt = createdAt, updatedAt = updatedAt,
            )
        }
    }
}

/// The user-facing label for a memory type (matron-web `memoryTypeLabel`).
fun memoryTypeLabel(type: MemoryType): String = when (type) {
    MemoryType.USER -> "About you"
    MemoryType.FEEDBACK -> "How to work"
    MemoryType.PROJECT -> "Project"
    MemoryType.REFERENCE -> "Reference"
}

/// "you" for the user's own device, "an agent" otherwise — the row's meta line.
fun memoryAuthorLabel(author: ItemAuthor): String = if (author == ItemAuthor.USER) "you" else "an agent"
