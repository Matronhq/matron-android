package chat.matron.android.journal

import chat.matron.android.models.AttachmentBatchTag
import chat.matron.android.models.SessionStatus
import chat.matron.android.models.SessionStatusUpdate
import chat.matron.android.viewmodels.BoxStatus
import java.time.Instant
import kotlinx.serialization.json.JsonElement
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

    /// One bridge summary pass — a TOC entry anchored at its own seq.
    /// Deliberately NOT in [MESSAGE_TYPES]: no snippet, no unread bump, no
    /// activity timestamp (the journal server applies the same exclusion).
    const val SUMMARY = "summary"
    /// The durable resolution of an `agent_spawn` consent card — journal
    /// -authored, appended into the parent's own conversation (see
    /// `chat.matron.android.events.SpawnOutcome`).
    const val SPAWN_OUTCOME = "spawn_outcome"
    /// Tracker marker, written by the journal itself on every mutating
    /// `/items` route (protocol.md "Items"). An invalidation signal for the
    /// local item cache, never a message: not in [MESSAGE_TYPES], and the
    /// timeline renders nothing for it until the inline cards port.
    const val ITEM = "item"
    /// Mission lifecycle marker (`created` / `joined` / `updated` / `closed`),
    /// written by the journal from the `/missions` routes (protocol.md
    /// "Missions & milestones → Marker events"). An invalidation signal for
    /// the local mission cache plus a one-line inline notice; never a
    /// message — not in [MESSAGE_TYPES], no unread, no snippet, no push.
    const val MISSION = "mission"
    /// One milestone, appended to the conversation it was posted in. Its OWN
    /// seq is the milestone's anchor — the inline card and the jump target.
    /// Not in [MESSAGE_TYPES] either.
    const val MILESTONE = "milestone"
    /// The user's memories changed (`saved` / `deleted`), written by the
    /// journal from the `/memories` routes (protocol.md "Memories → Marker
    /// event"). An invalidation signal for `MemoriesSync`; quiet in the
    /// transcript — not in [MESSAGE_TYPES], no unread, no snippet, no push.
    const val MEMORY = "memory"
    /// A Coordinator routine was saved, deleted or fired (protocol.md
    /// "Coordinator routines → Marker event"). Like [MEMORY], not in
    /// [MESSAGE_TYPES]: no unread, no snippet, no push — a timeline row only.
    const val ROUTINE = "routine"
    /// The conversation gained or lost the Coordinator role (`assigned` /
    /// `released`). A marker, not in [MESSAGE_TYPES].
    const val COORDINATOR = "coordinator"
    /// The Coordinator answered a consent card (matron-journal
    /// `src/consent-answer.js`). Client-only and outside [MESSAGE_TYPES]: no
    /// unread, no snippet, no push — a timeline row only.
    const val CONSENT_DECISION = "consent_decision"

    /// Payload key on the plain `text` twin the journal appends after a
    /// card-worthy `item` marker so pre-tracker clients still see the turn
    /// (spec "Old-client fallback"). A client that renders `item` markers
    /// hides these — one guard, kept until the journal stops emitting them.
    const val FALLBACK_FOR_KEY = "fallback_for"

    /// Infix in a subagent child's convo id: `<parent>:sub:<agentId>`.
    const val CHILD_CONVO_INFIX = ":sub:"

    /// Markers the bridge puts at the head of every agent-chat room title
    /// (`↔️ [ab] mac ↔ dev-z`, matron-bridge#225/#228; `🔗 ` is the legacy
    /// marker rooms minted before #228 still carry). A room is born by an
    /// agent's `agent_chat_start`, not by the user, so it must never
    /// auto-open — the title, carried by `convo_meta`, is the only frame
    /// that tells a room apart from the session the user just started.
    val AGENT_ROOM_TITLE_MARKERS: List<String> = listOf("↔️ ", "🔗 ")

    /// The marker the bridge puts ahead of the short on every earned title
    /// of a session another agent started (`🐣 [ab] Title`,
    /// matron-bridge#227).
    const val SPAWNED_SESSION_TITLE_MARKER = "🐣 "

    /// Whether a title is an agent-chat room's. A room's title either leads
    /// with a marker ([AGENT_ROOM_TITLE_MARKERS]) or, since 2026-08-19, is
    /// its two sides with the arrow between them: `G:0b ↔️ D:26 — topic`
    /// (matron-bridge lib/agent-chat.js). A session's own title leads with
    /// its short (`[ab] `, behind `🐣 ` when another agent spawned it) and a
    /// room's never does, so an arrow in the user's own words does not make
    /// a session a room. Ported from matron-apple's
    /// `JournalEventType.isAgentRoomTitle`.
    fun isAgentRoomTitle(title: String): Boolean {
        if (AGENT_ROOM_TITLE_MARKERS.any { title.startsWith(it) }) return true
        if (leadsWithSessionShort(title)) return false
        // The arrow with or without its emoji selector.
        return title.replace("\uFE0F", "").contains(" \u2194 ")
    }

    /// `[ab] ` at the head of the title (behind `🐣 ` when spawned): two
    /// letters or digits in brackets, then a space. Counted in code points,
    /// so a non-BMP letter counts once, as a Swift `Character` does.
    private fun leadsWithSessionShort(title: String): Boolean {
        val rest = title.removePrefix(SPAWNED_SESSION_TITLE_MARKER)
        val cps = rest.codePoints().limit(5).toArray()
        return cps.size == 5 && cps[0] == '['.code && cps[3] == ']'.code && cps[4] == ' '.code &&
            Character.isLetterOrDigit(cps[1]) && Character.isLetterOrDigit(cps[2])
    }

    /// Types that bump unread counts and set the conversation snippet.
    /// `SPAWN_OUTCOME` joins the set for the same reason the card
    /// (`PERMISSION_REQUEST`) is in it: the chat-list row must stop
    /// advertising "🤝 Agent spawn request" once the ask is settled, and an
    /// unresolved-then-expired card should surface as unread (mirrors the
    /// journal server's `MESSAGE_TYPES`, matron-journal `src/journal.js`).
    val MESSAGE_TYPES: Set<String> = setOf(
        TEXT, TOOL_OUTPUT, DIFF, PROMPT, PERMISSION_REQUEST, FILE, IMAGE, SPAWN_OUTCOME,
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

/// Payload-key accessors. The wire key names ("body"/"snippet"/"diff") live
/// here so every reader agrees on them and precedence lives in one place.
fun JournalEvent.body(): String? = payload.stringOrNull("body")

/// True for the journal's old-client twin of an `item` marker (a `text`
/// event flagged `fallback_for`). Hidden from the timeline (the marker card
/// is what renders), never indexed for search, and skipped by outbox
/// delivery confirmation — but it still bumps unread, activity and the
/// snippet like any text, per the spec's "Old-client fallback": that is how
/// a question filed while the chat was closed reaches the chat list.
fun JournalEvent.isItemFallbackText(): Boolean =
    type == JournalEventType.TEXT && payload.stringOrNull(JournalEventType.FALLBACK_FOR_KEY) != null
fun JournalEvent.snippet(): String? = payload.stringOrNull("snippet")
fun JournalEvent.diff(): String? = payload.stringOrNull("diff")

/// Text fed to the full-text search index by all three feeders — live sync
/// (`JournalSyncEngine`), backward pagination (`JournalTimelineService`) and
/// the history backfill (`SearchBackfillCoordinator`): TEXT→body,
/// TOOL_OUTPUT→snippet, DIFF→diff-then-snippet; nothing else indexes. The
/// analogue of matron-apple's `JournalEvent.searchableBody(now:)`.
///
/// [now] exists because what the store no longer HOLDS must never be
/// indexed. Two rules, mirroring [EventTombstone]: nothing older than the
/// 30-day retention window for `tool_output`/`diff` (the backfill and
/// backward-pagination feeders fetch from the server, which keeps bodies
/// forever, so without this the very next pass would re-add exactly what the
/// maintenance sweep just removed), and nothing past the 24 h tool-log TTL
/// for a `live_log` `tool_output` — that half matters for the LIVE feeder
/// too: `applyJournal`/`applyJournalBatch` hand their callers the ORIGINAL
/// events while the store keeps the tombstoned ones (apple #212).
fun JournalEvent.previewText(now: Instant = Instant.now()): String? {
    val tsMs = ts.toEpochMilli()
    val nowMs = now.toEpochMilli()
    when (type) {
        JournalEventType.TOOL_OUTPUT -> {
            if (tsMs + EventTombstone.RETENTION_WINDOW_MS <= nowMs) return null
            if (payload.boolOrNull("live_log") == true && tsMs + EventTombstone.TOOL_LOG_TTL_MS <= nowMs) return null
        }
        JournalEventType.DIFF -> if (tsMs + EventTombstone.RETENTION_WINDOW_MS <= nowMs) return null
    }
    return when (type) {
        // The item marker's `fallback_for` twin is hidden from the timeline, so
        // a search hit on it would land on a row that never renders (the journal
        // server's own indexer skips it the same way).
        JournalEventType.TEXT -> if (isItemFallbackText()) null else body()
        JournalEventType.TOOL_OUTPUT -> snippet()
        JournalEventType.DIFF -> diff() ?: snippet()
        else -> null
    }
}

/// A streaming-output update. Never persisted; lost updates are harmless (the
/// finalize journal row supersedes them). [Change] captures the delta-vs-replace
/// union the wire encodes, so an update always means exactly one operation.
data class EphemeralUpdate(
    val convoID: String,
    val messageRef: String,
    val change: Change,
) {
    sealed interface Change {
        /// Append `text` to the accumulated bubble (a `text` frame). Empty when
        /// the frame carried neither key — a harmless no-op append.
        data class Delta(val text: String) : Change
        /// Replace the bubble's whole text (a `replace_text` frame; wins over a
        /// coincident `text`).
        data class Replace(val text: String) : Change
    }
}

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

/// An agent's answer to an `agent_request`. [decodeRpc] builds the [Outcome]
/// union so consumers branch on success/failure instead of re-correlating the
/// old `ok`/`result`/`errorCode` nullables.
data class RPCResponse(
    val requestID: String,
    /// Which agent device answered. Some frames omit `agent_device_id`;
    /// matron-apple's decoder likewise defaults it to 0 (a correlation id no
    /// consumer reads), so we keep the 0 fallback for wire parity.
    val agentDeviceID: Long,
    val outcome: Outcome,
) {
    sealed interface Outcome {
        /// `result` keeps the raw JSON of the method-specific result (JsonNull
        /// when the server sent no `result`) — the caller decodes its shape.
        data class Success(val result: JsonElement) : Outcome
        /// `code` is null when the server omitted the `error` object; the caller
        /// substitutes a default label when reporting the failure.
        data class Failure(val code: String?, val detail: String?) : Outcome
    }
}

/// `coordinator_convo_id` on `hello_ok` and `GET /snapshot` (Coordinator
/// redesign contract). [Absent] is a journal predating the field —
/// "unknown", never "cleared"; `Known(null)` is an authoritative "no
/// Coordinator". Ported from matron-apple's `HelloCoordinator`.
sealed interface HelloCoordinator {
    data object Absent : HelloCoordinator
    data class Known(val convoID: String?) : HelloCoordinator

    companion object {
        /// Key presence carries the meaning; an empty id reads as none.
        fun from(obj: JsonObject): HelloCoordinator =
            if (obj.containsKey("coordinator_convo_id")) {
                Known(obj.stringOrNull("coordinator_convo_id")?.takeIf { it.isNotEmpty() })
            } else {
                Absent
            }
    }
}

/// Server → client frames. Unknown `kind`s decode to null (skip); unknown
/// control ops decode to [UnknownControl] so the protocol can grow.
sealed interface ServerFrame {
    data class Journal(val event: JournalEvent) : ServerFrame
    data class Ephemeral(val update: EphemeralUpdate) : ServerFrame
    data class Activity(val update: ActivityUpdate) : ServerFrame
    data class ToolStream(val update: ToolStreamUpdate) : ServerFrame
    data class SessionStatusFrame(val update: SessionStatusUpdate) : ServerFrame
    data class RpcResponse(val response: RPCResponse) : ServerFrame
    /// [pins] is null on a journal that predates pinned desk chats (no
    /// `pins` key) and [coordinator] is [HelloCoordinator.Absent] on one
    /// that predates `coordinator_convo_id` — both "unknown", never "none".
    /// [settings] is the user's journal-held settings as of this connect —
    /// `null` from a journal that sends none.
    data class HelloOK(
        val headSeq: Long,
        val pins: List<ConvoPin>? = null,
        val coordinator: HelloCoordinator = HelloCoordinator.Absent,
        val settings: UserSettings? = null,
    ) : ServerFrame
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
    /// The user changed a journal-held setting on some device
    /// (`PATCH /settings`). Transient: nothing to replay, since the next
    /// `hello_ok` carries the current settings.
    data class SettingsFrame(val settings: UserSettings) : ServerFrame

    /// A device was renamed (`POST /devices/:id/rename`). Transient — not a
    /// journal event, carries no seq. A client that misses it picks the name
    /// up from the next snapshot's `agents` list.
    /// A device's meta changed (`POST /devices/:id/rename` or `/tag`). The
    /// frame carries the device's full current meta — a rename repeats the
    /// standing tag character and vice versa. [tagChar] null = automatic,
    /// but only when [tagCharKnown]: a server predating tags omits the key
    /// entirely, and that null means "unknown", not "cleared" (apple #158).
    data class DeviceMeta(
        val id: Long,
        val name: String,
        val tagChar: String? = null,
        val tagCharKnown: Boolean = true,
    ) : ServerFrame

    /// A box's own capacity report, fanned live to client sockets (journal
    /// PR #82) — the same shape `GET /devices` serves as `status`. Transient
    /// like [DeviceMeta]: no seq, never replayed; a client that misses one
    /// reads the stored report off the next `GET /devices`.
    data class BoxStatusFrame(val deviceID: Long, val status: BoxStatus) : ServerFrame

    /// One agent box's defaults for new sessions changed (journal "Box
    /// defaults", `PUT /devices/:id/defaults` from any device or agent) —
    /// the box's full new state. Transient like [DeviceMeta]: no seq, never
    /// replayed; a client that misses one reads `GET /devices`'s `defaults`.
    data class BoxDefaultsFrame(val update: BoxDefaultsUpdate) : ServerFrame

    /// The user's whole pin list after any change (journal "Pinned desk
    /// chats"), fanned to client sockets only. Transient like [DeviceMeta]:
    /// a client that misses one reads the list off the next `hello_ok`.
    data class PinsFrame(val pins: List<ConvoPin>) : ServerFrame

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
                "device_meta" -> {
                    // Malformed frames are skipped, not crashed on.
                    val id = obj.longOrNull("device_id") ?: return null
                    val name = obj.stringOrNull("name") ?: return null
                    // Key-presence, not value: only an explicit null clears a tag.
                    DeviceMeta(id, name, tagChar = obj.stringOrNull("tag_char"), tagCharKnown = obj.containsKey("tag_char"))
                }
                "box_status" -> {
                    val id = obj.longOrNull("device_id") ?: return null
                    val status = BoxStatus.parse(obj) ?: return null
                    BoxStatusFrame(id, status)
                }
                "box_defaults" -> {
                    val id = obj.longOrNull("device_id") ?: return null
                    val defaults = BoxDefaults.decodeState(obj) ?: return null
                    BoxDefaultsFrame(BoxDefaultsUpdate(id, defaults))
                }
                "pins" -> Pins.parseList(obj["pins"])?.let { PinsFrame(it) }
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
                // Host CPU/RAM sample — top-level `vitals`, never a limits[]
                // entry (machine metrics must not render as subscription
                // meters). Either half can be null (CPU needs two sampler
                // ticks after a bridge boot); an object carrying neither
                // number degrades to null so the merge keeps the last good
                // sample instead of blanking it.
                var vitals: SessionStatus.Vitals? = null
                status.objectOrNull("vitals")?.let { raw ->
                    val cpu = raw.intOrNull("cpu_pct")
                    val ram = raw.intOrNull("ram_pct")
                    if (cpu != null || ram != null) {
                        vitals = SessionStatus.Vitals(cpuPct = cpu, ramPct = ram)
                    }
                }
                // Session-scoped argument lists for the slash palette. Unlike
                // `limits`, an empty array is NOT collapsed to null: absent
                // means "this bridge doesn't say" and empty means "this agent
                // offers nothing", and only the second may overwrite a list
                // the app already holds. A non-empty array that yields nothing
                // is a THIRD case, and it is malformed rather than empty:
                // returning `[]` there would let a garbled frame overwrite a
                // good list with "offers nothing". Only a wire `[]` is a
                // statement, so that alone survives as `[]` (apple #163).
                fun options(key: String): List<SessionStatus.Option>? {
                    val raw = status.arrayOrNull(key) ?: return null
                    val parsed = raw.mapNotNull { entry ->
                        val obj = entry as? JsonObject ?: return@mapNotNull null
                        val value = obj.stringOrNull("value") ?: return@mapNotNull null
                        SessionStatus.Option(value, obj.stringOrNull("label"))
                    }
                    return if (raw.isEmpty() || parsed.isNotEmpty()) parsed else null
                }
                // Effort is tri-state: a missing key is silence, a JSON null is
                // the bridge disowning the level it was tracking (republished
                // on every frame while untracked, so this is the only signal
                // that arrives), and only a string sets one. Anything else is
                // not a statement about effort — say nothing.
                val effort: SessionStatusUpdate.Effort? = when (val raw = status["effort"]) {
                    null -> null
                    is JsonNull -> SessionStatusUpdate.Effort.Cleared
                    is JsonPrimitive -> if (raw.isString) SessionStatusUpdate.Effort.Set(raw.content) else null
                    else -> null
                }
                return SessionStatusFrame(SessionStatusUpdate(
                    convoID = convoID,
                    model = status.stringOrNull("model"),
                    context = context,
                    limits = limits,
                    email = status.stringOrNull("email"),
                    taskRef = status.stringOrNull("task_ref"),
                    workdir = status.stringOrNull("workdir"),
                    vitals = vitals,
                    modelOptions = options("model_options"),
                    effortLevels = options("effort_levels"),
                    effort = effort,
                ))
            }
            val ref = obj.stringOrNull("message_ref") ?: return null
            // `replace_text` wins over `text`; a frame with neither key becomes a
            // no-op empty delta (the old both-null → append-"" behavior).
            val replace = obj.stringOrNull("replace_text")
            val change = if (replace != null) {
                EphemeralUpdate.Change.Replace(replace)
            } else {
                EphemeralUpdate.Change.Delta(obj.stringOrNull("text") ?: "")
            }
            return Ephemeral(EphemeralUpdate(convoID, ref, change))
        }

        private fun decodeRpc(obj: JsonObject): ServerFrame? {
            // Only the client-side shape (a `response` object) is expected here;
            // an agent-side `request` frame is not ours to handle.
            val response = obj.objectOrNull("response") ?: return null
            val requestID = response.stringOrNull("request_id") ?: return null
            val ok = response.boolOrNull("ok") ?: return null
            val outcome: RPCResponse.Outcome = if (ok) {
                RPCResponse.Outcome.Success(response["result"] ?: JsonNull)
            } else {
                val error = response.objectOrNull("error")
                RPCResponse.Outcome.Failure(error?.stringOrNull("code"), error?.stringOrNull("detail"))
            }
            return RpcResponse(RPCResponse(
                requestID = requestID,
                agentDeviceID = response.longOrNull("agent_device_id") ?: 0,
                outcome = outcome,
            ))
        }

        private fun decodeControl(obj: JsonObject): ServerFrame? {
            val op = obj.stringOrNull("op") ?: return null
            return when (op) {
                "hello_ok" -> HelloOK(
                    headSeq = obj.longOrNull("seq") ?: 0,
                    pins = Pins.fromContainer(obj),
                    coordinator = HelloCoordinator.from(obj),
                    settings = UserSettingsDecoding.settings(obj.objectOrNull("settings")),
                )
                "settings" -> UserSettingsDecoding.settings(obj.objectOrNull("settings"))?.let { SettingsFrame(it) }
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

/// The two `send`-op media wire kinds — decided by `StagedAttachment.isImage`,
/// nothing else is valid here.
enum class MediaKind(val wire: String) {
    FILE("file"), IMAGE("image"),
}

/// Client → server operations.
sealed interface ClientOp {
    data class Hello(val token: String, val cursor: Long?) : ClientOp
    data class Send(val convoID: String, val body: String, val localID: String) : ClientOp
    /// A media `send`: `type` is the wire kind (`"file"`/`"image"`), `blobRef`
    /// the id from a prior `POST /media` upload. `caption` is the composer text
    /// this attachment left with, omitted from the payload when null/empty.
    /// `batch` marks this attachment as one of several sent together from
    /// one composer message (same opaque-payload trick as `caption`): the
    /// bridge gathers frames sharing a `batch_id` and injects them as ONE
    /// prompt instead of starting a turn on the first and queueing the rest.
    data class SendMedia(
        val convoID: String,
        val type: MediaKind,
        val blobRef: String,
        val name: String,
        val contentType: String,
        val size: Int,
        val caption: String?,
        val batch: AttachmentBatchTag?,
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
                put("type", type.wire)
                put("blob_ref", blobRef)
                put("payload", buildJsonObject {
                    put("blob_ref", blobRef)
                    put("name", name)
                    put("content_type", contentType)
                    put("size", size)
                    // Absent rather than null for a captionless send.
                    if (!caption.isNullOrEmpty()) put("caption", caption)
                    // Same absent-when-single rule: a lone attachment carries
                    // no batch keys, so an older bridge sees byte-identical
                    // frames.
                    if (batch != null) {
                        put("batch_id", batch.id)
                        put("batch_index", batch.index)
                        put("batch_total", batch.total)
                    }
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
