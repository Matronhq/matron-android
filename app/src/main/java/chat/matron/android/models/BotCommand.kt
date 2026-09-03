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
        BotCommand(trigger = "/model", summary = "Show current model"),
        BotCommand(trigger = "/effort", summary = "Show or set effort level", argHint = "[level]"),
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
    fun argSuggestions(input: String, commands: List<BotCommand>): List<ArgSuggestion> {
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

        return command.argSuggestions.filter { suggestion ->
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
