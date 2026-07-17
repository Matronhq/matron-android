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
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NoteAdd
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import chat.matron.android.events.DiffEvent

private const val COLLAPSED_LINE_COUNT = 12

/// Card for a journal `diff` event — a file-edit snippet with the filename in
/// the header (tappable link to the bridge's signed viewer URL when supplied)
/// and prefix-colored unified-diff lines in the body, on the fixed dark
/// [TerminalStyle] surface so diffs read like the tool-output panel in both
/// app themes. Collapsed shows the first [COLLAPSED_LINE_COUNT] lines with a
/// "+N more lines" row; the chevron expands to the full diff.
@Composable
fun DiffCard(
    event: DiffEvent,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val colors = MatronThemeColors.current

    val allLines = remember(event.diff) {
        if (event.diff.isEmpty()) emptyList() else event.diff.split("\n")
    }
    val visible = if (expanded) allLines else allLines.take(COLLAPSED_LINE_COUNT)
    val hidden = allLines.size - visible.size

    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBg)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DiffHeader(event, expanded) { expanded = !expanded }

        if (visible.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TerminalStyle.background)
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    renderDiff(visible),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalStyle.foreground,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        if (hidden > 0) {
            Text(
                "+$hidden more line${if (hidden == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { expanded = true },
            )
        } else {
            AnimatedVisibility(visible = expanded && event.truncated) {
                Text(
                    "… diff truncated",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiffHeader(event: DiffEvent, expanded: Boolean, onToggle: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                if (event.newFile) Icons.Filled.NoteAdd else Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val name = event.filename ?: event.tool ?: "diff"
        val viewerURL = event.viewerURL
        Text(
            name,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (viewerURL != null) TextDecoration.Underline else null,
            modifier = if (viewerURL != null) {
                Modifier.clickable { runCatching { uriHandler.openUri(viewerURL) } }
            } else {
                Modifier
            },
        )

        event.label?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (event.newFile) {
            Text(
                "new file",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MatronGreen,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MatronGreen.copy(alpha = 0.12f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        event.added?.let {
            Text("+$it", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MatronGreen)
        }
        event.removed?.let {
            Text("−$it", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MatronRed)
        }
        if (event.truncated) {
            Text("…", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/// One [AnnotatedString] for the whole visible block — a per-line Text stack at
/// hundreds of lines is exactly the view-count blowup to avoid. `+`/`-`/`@@`
/// prefixes get add/remove/hunk coloring.
private fun renderDiff(lines: List<String>): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { i, line ->
        val color = when {
            line.startsWith("+") -> TerminalStyle.diffAdded
            line.startsWith("-") -> TerminalStyle.diffRemoved
            line.startsWith("@@") -> TerminalStyle.dimForeground
            else -> null
        }
        if (color != null) {
            withStyle(SpanStyle(color = color)) { append(line) }
        } else {
            append(line)
        }
        if (i < lines.size - 1) append("\n")
    }
}

/// Pins the VoiceOver / TalkBack summary — write/create wording plus add/remove
/// counts. Kept as a single source of truth so the chat timeline rows can reuse
/// the exact same string for their row-level content description.
object DiffCardAccessibility {
    fun summary(event: DiffEvent): String {
        val verb = if (event.tool == "Write") {
            if (event.newFile) "Created" else "Wrote"
        } else {
            "Edited"
        }
        val name = event.filename ?: "file"
        val parts = mutableListOf("$verb $name")
        event.added?.let { parts.add("$it addition${if (it == 1) "" else "s"}") }
        event.removed?.let { parts.add("$it removal${if (it == 1) "" else "s"}") }
        return parts.joinToString(", ")
    }
}
