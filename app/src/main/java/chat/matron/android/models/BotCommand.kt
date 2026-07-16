package chat.matron.android.models

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
)

/// Static slash-command catalogs per bot kind, plus a small filter helper used
/// by the composer's slash palette.
object BotCommandCatalog {
    /// Static catalog for the Claude bridge.
    val claudeBridge: List<BotCommand> = listOf(
        // Sessions
        BotCommand(trigger = "/start", summary = "Start a new session", argHint = "[workdir]"),
        BotCommand(trigger = "/stop", summary = "Stop the current session"),
        BotCommand(trigger = "/restart", summary = "Stop and immediately resume the session"),
        BotCommand(trigger = "/resume", summary = "Resume a previous session", argHint = "[n|id]"),
        BotCommand(trigger = "/sessions", summary = "List past sessions"),
        BotCommand(trigger = "/workdir", summary = "Start a session in a different directory", argHint = "<path>"),
        // Info
        BotCommand(trigger = "/status", summary = "Show current session info"),
        BotCommand(trigger = "/agent", summary = "Show the current agent"),
        BotCommand(trigger = "/working", summary = "Toggle tool call visibility"),
        BotCommand(trigger = "/mcp", summary = "Show MCP server status"),
        BotCommand(trigger = "/model", summary = "Show current model"),
        BotCommand(trigger = "/effort", summary = "Show or set effort level", argHint = "[level]"),
        BotCommand(trigger = "/mode", summary = "Show or switch interactive vs print", argHint = "[interactive|print]"),
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
        BotCommand(trigger = "/esc", summary = "Cancel the current turn"),
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
}
