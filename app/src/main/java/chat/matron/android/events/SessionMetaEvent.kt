package chat.matron.android.events

import chat.matron.android.journal.doubleOrNull
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/// Decoded form of a `chat.matron.session_meta` state event content blob, giving
/// the client enough context to render the session header without scanning the
/// timeline.
///
/// `sessionID` + `startedAt` are required; `model` + `workdir` are optional so
/// older bots can land partial events without breaking the parser.
data class SessionMetaEvent(
    val sessionID: String,
    val model: String?,
    val workdir: String?,
    val startedAt: Instant,
) {
    companion object {
        /// Parse a `chat.matron.session_meta` content object. Returns `null` if
        /// `session_id` or `started_at` are missing (header just won't render).
        fun parse(content: JsonObject): SessionMetaEvent? {
            val sessionID = content.stringOrNull("session_id") ?: return null
            val startedMs = content.doubleOrNull("started_at") ?: return null
            return SessionMetaEvent(
                sessionID = sessionID,
                model = content.stringOrNull("model"),
                workdir = content.stringOrNull("workdir"),
                startedAt = Instant.ofEpochMilli(startedMs.toLong()),
            )
        }
    }
}
