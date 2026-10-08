package chat.matron.android.models

import java.time.Instant

/// Per-conversation session status published by the bridge at turn end
/// (journal `status` ephemeral): model name, a context-window gauge, and
/// account rate limits. Parts are independently optional — the bridge omits
/// what it doesn't know, and absent parts mean "unchanged", so the held value
/// merges updates rather than replacing wholesale.
data class SessionStatus(
    val model: String? = null,
    val context: Context? = null,
    val limits: List<Limit>? = null,
    /// Logged-in account email on the bridge's machine. Absent when the bridge
    /// can't read it — e.g. API-key accounts.
    val email: String? = null,
    /// For a subagent child conversation, the `tool_use_id` of the parent's
    /// spawning Task call. `null` for normal conversations.
    val taskRef: String? = null,
    /// The session's absolute working directory on the bridge machine.
    val workdir: String? = null,
    /// Last host CPU/RAM sample from the bridge machine.
    val vitals: Vitals? = null,
    /// Model aliases this session can switch to — the palette's `/model`
    /// argument suggestions. Optional, and the optionality is load-bearing:
    /// `null` means this bridge doesn't say (an older one, or an agent the
    /// bridge can't enumerate), `[]` means it says there is nothing to offer.
    /// Both render as no suggestions; only `[]` may overwrite a known list
    /// (apple #163).
    val modelOptions: List<Option>? = null,
    /// Effort levels this session accepts — `/effort`'s suggestions. Same
    /// absent-versus-empty rule as [modelOptions].
    val effortLevels: List<Option>? = null,
    /// The session's current effort level, or null when nothing is tracking
    /// one. The bridge tracks this optimistically (nothing reads it back off
    /// the TUI) and never guesses; a restart or resume drops it back to null.
    /// Renderers show nothing at all when it's null.
    val effort: String? = null,
) {
    /// One value the bridge offers for a session-scoped command argument — a
    /// model alias for `/model`, an effort level for `/effort`. The bridge
    /// owns these lists (they're agent-dependent), so they travel on the
    /// status frame rather than being copied into the app's catalog. [label]
    /// is absent when it would only repeat [value].
    data class Option(val value: String, val label: String?)

    /// Context-window gauge — an estimate computed by the bridge from the last
    /// request's usage block, not /context's exact accounting.
    data class Context(val tokens: Int, val window: Int, val pct: Int)

    /// Host CPU/RAM sample from the bridge machine, published top-level
    /// (deliberately NOT a [Limit] — these are machine metrics, not account
    /// subscription meters, and must never render as one). Either half can be
    /// null: CPU needs two sampler ticks, so the first frames after a bridge
    /// boot carry RAM alone.
    data class Vitals(val cpuPct: Int?, val ramPct: Int?)

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
    /// carries it (absent = unchanged, per the status protocol). Returns a new
    /// instance — StateFlow conflates by equality, so mutating in place would
    /// mean an object always equals its (mutated) former self and the flow
    /// would never re-emit.
    fun merged(update: SessionStatusUpdate): SessionStatus = SessionStatus(
        model = update.model ?: model,
        context = update.context ?: context,
        limits = update.limits ?: limits,
        email = update.email ?: email,
        taskRef = update.taskRef ?: taskRef,
        workdir = update.workdir ?: workdir,
        vitals = update.vitals ?: vitals,
        // An empty list arrives as `[]`, not null, and legitimately replaces a
        // held one — "this agent offers nothing" is a statement, absence is
        // silence.
        modelOptions = update.modelOptions ?: modelOptions,
        effortLevels = update.effortLevels ?: effortLevels,
        // Effort is the one tri-state field: `null` is silence and changes
        // nothing, `Cleared` is the bridge disowning the level it was
        // tracking, and only `Set` writes one.
        effort = when (val e = update.effort) {
            is SessionStatusUpdate.Effort.Set -> e.level
            SessionStatusUpdate.Effort.Cleared -> null
            null -> effort
        },
    )
}

/// One decoded `status` ephemeral frame.
///
/// No parameter defaults, deliberately: every constructor names every field, so
/// merge sites (SessionStatus.merged, the sync engine's replay cache) can't
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
    /// Absolute workdir on the bridge machine. `null` when absent.
    val workdir: String?,
    /// Host CPU/RAM sample. `null` when absent or carrying no numbers.
    val vitals: SessionStatus.Vitals?,
    /// Model aliases and effort levels the bridge offers for this session
    /// (see [SessionStatus.modelOptions]). `null` when the frame omits the
    /// field — distinct from an empty list, which the decoder preserves.
    val modelOptions: List<SessionStatus.Option>?,
    val effortLevels: List<SessionStatus.Option>?,
    /// This frame's statement about the effort level (see [Effort]). `null`
    /// when the frame carries no `effort` key at all.
    val effort: Effort?,
) {
    /// A frame's statement about the session's effort level. The field is
    /// tri-state on the wire, and the three states are genuinely different:
    ///
    /// - a string — the bridge is tracking this level ([Set]);
    /// - an explicit JSON null — the bridge is tracking none, republished on
    ///   every frame while unknown so a dropped clear can't strand a stale
    ///   level in a client ([Cleared]);
    /// - the key absent — the frame says nothing, which is what a Codex
    ///   session (no effort concept) and any pre-tri-state bridge send.
    ///
    /// Absence is the only one that leaves a held value standing, so a
    /// decoder that folds null into absence would keep showing a level the
    /// bridge has disowned. `null` here is absence; null decodes to [Cleared].
    sealed interface Effort {
        data class Set(val level: String) : Effort
        data object Cleared : Effort
    }
}

