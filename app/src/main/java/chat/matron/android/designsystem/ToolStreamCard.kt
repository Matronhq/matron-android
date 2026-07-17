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
    // Full re-parse per text change: the timeline caps display text at 64 KiB,
    // so a stateful incremental parse isn't worth carrying UI-side state for.
    val rendered = remember(text, headTruncated) { renderStream(text, headTruncated) }

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
