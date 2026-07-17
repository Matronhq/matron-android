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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant

/// Auto-connect window. 10 minutes comfortably covers a long-running command
/// that's still streaming when the user opens the chat.
private const val AUTO_CONNECT_WINDOW_SECONDS = 600L

/// Inline live command-output tile — the journal-protocol port of matron-web's
/// live-output body. Header shows `$ <command>` with a status label and an
/// expand/collapse toggle; below, a pinned-dark monospace [TerminalPane]
/// streams the command's output live. Only tiles young enough that the command
/// is plausibly still running auto-connect; historical tiles wait for the user
/// to expand ([eventTimestamp] gates it).
@Composable
fun LiveOutputCard(
    session: LiveOutputSession,
    eventTimestamp: Instant,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val command = session.event.command
    val phase = session.phase

    LaunchedEffect(session.event.toolUseID) {
        if (Instant.now().epochSecond - eventTimestamp.epochSecond < AUTO_CONNECT_WINDOW_SECONDS) {
            session.startIfNeeded(scope)
        }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MatronThemeColors.current.codeBg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .semantics(mergeDescendants = true) {
                contentDescription = "Command output: $command. ${statusLabel(phase)}"
            },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "$ ${command.replace("\n", " ⏎ ")}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusView(phase)
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse output" else "Expand output",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .clickable {
                        expanded = !expanded
                        // Historical tiles defer loading until the user asks —
                        // expanding is the ask.
                        if (expanded) session.startIfNeeded(scope)
                    },
            )
        }

        if (showsPane(session)) {
            TerminalPane(output = session.output, expanded = expanded)
        } else {
            placeholder(phase)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusView(phase: LiveOutputSession.Phase) {
    when (phase) {
        LiveOutputSession.Phase.Idle ->
            Text(statusLabel(phase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        LiveOutputSession.Phase.Connecting, LiveOutputSession.Phase.Streaming ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Text(statusLabel(phase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        is LiveOutputSession.Phase.Complete -> {
            val warn = phase.denied || (phase.exitCode ?: 0) != 0
            Text(
                statusLabel(phase),
                style = MaterialTheme.typography.labelSmall,
                color = if (warn) MatronOrange else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LiveOutputSession.Phase.Expired, LiveOutputSession.Phase.Disconnected ->
            Text(statusLabel(phase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun showsPane(session: LiveOutputSession): Boolean {
    if (session.hasOutput) return true
    // While live, show the (empty) pane so output has somewhere to land without
    // a layout jump; placeholder states take over once nothing is coming.
    return when (session.phase) {
        LiveOutputSession.Phase.Connecting, LiveOutputSession.Phase.Streaming -> true
        else -> false
    }
}

private fun placeholder(phase: LiveOutputSession.Phase): String? = when (phase) {
    is LiveOutputSession.Phase.Complete -> if (phase.denied) "Command not executed" else "No output"
    LiveOutputSession.Phase.Expired -> "Output expired"
    LiveOutputSession.Phase.Disconnected -> "Output unavailable"
    else -> null
}

private fun statusLabel(phase: LiveOutputSession.Phase): String = when (phase) {
    LiveOutputSession.Phase.Idle -> "expand to view"
    LiveOutputSession.Phase.Connecting -> "connecting…"
    LiveOutputSession.Phase.Streaming -> "running…"
    is LiveOutputSession.Phase.Complete -> when {
        phase.denied -> "not executed"
        (phase.exitCode ?: 0) == 0 -> "✓ exit ${phase.exitCode ?: 0}".let { if (phase.truncated) "$it · truncated" else it }
        else -> "✗ exit ${phase.exitCode ?: -1}".let { if (phase.truncated) "$it · truncated" else it }
    }
    LiveOutputSession.Phase.Expired -> "expired"
    LiveOutputSession.Phase.Disconnected -> "⚠ disconnected"
}
