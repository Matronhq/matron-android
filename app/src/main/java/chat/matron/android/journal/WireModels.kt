package chat.matron.android.journal

import chat.matron.android.models.SessionStatus
import chat.matron.android.models.SessionStatusUpdate
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/// String constants for journal event `type`s (spec §7). Use these, not
/// literals, so renames are compile-checked.
object JournalEventType {
    const val TEXT = "text"
    const val PROMPT = "prompt"
    const val PROMPT_REPLY = "prompt_reply"
    const val TOOL_OUTPUT = "tool_output"
    const val DIFF = "diff"
    const val PERMISSION_REQUEST = "permission_request"
    const val SESSION_STATUS = "session_status"
    const val FILE = "file"
    const val IMAGE = "image"
    const val READ_MARKER = "read_marker"
    const val EDIT = "edit"

    /// Conversation metadata (title, etc.). Carries no message body.
    const val CONVO_META = "convo_meta"

    /// Infix in a subagent child's convo id: `<parent>:sub:<agentId>`.
    const val CHILD_CONVO_INFIX = ":sub:"

    /// Types that bump unread counts and set the conversation snippet.
    val MESSAGE_TYPES: Set<String> = setOf(
        TEXT, TOOL_OUTPUT, DIFF, PROMPT, PERMISSION_REQUEST, FILE, IMAGE,
    )
}

/// One durable journal row. `payload` keeps the raw JSON object so arbitrary
/// payload shapes survive round-trips.
data class JournalEvent(
    val seq: Long,
    val convoID: String,
    val ts: Instant,
    val sender: String,
    val type: String,
    val payload: JsonObject,
) {
    companion object {
        /// Builds from a decoded `{seq, convo_id, ts, sender, type, payload}`
        /// object (shared shape of WS journal frames and HTTP pagination rows).
        fun fromFrame(obj: JsonObject): JournalEvent? {
            val seq = obj.longOrNull("seq") ?: return null
            val convoID = obj.stringOrNull("convo_id") ?: return null
            val ts = obj.doubleOrNull("ts") ?: return null
            val sender = obj.stringOrNull("sender") ?: return null
            val type = obj.stringOrNull("type") ?: return null
            val payload = obj.objectOrNull("payload") ?: JsonObject(emptyMap())
            return JournalEvent(
                seq = seq,
                convoID = convoID,
                ts = Instant.ofEpochMilli(ts.toLong()),
                sender = sender,
                type = type,
                payload = payload,
            )
        }
    }
}

/// A streaming-output update. Never persisted; lost updates are harmless (the
/// finalize journal row supersedes them).
data class EphemeralUpdate(
    val convoID: String,
    val messageRef: String,
    val textDelta: String?,
    val replaceText: String?,
)

/// A transient activity indicator (typing / tool-use). `Idle` clears whatever
/// indicator is showing. Never persisted; delivered only while `viewing`.
data class ActivityUpdate(
    val convoID: String,
    val state: State,
    val detail: String?,
) {
    enum class State(val wire: String) {
        /// Agent is composing/thinking — a bare "working" indicator.
        THINKING("thinking"),
        /// Agent is running a tool; `detail` carries the tool name.
        TOOL("tool"),
        /// Nothing in flight — clears any showing indicator.
        IDLE("idle");

        companion object {
            fun fromWire(raw: String): State? = entries.firstOrNull { it.wire == raw }
        }
    }
}

/// One live tool-output stream frame. `offset`s are UTF-8 BYTE positions in the
/// command's output. Never persisted; delivered only while `viewing`.
data class ToolStreamUpdate(
    val convoID: String,
    val messageRef: String,
    val event: Event,
) {
    sealed interface Event {
        /// Consecutive appends coalesce by concatenation. No meta.
        data class Append(val offset: Int, val chunk: String) : Event
        /// Full scrollback so far, sent per active stream on (re-)viewing.
        /// `offset` is the byte position of `content`'s first byte;
        /// `headTruncated` means the ring buffer dropped the beginning.
        data class Sync(
            val tool: String?,
            val command: String?,
            val offset: Int,
            val content: String,
            val headTruncated: Boolean,
        ) : Event
        /// Server idle sweep freed the buffer (bridge died) — drop the tile.
        data class End(val reason: String?) : Event
    }
}

/// An agent's answer to an `agent_request`. `result` keeps the raw JSON of the
/// method-specific result — the caller decodes its shape.
data class RPCResponse(
    val requestID: String,
    val agentDeviceID: Long,
    val ok: Boolean,
    val result: kotlinx.serialization.json.JsonElement?,
    val errorCode: String?,
    val errorDetail: String?,
)

