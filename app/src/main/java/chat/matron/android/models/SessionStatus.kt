package chat.matron.android.models

import java.time.Instant

/// Per-conversation session status published by the bridge at turn end
/// (journal `status` ephemeral): model name, a context-window gauge, and
/// account rate limits. Parts are independently optional — the bridge omits
/// what it doesn't know, and absent parts mean "unchanged", so the held value
/// merges updates rather than replacing wholesale.
data class SessionStatus(
    var model: String? = null,
    var context: Context? = null,
    var limits: List<Limit>? = null,
    /// Logged-in account email on the bridge's machine. Absent when the bridge
    /// can't read it — e.g. API-key accounts.
    var email: String? = null,
    /// For a subagent child conversation, the `tool_use_id` of the parent's
    /// spawning Task call. `null` for normal conversations.
    var taskRef: String? = null,
) {
    /// Context-window gauge — an estimate computed by the bridge from the last
    /// request's usage block, not /context's exact accounting.
    data class Context(val tokens: Int, val window: Int, val pct: Int)

    /// One account rate-limit line (session / week / per-model week). `resets`
    /// is the raw text claude printed; `resetsAt` is the bridge's normalised
    /// timestamp, `null` when the bridge couldn't parse the text — renderers
    /// fall back to showing `resets` verbatim.
    data class Limit(
        val label: String,
        val percent: Int,
        val resets: String?,
        val resetsAt: Instant?,
    )

    /// Merge an update: each part replaces the held value only when the frame
    /// carries it (absent = unchanged, per the status protocol).
    fun apply(update: SessionStatusUpdate) {
        update.model?.let { model = it }
        update.context?.let { context = it }
        update.limits?.let { limits = it }
        update.email?.let { email = it }
        update.taskRef?.let { taskRef = it }
    }
}

/// One decoded `status` ephemeral frame.
///
/// No parameter defaults, deliberately: every constructor names every field, so
/// merge sites (SessionStatus.apply, the sync engine's replay cache) can't
/// silently drop a newly added one.
data class SessionStatusUpdate(
    val convoID: String,
    val model: String?,
    val context: SessionStatus.Context?,
    val limits: List<SessionStatus.Limit>?,
    val email: String?,
    /// The spawning Task call's `tool_use_id` for a subagent child. `null` when
    /// the frame doesn't carry one.
    val taskRef: String?,
)
