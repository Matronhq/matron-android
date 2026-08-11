package chat.matron.android.events

import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonObject

/// One agent asking the user's permission to spawn a child agent session on
/// another of their devices — the journal's server-minted consent card
/// (matron-journal `src/ws.js`, the `spawn_request` park path;
/// `docs/superpowers/specs/2026-08-11-spawn-outcome-events-design.md`).
///
/// It arrives as a `permission_request` event whose payload carries
/// `kind: "agent_spawn"`, published into the PARENT's own conversation. Like
/// `AgentChatRequest` it is client-only — the journal withholds the card
/// (and the child's unapproved seed prompt it carries as [task]) from every
/// agent-facing replay, including the very agent that asked for it.
///
/// Deliberately NOT modelled as an `AskUserEvent`, for the same reason as
/// `AgentChatRequest`: the answer is `POST /agent-spawn/answer`, over HTTP,
/// not a `prompt_reply` in the timeline — and who is asking, on whose
/// device, to do what, are the facts a consent decision rests on.
///
/// Resolution is NOT tracked here or persisted locally: it is derived from a
/// `spawn_outcome` event landing in the same conversation with a matching
/// [requestId] (see [SpawnOutcome]) — the durable, cross-device record the
/// spawn-outcome-events design exists to provide.
data class AgentSpawnRequest(
    /// The spawn row's id. The only field `POST /agent-spawn/answer` needs
    /// besides the decision, and the correlation key a `spawn_outcome` event
    /// resolves this card by.
    val requestId: String,
    val fromDeviceId: Long,
    /// The requesting agent's device name, already sanitised and capped
    /// server-side. Null if the journal had no name for it.
    val fromName: String?,
    /// The parent conversation the card is filed under — the same one the
    /// eventual `spawn_outcome` lands in. Null if the journal predates the
    /// field.
    val fromConvoId: String?,
    val fromConvoTitle: String?,
    /// The device the child session would run on.
    val targetDeviceId: Long,
    val targetName: String?,
    /// Where the child session would run. Empty when the journal had no
    /// value — display-only, never gates parsing.
    val workdir: String,
    /// The child's seed prompt, unapproved until the user consents. Never
    /// forwarded to any agent until the card is answered.
    val task: String,
    val topic: String?,
) {
    /// The name to show for the requester, falling back to the device id
    /// when the journal had no name for it.
    val requesterLabel: String
        get() = fromName ?: "Device $fromDeviceId"

    /// The name to show for the target device, same fallback as
    /// [requesterLabel].
    val targetLabel: String
        get() = targetName ?: "Device $targetDeviceId"

    /// One line summarising what is being asked: the topic when the
    /// requester gave one, otherwise the first line of [task] — never the
    /// whole (possibly multi-line) seed prompt.
    val headline: String
        get() = topic ?: task.substringBefore('\n')

    /// "dev-2 — CI triage", or just [requesterLabel] when the journal named
    /// no parent conversation. Mirrors `AgentChatRequest.fromLabel`'s
    /// name-plus-session convention for the card's "From" detail row.
    val fromLabel: String
        get() = fromConvoTitle?.let { "$requesterLabel — $it" } ?: requesterLabel

    companion object {
        /// Parses a `permission_request` payload, or null if this is not an
        /// agent-spawn card. Strict only about the two fields
        /// `POST /agent-spawn/answer` and the card body actually need — a
        /// string `request_id` and a non-empty `task` — mirroring
        /// `AgentChatRequest.parse`'s stance that a card we cannot answer or
        /// meaningfully render must fall back to the generic permission
        /// rendering rather than draw buttons that would 400 or a body with
        /// nothing in it.
        fun parse(payload: JsonObject): AgentSpawnRequest? {
            if (payload.stringOrNull("kind") != "agent_spawn") return null
            val requestId = payload.stringOrNull("request_id")?.takeIf { it.isNotEmpty() } ?: return null
            val task = payload.stringOrNull("task")?.takeIf { it.isNotEmpty() } ?: return null
            return AgentSpawnRequest(
                requestId = requestId,
                fromDeviceId = payload.longOrNull("from_device_id") ?: 0L,
                fromName = nonEmpty(payload.stringOrNull("from_name")),
                fromConvoId = nonEmpty(payload.stringOrNull("from_convo_id")),
                fromConvoTitle = nonEmpty(payload.stringOrNull("from_convo_title")),
                targetDeviceId = payload.longOrNull("target_device_id") ?: 0L,
                targetName = nonEmpty(payload.stringOrNull("target_name")),
                workdir = payload.stringOrNull("workdir") ?: "",
                task = task,
                topic = nonEmpty(payload.stringOrNull("topic")),
            )
        }

        /// The journal defaults optional display fields to `""` rather than
        /// omitting them, so "absent" and "empty" arrive identically —
        /// collapse both to null, mirroring `AgentChatRequest`'s
        /// `topic`/`justification` handling.
        private fun nonEmpty(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/// Where one agent-spawn consent card is in its short life.
///
/// Unlike `AgentChatCardState`, `Resolved` is never written to local
/// storage: the journal's `spawn_outcome` event is the durable,
/// cross-device record of the outcome (started/declined/expired/failed), so
/// there is nothing for the client to remember between launches — a fresh
/// snapshot replay reconstructs it. `Resolved` is reserved for that real,
/// journal-sent event — see [Unavailable] for the two cases that stop the
/// card waiting WITHOUT one.
sealed class AgentSpawnCardState {
    data object Idle : AgentSpawnCardState()
    data object Sending : AgentSpawnCardState()
    data class Resolved(val outcome: SpawnOutcome) : AgentSpawnCardState()
    data class Failed(val message: String) : AgentSpawnCardState()

    /// The card stopped waiting for an answer with no durable [SpawnOutcome]
    /// behind it: a 409 from `POST /agent-spawn/answer` (answered/expired on
    /// another device — the real `spawn_outcome` event, once it arrives,
    /// supersedes this via `ChatViewModel.agentSpawnState`'s precedence), or
    /// no answerer wired at all (previews, tests, a screen that never wires
    /// one). Kept distinct from `Resolved` — reusing a synthetic
    /// `SpawnOutcome(outcome = "expired")` here would show the journal's own
    /// "Spawn request expired" copy for a case the journal never actually
    /// resolved; the plan's Global Constraint reserves that copy for a real
    /// `expired` outcome and calls for "request no longer waiting" here
    /// instead (matching agent-chat's `AgentChatCardState.Expired`).
    data object Unavailable : AgentSpawnCardState()
}
