package chat.matron.android.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.ToolCallEvent

/// Collapsible card for a `chat.matron.tool_call` event.
///
/// Collapsed: status icon + tool name + one-line arg summary. Tap the header to
/// expand into the full pretty-printed arguments and result blocks. The Mac's
/// hover "Click to expand" hint has no Android analog and is dropped.
@Composable
fun ToolCallCard(
    event: ToolCallEvent,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val colors = MatronThemeColors.current

    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBg)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatusIcon(event.status)
            Text(
                event.tool,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            outcomeBadge(event)?.let { badge ->
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MatronRed,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MatronRed.copy(alpha = 0.12f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Text(
                event.argSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (event.argsJSON.isNotEmpty() && event.argsJSON != "{}") {
                    Section("Command") {
                        CodeSurface(
                            event.commandString ?: event.argsJSON,
                            background = colors.cardInnerBg,
                            foreground = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (event.expired) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Output expired",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (event.resultText != null) {
                    Section("Result" + if (event.resultTruncated) " (truncated)" else "") {
                        CodeSurface(
                            event.resultText!!,
                            background = TerminalStyle.background,
                            foreground = TerminalStyle.foreground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: ToolCallEvent.Status) {
    when (status) {
        ToolCallEvent.Status.RUNNING ->
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        ToolCallEvent.Status.OK ->
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MatronGreen, modifier = Modifier.size(14.dp))
        ToolCallEvent.Status.ERROR ->
            Icon(Icons.Filled.Dangerous, contentDescription = null, tint = MatronRed, modifier = Modifier.size(14.dp))
    }
}

/// "denied" / "exit N" pill for failed commands. A zero exit code shows nothing
/// — the green check already says it.
private fun outcomeBadge(event: ToolCallEvent): String? {
    if (event.denied) return "denied"
    val code = event.exitCode
    if (code != null && code != 0) return "exit $code"
    return null
}

@Composable
private fun Section(heading: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            heading,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/// Horizontally scrollable monospace block on the given surface.
@Composable
private fun CodeSurface(text: String, background: Color, foreground: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
            modifier = Modifier.padding(8.dp),
        )
    }
}