/// Server → client frames. Unknown `kind`s decode to null (skip); unknown
/// control ops decode to [UnknownControl] so the protocol can grow.
sealed interface ServerFrame {
    data class Journal(val event: JournalEvent) : ServerFrame
    data class Ephemeral(val update: EphemeralUpdate) : ServerFrame
    data class Activity(val update: ActivityUpdate) : ServerFrame
    data class ToolStream(val update: ToolStreamUpdate) : ServerFrame
    data class SessionStatusFrame(val update: SessionStatusUpdate) : ServerFrame
    data class RpcResponse(val response: RPCResponse) : ServerFrame
    data class HelloOK(val headSeq: Long) : ServerFrame
    /// `requestID` correlates RPC errors back to their `agent_request`; null for
    /// ordinary op errors.
    data class Error(
        val code: String,
        val ref: String?,
        val requestID: String?,
        val detail: String?,
    ) : ServerFrame
    data object SnapshotRequired : ServerFrame
    data class UnknownControl(val op: String) : ServerFrame

    companion object {
        /// Bridge timestamps are `Date.toISOString()` output (fractional), but
        /// plain ISO is accepted too for robustness.
        private fun parseISODate(raw: String): Instant? =
            runCatching { Instant.parse(raw) }.getOrNull()

        fun decode(text: String): ServerFrame? {
            val obj = parseJsonObjectOrNull(text) ?: return null
            val kind = obj.stringOrNull("kind") ?: return null
            return when (kind) {
                "journal" -> JournalEvent.fromFrame(obj)?.let { Journal(it) }
                "ephemeral" -> decodeEphemeral(obj)
                "rpc" -> decodeRpc(obj)
                "control" -> decodeControl(obj)
                else -> null
            }
        }

        private fun decodeEphemeral(obj: JsonObject): ServerFrame? {
            val convoID = obj.stringOrNull("convo_id") ?: return null
            // Two shapes share `kind: "ephemeral"`: a streaming-text update
            // (keyed by `message_ref`) and an activity indicator (an `activity`
            // object, no `message_ref`). Branch on `activity` so a valid
            // activity frame isn't dropped by a `message_ref` guard.
            obj.objectOrNull("activity")?.let { activity ->
                val stateRaw = activity.stringOrNull("state") ?: return null
                val state = ActivityUpdate.State.fromWire(stateRaw) ?: return null
                return Activity(ActivityUpdate(convoID, state, activity.stringOrNull("detail")))
            }
            // tool_stream frames also carry `message_ref`; matched before the
            // text-streaming fallback or they'd paint an empty streaming bubble.
            obj.objectOrNull("tool_stream")?.let { toolStream ->
                val ref = obj.stringOrNull("message_ref") ?: return null
                val eventName = toolStream.stringOrNull("event") ?: return null
                val event: ToolStreamUpdate.Event = when (eventName) {
                    "append" -> {
                        val offset = toolStream.intOrNull("offset") ?: return null
                        val chunk = toolStream.stringOrNull("chunk") ?: return null
                        ToolStreamUpdate.Event.Append(offset, chunk)
                    }
                    "sync" -> {
                        val offset = toolStream.intOrNull("offset") ?: return null
                        val content = toolStream.stringOrNull("content") ?: return null
                        val meta = toolStream.objectOrNull("meta")
                        ToolStreamUpdate.Event.Sync(
                            tool = meta?.stringOrNull("tool"),
                            command = meta?.stringOrNull("command"),
                            offset = offset,
                            content = content,
                            headTruncated = toolStream.boolOrNull("head_truncated") ?: false,
                        )
                    }
                    "end" -> ToolStreamUpdate.Event.End(toolStream.stringOrNull("reason"))
                    else -> return null // unknown tool_stream event — skip
                }
                return ToolStream(ToolStreamUpdate(convoID, ref, event))
            }
            // Session-status frames carry a `status` object and no
            // `message_ref`. Parts are independently optional.
            obj.objectOrNull("status")?.let { status ->
                var context: SessionStatus.Context? = null
                status.objectOrNull("context")?.let { ctx ->
                    val tokens = ctx.intOrNull("tokens")
                    val window = ctx.intOrNull("window")
                    val pct = ctx.intOrNull("pct")
                    if (tokens != null && window != null && pct != null) {
                        context = SessionStatus.Context(tokens, window, pct)
                    }
                }
                var limits: List<SessionStatus.Limit>? = null
                status.arrayOrNull("limits")?.let { rawLimits ->
                    val parsed = rawLimits.objects().mapNotNull { entry ->
                        val label = entry.stringOrNull("label") ?: return@mapNotNull null
                        val percent = entry.intOrNull("percent") ?: return@mapNotNull null
                        SessionStatus.Limit(
                            label = label,
                            percent = percent,
                            resets = entry.stringOrNull("resets"),
                            resetsAt = entry.stringOrNull("resets_at")?.let(::parseISODate),
                        )
                    }
                    if (parsed.isNotEmpty()) limits = parsed
                }
                return SessionStatusFrame(SessionStatusUpdate(
                    convoID = convoID,
                    model = status.stringOrNull("model"),
                    context = context,
                    limits = limits,
                    email = status.stringOrNull("email"),
                    taskRef = status.stringOrNull("task_ref"),
                ))
            }
            val ref = obj.stringOrNull("message_ref") ?: return null
            return Ephemeral(EphemeralUpdate(
                convoID = convoID,
                messageRef = ref,
                textDelta = obj.stringOrNull("text"),
                replaceText = obj.stringOrNull("replace_text"),
            ))
        }

        private fun decodeRpc(obj: JsonObject): ServerFrame? {
            // Only the client-side shape (a `response` object) is expected here;
            // an agent-side `request` frame is not ours to handle.
            val response = obj.objectOrNull("response") ?: return null
            val requestID = response.stringOrNull("request_id") ?: return null
            val ok = response.boolOrNull("ok") ?: return null
            val result = if (ok) response["result"] else null
            val error = response.objectOrNull("error")
            return RpcResponse(RPCResponse(
                requestID = requestID,
                agentDeviceID = response.longOrNull("agent_device_id") ?: 0,
                ok = ok,
                result = result,
                errorCode = error?.stringOrNull("code"),
                errorDetail = error?.stringOrNull("detail"),
            ))
        }

        private fun decodeControl(obj: JsonObject): ServerFrame? {
            val op = obj.stringOrNull("op") ?: return null
            return when (op) {
                "hello_ok" -> HelloOK(obj.longOrNull("seq") ?: 0)
                "error" -> Error(
                    code = obj.stringOrNull("code") ?: "unknown",
                    ref = obj.stringOrNull("ref"),
                    requestID = obj.stringOrNull("request_id"),
                    detail = obj.stringOrNull("detail"),
                )
                "snapshot_required" -> SnapshotRequired
                else -> UnknownControl(op)
            }
        }
    }
}

