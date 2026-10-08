package chat.matron.android.events

import chat.matron.android.journal.MatronJson
import chat.matron.android.journal.MatronJsonPretty
import chat.matron.android.journal.doubleOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/// Recursively sorts object keys and renders as pretty JSON. Mirrors Swift's
/// `JSONSerialization.data(withJSONObject:options:[.prettyPrinted,.sortedKeys])`
/// so tool cards render stable, deterministic code blocks. The exact whitespace
/// differs from Foundation's (4-space vs 2-space, `"k": v` vs `"k" : v`, no
/// forward-slash escaping) — a serializer detail, not a behavioral one.
internal fun sortedPrettyJson(element: JsonElement): String {
    val sorted = sortJsonKeys(element)
    if (sorted is JsonObject && sorted.isEmpty()) return "{}"
    return MatronJsonPretty.encodeToString(JsonElement.serializer(), sorted)
}

private fun sortJsonKeys(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries.sortedBy { it.key }.associate { it.key to sortJsonKeys(it.value) }
    )
    is JsonArray -> JsonArray(element.map { sortJsonKeys(it) })
    else -> element
}

/// Decoded form of a `chat.matron.tool_call` event content blob (also fed from
/// journal `tool_output` payloads). Renders as a tool-call card. `argsJSON` is a
/// pretty-printed sorted-key JSON string so the card can display it without
/// re-serialising on every render. `resultText` is the string form of whatever
/// the bridge supplied as `result` (string-or-object).
///
/// `running` events carry only `started_at`; `ok` / `error` events add
/// `ended_at` + `result` (+ `result_truncated`). Wire timestamps are
/// milliseconds-since-epoch.
data class ToolCallEvent(
    val tool: String,
    val argsJSON: String,
    val status: Status,
    val resultText: String?,
    val resultTruncated: Boolean,
    val startedAt: Instant,
    val endedAt: Instant?,
    /// Command-completion fields from the journal's `tool_output` payload.
    /// Absent on `chat.matron.tool_call` payloads and diff/fallback shapes,
    /// which is why they default to "nothing to show".
    val exitCode: Int? = null,
    val denied: Boolean = false,
    /// Output purged (server tombstone, or the client-side 24h TTL) — render an
    /// "output expired" affordance.
    val expired: Boolean = false,
) {
    enum class Status(val wire: String) {
        RUNNING("running"), OK("ok"), ERROR("error");

        companion object {
            fun fromWire(raw: String): Status? = entries.firstOrNull { it.wire == raw }
        }
    }

    /// The Bash-tool command string: present when `argsJSON` is a JSON object
    /// carrying a string under `"command"`, `null` otherwise. Computed once at
    /// construction (not a `get()`) — card renderers read this on every
    /// recomposition, and re-parsing `argsJSON` each time would be wasted work.
    val commandString: String? = argsObject(argsJSON)?.stringOrNull("command")

    /// One-line argument summary for the collapsed card header. Prefers the
    /// human-readable form (Bash `command`, or a single `key: value` string),
    /// else the raw JSON; collapsed to one line and truncated to 80 chars.
    /// Nullary tools (`argsJSON == "{}"`) summarise to "". Computed once at
    /// construction, same rationale as [commandString].
    val argSummary: String = if (argsJSON == "{}") {
        ""
    } else {
        val oneLine = summaryCandidate(argsJSON).replace("\n", " ")
        if (oneLine.length > 80) oneLine.take(77) + "…" else oneLine
    }

    companion object {
        /// Parse a `chat.matron.tool_call` content object. Returns `null` if any
        /// required field is missing or wrong-shaped — callers fall back to
        /// plain-text rendering (graceful degradation).
        fun parse(content: JsonObject): ToolCallEvent? {
            val tool = content.stringOrNull("tool") ?: return null
            val statusRaw = content.stringOrNull("status") ?: return null
            val status = Status.fromWire(statusRaw) ?: return null
            val startedMs = content.doubleOrNull("started_at") ?: return null

            val argsElem = content["args"] ?: JsonObject(emptyMap())
            val argsJSON = if (argsElem is JsonObject && argsElem.isEmpty()) "{}"
                else sortedPrettyJson(argsElem)

            val resultElem = content["result"]
            val resultText: String? = when {
                resultElem is JsonPrimitive && resultElem.isString -> resultElem.content
                resultElem is JsonObject -> sortedPrettyJson(resultElem)
                else -> null
            }
            val resultTruncated = content.boolOrNull("result_truncated") ?: false
            val endedAt = content.doubleOrNull("ended_at")?.let { Instant.ofEpochMilli(it.toLong()) }

            return ToolCallEvent(
                tool = tool,
                argsJSON = argsJSON,
                status = status,
                resultText = resultText,
                resultTruncated = resultTruncated,
                startedAt = Instant.ofEpochMilli(startedMs.toLong()),
                endedAt = endedAt,
            )
        }

        /// Parse `argsJSON` back into a JSON object, or `null` when it isn't one
        /// (already-flattened command strings like `"make test"` parse to null).
        private fun argsObject(argsJSON: String): JsonObject? =
            runCatching { MatronJson.parseToJsonElement(argsJSON) as? JsonObject }.getOrNull()

        /// The un-truncated, still-multiline summary string.
        private fun summaryCandidate(argsJSON: String): String {
            val obj = argsObject(argsJSON)
            if (obj != null) {
                obj.stringOrNull("command")?.let { return it }
                if (obj.size == 1) {
                    val key = obj.keys.first()
                    obj.stringOrNull(key)?.let { return "$key: $it" }
                }
            }
            return argsJSON
        }
    }
}
