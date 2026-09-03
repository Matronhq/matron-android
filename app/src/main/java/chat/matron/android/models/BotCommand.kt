package chat.matron.android.models

/// One selectable argument for a slash command — a flag (`--browser`) or an
/// enumerated value (`interactive`). Static entries cover values that are
/// part of the command's grammar; session-derived entries (model aliases,
/// effort levels) arrive via the bridge's status frame in a later phase
/// (apple #161 / spec 2026-08-10 composer-argument-suggestions).
data class ArgSuggestion(
    /// Inserted verbatim into the composer: `--browser`, `claude`, `cancel`.
    val value: String,
    /// Shown instead of [value] when they differ (e.g. "X-High" for `xhigh`).
    val label: String? = null,
    /// One-line description, same role as [BotCommand.summary].
    val summary: String? = null,
    /// Lowercased values that suppress this suggestion when already on the
    /// line. Encodes the bridge's static grammar constraints — mutual
    /// exclusion is two suggestions listing each other (`--claude` ↔
    /// `--codex`), a dependency is one-way (`--browser` is Claude-only, so
    /// `--codex` suppresses it). The palette must not build a command line
    /// the bridge will refuse.
    val conflictsWith: Set<String> = emptySet(),
) {
    /// What the palette row displays.
    val displayLabel: String get() = label ?: value

    /// Flags (`--x`) compose, so several can ride one command line; plain
    /// values fill a single slot. The resolver keys re-offering off this.
    val isFlag: Boolean get() = value.startsWith("--")
}

/// Which of the bridge's session-scoped lists supplies a command's argument
/// values. These lists are agent-dependent and the bridge owns them, so they
/// ride the status frame ([SessionStatus.modelOptions] / [SessionStatus.effortLevels])
/// instead of being copied into the catalog — the catalog names the source,
/// never the values (apple #163).
enum class SessionArgSource { MODEL_OPTIONS, EFFORT_LEVELS }

/// A slash-command entry surfaced in the composer's slash palette.
///
/// The catalog is local — driven by a static list per bot kind — because the
/// bridge protocol doesn't expose a discovery endpoint yet.
data class BotCommand(
    /// Full trigger including its leading character, e.g. `/start` or `!start`.
    val trigger: String,
    /// One-line user-facing description shown in the palette.
    val summary: String,
    /// Optional argument hint, e.g. `[workdir]` or `<path>`.
    val argHint: String? = null,
    /// Statically-known argument completions, offered by the palette once the
    /// command is typed in full. Empty for free-text arguments.
    val argSuggestions: List<ArgSuggestion> = emptyList(),
    /// The bridge-owned list this command's values come from, when they
    /// aren't static. `null` for every command whose grammar the catalog
    /// knows in full; the resolver appends the session's list to
    /// [argSuggestions] when it's set.
    val sessionArgSource: SessionArgSource? = null,
)

/// Static slash-command catalogs per bot kind, plus a small filter helper used
/// by the composer's slash palette.
object BotCommandCatalog {
    /// The agent-picker flags every session-creating command accepts.
    /// Summaries match the bridge's /help text.
    private val agentFlags = listOf(
        ArgSuggestion("--claude", summary = "Use the Claude agent", conflictsWith = setOf("--codex")),
        ArgSuggestion("--codex", summary = "Use the Codex agent", conflictsWith = setOf("--claude", "--browser")),
    )

    /// Claude-only session extra; ~400M of headless Chrome, so opt-in. The
    /// bridge refuses it on a Codex line, hence the conflict.
    private val browserFlag = ArgSuggestion(
        "--browser", summary = "Add browser tools (chrome-devtools MCP)", conflictsWith = setOf("--codex"),
    )

