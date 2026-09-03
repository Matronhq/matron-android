package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/// Live tool-output tile for the journal `tool_stream` overlay — the ephemeral
/// sibling of [LiveOutputCard], fed accumulated stream [text] by the timeline
/// instead of owning a socket. No terminal states: the tile only exists while
/// the command runs; completion replaces it with the durable [ToolCallCard].
@Composable
fun ToolStreamCard(
    command: String?,
    text: String,
    headTruncated: Boolean,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    // Full re-parse per text change: a stateful incremental parse isn't worth
    // carrying UI-side state for at these sizes (collapsed 4 KiB via
    // [collapsedSlice], expanded 64 KiB max).
    val rendered = remember(text, headTruncated, expanded) {
        if (expanded) {
            renderStream(text, headTruncated)
        } else {
            val (shown, cut) = collapsedSlice(text)
            renderStream(shown, headTruncated || cut)
        }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MatronThemeColors.current.codeBg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = "Live command output: ${command ?: "running command"}. running"
            },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                command?.let { "$ ${it.replace("\n", " ⏎ ")}" } ?: "live output",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Text("running…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse output" else "Expand output",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).clickable { expanded = !expanded },
            )
        }
        TerminalPane(output = rendered, expanded = expanded)
    }
}

/// How much of the stream tail a COLLAPSED pane renders. Ported from
/// matron-apple's `ToolStreamCard.collapsedDisplayCapChars` (Apple PR #130).
/// The timeline caps delivered text at 64 KiB, and re-parsing + re-laying-out
/// all of it on every append is what a live command's card cost the main
/// thread several times a second — while the collapsed pane is 76dp tall and
/// shows ~5 lines. 4 KiB is hundreds of terminal lines, far more than the
/// pane can scroll into view before the next append lands. Expanding renders
/// the full tail.
internal const val COLLAPSED_DISPLAY_CAP_CHARS = 4096

/// The slice of [text] a collapsed pane renders: the last
/// [COLLAPSED_DISPLAY_CAP_CHARS] characters, opened at a line boundary. The
/// second value reports whether anything was dropped (the caller shows the
/// truncation notice). Internal so tests can pin the cap and the line-boundary
/// contract without composing. Ported from matron-apple's
/// `ToolStreamCard.collapsedSlice` (Apple PR #130).
internal fun collapsedSlice(text: String): Pair<String, Boolean> {
    if (text.length <= COLLAPSED_DISPLAY_CAP_CHARS) return text to false
    var shown = text.takeLast(COLLAPSED_DISPLAY_CAP_CHARS)
    // Drop the (almost certainly partial) first line so the cut never opens
    // mid-word or inside a split ANSI escape sequence — but only when that
    // line ends within the first few hundred characters. Terminal lines are
    // short; a newline that far in means the tail is effectively one giant
    // line, and trimming through it would throw away most (or, when the only
    // newline is the final character, ALL) of the visible text (Bugbot,
    // Apple PR #130).
    val scanEnd = minOf(512, shown.length)
    val newline = shown.substring(0, scanEnd).indexOf('\n')
    if (newline >= 0) shown = shown.substring(newline + 1)
    return shown to true
}

/// Prepends the head-truncation notice (fixed dim grey, not a semantic colour —
/// the pane is hard-coded dark in both themes) then the ANSI-rendered stream.
private fun renderStream(text: String, headTruncated: Boolean): AnnotatedString =
    buildAnnotatedString {
        if (headTruncated) {
            withStyle(SpanStyle(color = Color(0.55f, 0.55f, 0.55f))) {
                append("… earlier output truncated\n")
            }
        }
        append(AnsiSGRParser().append(text))
    }
