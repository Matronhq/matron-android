package chat.matron.android.events

import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.doubleOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/// A live command-output announcement — the journal `tool_output` payload the
/// bridge publishes when a Bash tool call starts with live output enabled. The
/// output itself never rides the journal: it streams from the bridge's viewer
/// service over a separate WebSocket derived from [viewerURL].
data class LiveOutputEvent(
    val toolUseID: String,
    val command: String,
    /// The signed viewer URL (`https://host/live?token=…`). HMAC-scoped to one
    /// command's log file.
    val viewerURL: String,
    /// Token/log expiry. Past this the viewer socket rejects connects and the
    /// log may be GC'd — render "expired" instead of connecting.
    val expiresAt: Instant?,
) {
    /// The WebSocket endpoint for the stream: `http(s)` → `ws(s)`, path
    /// `…/live` → `…/live/ws`, query (the token) preserved. `null` when the URL
    /// isn't a `/live` viewer link or has an unusable scheme.
    val socketURL: String?
        get() {
            val uri = runCatching { URI(viewerURL) }.getOrNull() ?: return null
            val scheme = when (uri.scheme) {
                "https" -> "wss"
                "http" -> "ws"
                "wss", "ws" -> uri.scheme
                else -> return null
            }
            val path = uri.rawPath ?: return null
            val newPath = when {
                path.endsWith("/live") -> "$path/ws"
                path.endsWith("/live/ws") -> path
                else -> return null
            }
            val authority = uri.rawAuthority ?: return null
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return "$scheme://$authority$newPath$query"
        }

    val isExpired: Boolean
        get() = expiresAt?.let { !Instant.now().isBefore(it) } ?: false

    companion object {
        /// Parses the bridge's payload shape. `command` + a parseable
        /// `viewer_url` are what make live rendering possible — without either
        /// the caller should fall back to the static tool-call card.
        /// `tool_use_id` falls back to the URL string so a malformed payload
        /// missing it still gets a stable identity.
        fun parse(payload: JsonObject): LiveOutputEvent? {
            val command = payload.stringOrNull("command")?.takeIf { it.isNotEmpty() } ?: return null
            val urlString = payload.stringOrNull("viewer_url") ?: return null
            val uri = runCatching { URI(urlString) }.getOrNull() ?: return null
            if (uri.scheme == null) return null
            val expiresSeconds = payload.doubleOrNull("expires_at")
            return LiveOutputEvent(
                toolUseID = payload.stringOrNull("tool_use_id") ?: urlString,
                command = command,
                viewerURL = urlString,
                expiresAt = expiresSeconds?.let { Instant.ofEpochMilli((it * 1000).toLong()) },
            )
        }
    }
}

/// One frame of the viewer socket's protocol. `{type:"data", chunk}` appends
/// output; `{type:"complete", exitCode, denied, truncated}` is terminal.
/// Anything else is ignored so the protocol can grow.
sealed interface LiveOutputFrame {
    data class Data(val chunk: String) : LiveOutputFrame
    data class Complete(
        val exitCode: Int?,
        val denied: Boolean,
        val truncated: Boolean,
    ) : LiveOutputFrame

    companion object {
        fun decode(text: String): LiveOutputFrame? {
            val obj: JsonObject = parseJsonObjectOrNull(text) ?: return null
            return when (obj.stringOrNull("type")) {
                "data" -> obj.stringOrNull("chunk")?.let { Data(it) }
                "complete" -> Complete(
                    exitCode = obj.intOrNull("exitCode"),
                    denied = obj.boolOrNull("denied") ?: false,
                    truncated = obj.boolOrNull("truncated") ?: false,
                )
                else -> null
            }
        }
    }
}