    /// Static catalog for the Claude bridge.
    val claudeBridge: List<BotCommand> = listOf(
        // Sessions
        BotCommand(
            trigger = "/start", summary = "Start a new session",
            argHint = "[--claude|--codex] [--browser] [workdir]",
            argSuggestions = agentFlags + browserFlag,
        ),
        BotCommand(trigger = "/stop", summary = "Stop the current session"),
        BotCommand(
            trigger = "/restart", summary = "Stop and immediately resume the session",
            argHint = "[--force] [--browser]",
            argSuggestions = listOf(
                ArgSuggestion("--force", summary = "Restart immediately, even mid-turn"),
                browserFlag,
            ),
        ),
        BotCommand(
            trigger = "/resume", summary = "Resume a previous session",
            argHint = "[--claude|--codex] [n|id]", argSuggestions = agentFlags,
        ),
        BotCommand(
            trigger = "/sessions", summary = "List past sessions",
            argHint = "[--claude|--codex]", argSuggestions = agentFlags,
        ),
        BotCommand(
            trigger = "/workdir", summary = "Start a session in a different directory",
            argHint = "[--claude|--codex] <path>", argSuggestions = agentFlags,
        ),
        // Info
        BotCommand(trigger = "/status", summary = "Show current session info"),
        BotCommand(trigger = "/agent", summary = "Show the current agent"),
        BotCommand(
            trigger = "/switch", summary = "Hand this conversation to the other coding agent",
            argHint = "<claude|codex>",
            argSuggestions = listOf(
                ArgSuggestion("claude", summary = "Hand over to Claude"),
                ArgSuggestion("codex", summary = "Hand over to Codex"),
            ),
        ),
        BotCommand(trigger = "/working", summary = "Toggle tool call visibility"),
        BotCommand(trigger = "/mcp", summary = "Show MCP server status"),
        // The values for these two are the bridge's to publish: the model
        // aliases are agent-dependent, and the effort levels are a list the
        // bridge enumerates. No suggestions until a status frame carries them
        // — honest where a stale hardcoded copy would not be.
        BotCommand(
            trigger = "/model", summary = "Show or switch the model",
            argHint = "[alias]", sessionArgSource = SessionArgSource.MODEL_OPTIONS,
        ),
        BotCommand(
            trigger = "/effort", summary = "Show or set effort level",
            argHint = "[level]", sessionArgSource = SessionArgSource.EFFORT_LEVELS,
        ),
        BotCommand(
            trigger = "/mode", summary = "Show or switch interactive vs print",
            argHint = "[interactive|print]",
            argSuggestions = listOf(
                ArgSuggestion("interactive", summary = "Interactive TUI mode"),
                ArgSuggestion("print", summary = "Non-interactive print mode"),
            ),
        ),
        BotCommand(trigger = "/cost", summary = "Show session cost"),
        BotCommand(trigger = "/usage", summary = "Show token usage"),
        BotCommand(trigger = "/limits", summary = "Show subscription usage limits"),
        BotCommand(trigger = "/tools", summary = "List available tools"),
        // Context
        BotCommand(trigger = "/context", summary = "Show what's using the context window"),
        BotCommand(trigger = "/compact", summary = "Compact the conversation to free context", argHint = "[instructions]"),
        // Account
        BotCommand(trigger = "/login", summary = "Log in to your Anthropic account"),
        BotCommand(trigger = "/logout", summary = "Log out of your Anthropic account"),
        // Misc
        BotCommand(
            trigger = "/timer", summary = "Send a message to this chat later",
            argHint = "<duration> <message> | cancel <id|all>",
            argSuggestions = listOf(ArgSuggestion("cancel", summary = "Cancel a pending timer")),
        ),
        // The rescue keystrokes are bang-only on the bridge: a typed "/esc"
        // is NOT intercepted — it falls through as a TUI slash passthrough
        // and lands in the agent's terminal as a junk command. The palette
        // must complete the bang form. (The filter matches either prefix,
        // so typing "/es…" still surfaces it.)
        BotCommand(trigger = "!esc", summary = "Cancel the current turn"),
        BotCommand(trigger = "!enter", summary = "Press Enter in the agent's terminal"),
        BotCommand(trigger = "/help", summary = "Show command help"),
    )

    /// Filters `commands` by typed prefix. Comparison is case-insensitive and
    /// ignores the leading `/` or `!` so users can type either prefix to
    /// discover the same command. An empty (or all-prefix-only) input returns
    /// the full list.
    fun filter(commands: List<BotCommand>, byPrefix: String): List<BotCommand> {
        val normalized = byPrefix.lowercase().dropWhile { it == '/' || it == '!' }
        if (normalized.isEmpty()) return commands
        return commands.filter { cmd ->
            val trigger = cmd.trigger.lowercase().dropWhile { it == '/' || it == '!' }
            trigger.startsWith(normalized)
        }
    }

    private val LEADING_UNICODE_DASHES = Regex("^[\u2010\u2011\u2012\u2013\u2014\u2015]+")

