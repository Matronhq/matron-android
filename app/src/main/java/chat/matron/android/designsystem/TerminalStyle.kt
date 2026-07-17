package chat.matron.android.designsystem

import androidx.compose.ui.graphics.Color

/// Shared dark-terminal palette for the live-output pane (`TerminalPane`) and
/// the journal tool_output result block (`ToolCallCard`): a fixed dark surface
/// with light monospace text so the terminal look reads identically in both app
/// themes. Kept in one place so the two call sites can't drift apart. Values
/// mirror the Swift `TerminalStyle` (0–1 component floats → 0–255).
object TerminalStyle {
    /// Dark panel background (0.12).
    val background: Color = Color(31, 31, 31)

    /// Light monospace foreground (0.86).
    val foreground: Color = Color(219, 219, 219)

    /// Diff added/removed line colours — the same green/red the ANSI
    /// live-output palette uses, fixed rather than scheme-adaptive because this
    /// surface stays dark in both app themes.
    val diffAdded: Color = Color(115, 209, 115)
    val diffRemoved: Color = Color(230, 89, 89)

    /// Dimmed foreground for structural lines (diff `@@` hunk headers).
    val dimForeground: Color = Color(140, 140, 140)
}
