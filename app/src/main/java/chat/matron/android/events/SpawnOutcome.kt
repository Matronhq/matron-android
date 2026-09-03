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
    val roomId: String? = null,
    /// The child session's own conversation id. Present only for `started`.
    val childConvoId: String? = null,
    /// Sanitised failure code. Present only for `failed`.
    val errorCode: String? = null,
) {
    /// The one-line resolution copy the timeline row (`SpawnOutcomeRow`) and
    /// a resolved card show. Starts from [baseLines] — the journal server's
    /// own `snippetOf` string for the outcome — then layers the
    /// [errorCode] suffix a `failed` outcome carries when the journal sent
    /// one, and a neutral "resolved" line for an outcome this client
    /// doesn't recognise (`baseLines` has no entry for it).
    ///
    /// Deliberately NOT what `JournalStore`'s chat-list snippet uses — that
    /// path must stay a byte-exact mirror of the server's `snippetOf`
    /// (`baseSnippet`), because it also renders snapshot-sourced rows the
    /// server itself produced; this line only ever backs a live-mapped
    /// timeline row.
    val displayLine: String
        get() {
            val base = baseLines[outcome] ?: return "Spawn request resolved"
            return if (outcome == "failed") base + (errorCode?.let { " — $it" } ?: "") else base
        }

    /// The room to jump to via a resolved card's or `SpawnOutcomeRow`'s
    /// "Open" action — present only for a `started` outcome, the only one
    /// [roomId] is ever non-null for. The single source both call sites
    /// (`AgentSpawnRequestCard`'s `ResolvedRow` and `TimelineItemView`'s
    /// `SpawnOutcomeRow` branch) read, so they can never disagree about when
    /// to show the button.
    val openRoomId: String?
        get() = roomId.takeIf { outcome == "started" }

    companion object {
        /// The journal server's own outcome→copy mapping, byte-exact
        /// (matron-journal `src/journal.js` `snippetOf`) — no error-code
        /// suffix, no "resolved" fallback. The single source of truth both
        /// [displayLine] and [baseSnippet] build from, so the two can never
        /// silently drift apart.
        private val baseLines: Map<String, String> = mapOf(
            "started" to "🚀 Spawned session started",
            "declined" to "🚫 Spawn declined",
            "expired" to "⌛ Spawn request expired",
            "failed" to "❌ Spawn failed",
        )

        /// The server-mirror snippet for [outcome] — `"[spawn_outcome]"` for
        /// anything [baseLines] has no entry for, exactly like an agent
        /// publishing an outcome string the server doesn't recognise either.
        /// `JournalStore`'s snippet path must call this, never [displayLine]:
        /// a snapshot row's snippet was minted server-side, so a locally
        /// computed string that disagrees with it would flip-flop the
        /// chat-list row between a live-mapped render and a post-snapshot one.
        fun baseSnippet(outcome: String): String = baseLines[outcome] ?: "[spawn_outcome]"

        /// Parses a `spawn_outcome` payload, or null if it carries neither a
        /// [requestId] nor an [outcome] — the two fields a card cannot be
        /// resolved without. An unrecognised [outcome] string still parses;
        /// only [displayLine]/[baseSnippet] fall back to generic copy for it.
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