    /// Mirrors the bridge's `LEADING_UNICODE_DASHES` rule
    /// (lib/command-dispatch.js): phone keyboards auto-correct a leading `--`
    /// into a single em/en dash, and the bridge normalizes it back before
    /// parsing — so anything comparing typed tokens to flags must do the same.
    fun normalizeLeadingDashes(token: CharSequence): String =
        LEADING_UNICODE_DASHES.replace(token, "--")

    /// Resolves the argument suggestions for a raw composer input: the matched
    /// command's static suggestions, filtered by the trailing partial token.
    /// Empty unless the input is a fully-typed command (either `/` or `!`
    /// prefix) followed by whitespace.
    ///
    /// Flags compose, so a flag stays offered until it's on the line or a
    /// conflicting one is ([ArgSuggestion.conflictsWith]) — but only while the
    /// trailing positional slot is open: the grammar is `[flags] [path]`, so a
    /// completed non-flag token ends the offering. Plain values fill a single
    /// slot and are only offered while no argument token is down yet
    /// (`/switch claude codex` is junk the palette must not build). A
    /// suggestion identical to the partial offers nothing — same rule as
    /// folder completion, so the palette doesn't linger over a completed flag.
    /// The values `status` supplies for a command that draws them from the
    /// session rather than the catalog. Absent and empty lists both come back
    /// empty here — the distinction lives in the model, and matters only to
    /// the merge that produced it. Values repeated by the bridge collapse to
    /// their first occurrence, keeping the order it sent: this pool is
    /// remote-controlled input, and the palette keys rows by value.
    private fun sessionSuggestions(source: SessionArgSource?, status: () -> SessionStatus?): List<ArgSuggestion> {
        if (source == null) return emptyList()
        val current = status() ?: return emptyList()
        val options = when (source) {
            SessionArgSource.MODEL_OPTIONS -> current.modelOptions
            SessionArgSource.EFFORT_LEVELS -> current.effortLevels
        } ?: return emptyList()
        val seen = mutableSetOf<String>()
        return options.mapNotNull { option ->
            if (!seen.add(option.value.lowercase())) return@mapNotNull null
            ArgSuggestion(option.value, label = option.label)
        }
    }

    /// [status] is read only once a session-derived command has actually
    /// matched: the composer's reads it out of the chat view model, and doing
    /// that on every keystroke would make the composer observe every status
    /// frame for input that isn't a command at all. A `null` status (no bridge
    /// has spoken, or the caller has no session) leaves session-derived
    /// commands offering nothing — exactly the pre-status-frame behaviour.
    fun argSuggestions(
        input: String,
        commands: List<BotCommand>,
        status: () -> SessionStatus? = { null },
    ): List<ArgSuggestion> {
        val leading = input.dropWhile { it == ' ' || it == '\t' }
        val first = leading.firstOrNull() ?: return emptyList()
        if (first != '/' && first != '!') return emptyList()
        val body = leading.drop(1)
        // The command must be complete: whitespace after its token.
        val commandEnd = body.indexOfFirst { it.isWhitespace() }
        if (commandEnd < 0) return emptyList()
        val name = body.substring(0, commandEnd).lowercase()
        val command = commands.firstOrNull {
            it.trigger.lowercase().dropWhile { c -> c == '/' || c == '!' } == name
        } ?: return emptyList()

        // Trailing partial = everything after the last whitespace (empty when
        // the input ends mid-separator); earlier tokens are complete.
        val args = body.substring(commandEnd)
        val partialStart = args.indexOfLast { it.isWhitespace() } + 1
        val partial = normalizeLeadingDashes(args.substring(partialStart)).lowercase()
        val earlier = args.substring(0, partialStart)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { normalizeLeadingDashes(it).lowercase() }
            .toSet()
        val positionalFilled = earlier.any { !it.startsWith("--") }

        val pool = command.argSuggestions + sessionSuggestions(command.sessionArgSource, status)
        return pool.filter { suggestion ->
            val value = suggestion.value.lowercase()
            if (suggestion.isFlag) {
                if (positionalFilled || earlier.contains(value)) return@filter false
            } else if (earlier.isNotEmpty()) {
                return@filter false
            }
            if (suggestion.conflictsWith.any { it in earlier }) return@filter false
            value.startsWith(partial) && value != partial
        }
    }
}
