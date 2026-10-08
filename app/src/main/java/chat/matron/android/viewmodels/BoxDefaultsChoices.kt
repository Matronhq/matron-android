package chat.matron.android.viewmodels

import chat.matron.android.journal.BoxDefaults

/// The words and choices Settings ▸ Devices ▸ New sessions offers for one
/// agent box. Model and effort choices follow the box's agent: Claude gets
/// its aliases and effort levels, Codex a free-text model id and its own
/// effort levels. Ported from matron-apple's `BoxDefaults+Choices`.
object BoxDefaultsChoices {
    /// One picker option: [value] is what the journal stores (null = the
    /// "default" entry), [label] what the user reads.
    data class Choice(val value: String?, val label: String)

    const val HELP_TEXT =
        "What a session started on this box gets when nobody names an agent, model or effort. " +
            "Box default uses the box's own setting; for Claude, your own default model comes first."
    const val CODEX_MODEL_HELP = "A Codex model id, e.g. gpt-5.1-codex. Leave empty for Codex's own default."

    val agentChoices = listOf(
        Choice(null, "Box default"),
        Choice("claude", "Claude"),
        Choice("codex", "Codex"),
    )

    val claudeModelChoices = listOf(
        Choice(null, "Box default"),
        Choice("opus", "Opus"),
        Choice("opus[1m]", "Opus (1M)"),
        Choice("sonnet", "Sonnet"),
        Choice("sonnet[1m]", "Sonnet (1M)"),
        Choice("haiku", "Haiku"),
        Choice("opusplan", "Opus Plan"),
        Choice("fable", "Fable"),
    )

    val claudeEffortChoices = listOf(
        Choice(null, "Box default"),
        Choice("low", "Low"),
        Choice("medium", "Medium"),
        Choice("high", "High"),
        Choice("xhigh", "X-High"),
        Choice("max", "Max"),
    )

    /// Codex's own levels (`minimal` is Codex-only, `max` Claude-only); the
    /// journal takes all of them.
    val codexEffortChoices = listOf(
        Choice(null, "Codex default"),
        Choice("minimal", "Minimal"),
        Choice("low", "Low"),
        Choice("medium", "Medium"),
        Choice("high", "High"),
        Choice("xhigh", "X-High"),
    )

    /// The model picker's options on a Claude box (a stored value outside
    /// the list is kept, under its own name). Codex boxes take a typed id
    /// instead ([codexModel]).
    fun modelChoices(defaults: BoxDefaults): List<Choice> = including(defaults.model, claudeModelChoices)

    fun effortChoices(defaults: BoxDefaults): List<Choice> =
        including(defaults.effort, if (defaults.agent == "codex") codexEffortChoices else claudeEffortChoices)

    /// One line for the Devices row: "Claude · Opus · High", "Codex ·
    /// Codex's own model". With no agent set, the bridge applies no box
    /// model or effort, so they are not shown.
    fun summary(defaults: BoxDefaults): String {
        val agent = defaults.agent ?: return "Box default"
        val parts = when (agent) {
            "claude" -> mutableListOf(
                "Claude",
                defaults.model?.let { label(it, claudeModelChoices) } ?: "Your default model",
            )
            "codex" -> mutableListOf("Codex", defaults.model ?: "Codex's own model")
            else -> listOfNotNull(agent, defaults.model).toMutableList()
        }
        defaults.effort?.let { parts.add(label(it, claudeEffortChoices + codexEffortChoices)) }
        return parts.joinToString(" · ")
    }

    /// A typed Codex model id, checked the way the journal checks it
    /// (trimmed, lowercased, ≤64, `^[a-z0-9][a-z0-9.-]*(\[1m\])?$`), so a
    /// typo is caught before the round-trip.
    sealed interface ModelDraft {
        /// [model] null = blank = Codex's own default.
        data class Valid(val model: String?) : ModelDraft
        data object Invalid : ModelDraft
    }

    private val MODEL_PATTERN = Regex("""^[a-z0-9][a-z0-9.-]*(\[1m\])?$""")

    fun codexModel(draft: String): ModelDraft {
        val value = draft.trim().lowercase()
        if (value.isEmpty()) return ModelDraft.Valid(null)
        if (value.length > 64 || !MODEL_PATTERN.matches(value)) return ModelDraft.Invalid
        return ModelDraft.Valid(value)
    }

    /// [base], plus a stored value outside it under its own name, so the
    /// picker shows what is really set (one another app or the Coordinator
    /// wrote).
    private fun including(value: String?, base: List<Choice>): List<Choice> =
        if (value == null || base.any { it.value == value }) base else base + Choice(value, value)

    fun label(value: String?, choices: List<Choice>): String =
        choices.firstOrNull { it.value == value }?.label ?: value ?: choices.first().label
}

/// The picker's title.
val BoxDefaults.Key.title: String
    get() = when (this) {
        BoxDefaults.Key.AGENT -> "Agent"
        BoxDefaults.Key.MODEL -> "Model"
        BoxDefaults.Key.EFFORT -> "Effort"
    }

/// The setting's name inside an error message.
val BoxDefaults.Key.errorName: String
    get() = when (this) {
        BoxDefaults.Key.AGENT -> "agent"
        BoxDefaults.Key.MODEL -> "model"
        BoxDefaults.Key.EFFORT -> "effort"
    }