/// Client → server operations.
sealed interface ClientOp {
    data class Hello(val token: String, val cursor: Long?) : ClientOp
    data class Send(val convoID: String, val body: String, val localID: String) : ClientOp
    /// A media `send`: `type` is the wire kind (`"file"`/`"image"`), `blobRef`
    /// the id from a prior `POST /media` upload. `caption` is the composer text
    /// this attachment left with, omitted from the payload when null/empty.
    data class SendMedia(
        val convoID: String,
        val type: String,
        val blobRef: String,
        val name: String,
        val contentType: String,
        val size: Int,
        val caption: String?,
        val localID: String,
    ) : ClientOp
    data class PromptReply(
        val convoID: String,
        val targetSeq: Long,
        val choice: String?,
        val text: String?,
    ) : ClientOp
    data class ReadMarker(val convoID: String, val upToSeq: Long) : ClientOp
    data class Ack(val cursor: Long) : ClientOp
    data class Viewing(val convoID: String?) : ClientOp
    /// A structured request to one of the user's agent devices. `paramsJson` is
    /// a JSON-encoded object; unparseable input degrades to `{}` at encode time.
    data class AgentRequest(
        val requestID: String,
        val agentDeviceID: Long,
        val method: String,
        val paramsJson: String,
    ) : ClientOp

    fun encoded(): String {
        val obj: JsonObject = when (this) {
            is Hello -> buildJsonObject {
                put("op", "hello")
                put("token", token)
                put("cursor", cursor?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            is Send -> buildJsonObject {
                put("op", "send")
                put("convo_id", convoID)
                put("type", "text")
                put("payload", buildJsonObject { put("body", body) })
                put("local_id", localID)
            }
            is SendMedia -> buildJsonObject {
                put("op", "send")
                put("convo_id", convoID)
                put("type", type)
                put("blob_ref", blobRef)
                put("payload", buildJsonObject {
                    put("blob_ref", blobRef)
                    put("name", name)
                    put("content_type", contentType)
                    put("size", size)
                    // Absent rather than null for a captionless send.
                    if (!caption.isNullOrEmpty()) put("caption", caption)
                })
                put("local_id", localID)
            }
            is PromptReply -> buildJsonObject {
                put("op", "prompt_reply")
                put("convo_id", convoID)
                put("target_seq", targetSeq)
                put("choice", choice?.let { JsonPrimitive(it) } ?: JsonNull)
                put("text", text?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            is ReadMarker -> buildJsonObject {
                put("op", "read_marker")
                put("convo_id", convoID)
                put("up_to_seq", upToSeq)
            }
            is Ack -> buildJsonObject {
                put("op", "ack")
                put("cursor", cursor)
            }
            is Viewing -> buildJsonObject {
                put("op", "viewing")
                put("convo_id", convoID?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            is AgentRequest -> buildJsonObject {
                put("op", "agent_request")
                put("request_id", requestID)
                put("agent_device_id", agentDeviceID)
                put("method", method)
                put("params", parseJsonObjectOrNull(paramsJson) ?: JsonObject(emptyMap()))
            }
        }
        return obj.toString()
    }
}
