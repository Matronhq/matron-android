package chat.matron.android.journal

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/// The two age rules that can rewrite a stored event payload, as one pure
/// function shared by every writer. Port of matron-apple's `EventTombstone`
/// (#212).
///
/// There are exactly two writers of an aged-out payload — the insert paths
/// (`JournalStore.applyJournal` / `insertHistory`, which tombstone a row that
/// is already past a cutoff as it lands) and the background sweeps
/// (`JournalStore.purgeExpiredToolOutputSnippets(now)` /
/// `applyRetention(now)`). They MUST agree: the sweeps skip everything at or
/// below a persisted watermark, so a row older than that watermark is only
/// ever correct because the insert path applied the identical rule on the way
/// in.
///
/// Both rules are idempotent, and [apply] returns `null` when it would change
/// nothing — which is how a sweep counts "rows touched" without re-writing
/// the whole range on every pass.
object EventTombstone {
    /// The journal server's tool-log TTL (matron-journal docs/protocol.md
    /// Retention): live-streamed output is purged server-side 24 h after the
    /// event, and the client rules make the same TTL binding on local caches.
    /// The single definition — `JournalTimelineMapper.TOOL_LOG_TTL_SECONDS`
    /// derives from it.
    const val TOOL_LOG_TTL_MS: Long = 24L * 3600 * 1000

    /// How long this device keeps tool-output and diff BODIES (spec §4
    /// decision 1). The server still has them; the local mirror does not,
    /// and the UI already knows how to render a tombstone.
    const val RETENTION_WINDOW_MS: Long = 30L * 24 * 3600 * 1000

    /// How much of a tool-output `command` survives retention (spec §4
    /// decision 2), before the `…` marker.
    const val COMMAND_STUB_LENGTH = 200

    /// The rewritten payload, or `null` when neither rule changes anything.
    /// [tsMs] / [nowMs] are epoch milliseconds, like every store timestamp.
    ///
    /// - `tool_output` past [RETENTION_WINDOW_MS]: body keys go, `command` is
    ///   truncated to [COMMAND_STUB_LENGTH] + `…`, `expired: true`.
    ///   `exit_code`, `denied`, `truncated` and `message_ref` stay, so the
    ///   timeline can still say what ran and how it ended.
    /// - `tool_output` past [TOOL_LOG_TTL_MS] AND `live_log: true`: the same
    ///   body strip with the command left whole. The `live_log` gate is
    ///   deliberate and is the shipped behaviour — an offloaded/legacy
    ///   tool_output carries a durable snippet that no 24 h TTL applies to
    ///   (`JournalStoreTest.purgeLeavesYoungAndNonLiveLogRows`).
    /// - `diff` past [RETENTION_WINDOW_MS]: `diff` and `snippet` go, every
    ///   other key stays so the card can still name the file and its counts.
    fun apply(payload: JsonObject, type: String, tsMs: Long, nowMs: Long): JsonObject? = when (type) {
        JournalEventType.TOOL_OUTPUT -> when {
            tsMs + RETENTION_WINDOW_MS <= nowMs ->
                rewrite(payload, stripping = setOf("snippet", "live_log"), truncateCommand = true)
            tsMs + TOOL_LOG_TTL_MS <= nowMs && payload.boolOrNull("live_log") == true ->
                rewrite(payload, stripping = setOf("snippet", "live_log"), truncateCommand = false)
            else -> null
        }
        JournalEventType.DIFF ->
            if (tsMs + RETENTION_WINDOW_MS <= nowMs) {
                rewrite(payload, stripping = setOf("diff", "snippet"), truncateCommand = false)
            } else {
                null
            }
        else -> null
    }

    /// Applies a strip + `expired: true` (+ optional command truncation) and
    /// reports `null` when every one of those was already true — the
    /// idempotence the sweeps rely on.
    ///
    /// `blob_ref` is NULLED rather than deleted when present: that is the
    /// shipped tombstone shape, both from the server and from the sweep this
    /// replaces, and readers take it as `stringOrNull("blob_ref")` so null
    /// and absent are indistinguishable to them. An absent key stays absent,
    /// so a server-minted tombstone does not get a pointless rewrite.
    private fun rewrite(payload: JsonObject, stripping: Set<String>, truncateCommand: Boolean): JsonObject? {
        val out = payload.toMutableMap()
        var changed = false
        for (key in stripping) if (out.remove(key) != null) changed = true
        val blobRef = out["blob_ref"]
        if (blobRef != null && blobRef !is JsonNull) {
            out["blob_ref"] = JsonNull
            changed = true
        }
        if (payload.boolOrNull("expired") != true) {
            out["expired"] = JsonPrimitive(true)
            changed = true
        }
        if (truncateCommand) {
            val command = payload.stringOrNull("command")
            if (command != null && command.length > COMMAND_STUB_LENGTH) {
                out["command"] = JsonPrimitive(command.take(COMMAND_STUB_LENGTH) + "…")
                changed = true
            }
        }
        return if (changed) JsonObject(out) else null
    }
}
