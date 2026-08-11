package chat.matron.android.events

import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.JsonObject

/// The journal's durable record of how one agent-spawn request was resolved
/// — a `spawn_outcome` event, journal-authored, appended into the same
/// conversation the `agent_spawn` card (see [AgentSpawnRequest]) was
/// published into
/// (`docs/superpowers/specs/2026-08-11-spawn-outcome-events-design.md`).
///
/// Unlike the card, this event is NOT client-only: the parent agent owns the
/// conversation and is entitled to learn the outcome of its own ask, so it
/// reaches both the client and the agent via ordinary fan-out/replay. A card
/// is resolved iff a `SpawnOutcome` with a matching [requestId] exists in
/// the same conversation — there is no other signal and nothing persisted
/// locally (see `AgentSpawnCardState`).
data class SpawnOutcome(
    /// Correlates back to `AgentSpawnRequest.requestId` — the card payload
    /// and this event carry the same value.
    val requestId: String,
    /// One of `started | declined | expired | failed`, or an outcome string
    /// this client doesn't yet recognise — never rejected outright, so a
    /// journal running ahead of this client still resolves the card, just
    /// with generic copy (see [displayLine]).
    val outcome: String,
    /// The new room the child session runs in. Present only for `started`.
    val roomId: String?,
    /// The child session's own conversation id. Present only for `started`.
    val childConvoId: String?,
    /// Sanitised failure code. Present only for `failed`.
    val errorCode: String?,
) {
    /// The one-line resolution copy a resolved card or timeline row shows.
    /// Mirrors the journal server's own `snippetOf` mapping
    /// (matron-journal `src/journal.js`) for the four known outcomes, plus
    /// the [errorCode] suffix a `failed` outcome carries when the journal
    /// sent one.
    val displayLine: String
        get() = when (outcome) {
            "started" -> "🚀 Spawned session started"
            "declined" -> "🚫 Spawn declined"
            "expired" -> "⌛ Spawn request expired"
            "failed" -> "❌ Spawn failed" + (errorCode?.let { " — $it" } ?: "")
            else -> "Spawn request resolved"
        }

    companion object {
        /// Parses a `spawn_outcome` payload, or null if it carries neither a
        /// [requestId] nor an [outcome] — the two fields a card cannot be
        /// resolved without. An unrecognised [outcome] string still parses;
        /// only [displayLine] falls back to generic copy for it.
        fun parse(payload: JsonObject): SpawnOutcome? {
            val requestId = payload.stringOrNull("request_id")?.takeIf { it.isNotEmpty() } ?: return null
            val outcome = payload.stringOrNull("outcome")?.takeIf { it.isNotEmpty() } ?: return null
            return SpawnOutcome(
                requestId = requestId,
                outcome = outcome,
                roomId = payload.stringOrNull("room_id")?.takeIf { it.isNotEmpty() },
                childConvoId = payload.stringOrNull("child_convo_id")?.takeIf { it.isNotEmpty() },
                errorCode = payload.stringOrNull("error_code")?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
