package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/// Design-system primitive for a fenced code block: monospaced source in a
/// horizontally scrollable container, with a language label and a copy button.
/// Used by [MarkdownText] and reusable directly.
@Composable
fun CodeBlock(
    language: String,
    source: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    // Tap feedback: the icon flips to a checkmark for a moment after a copy.
    // Keyed on a counter rather than a boolean so a re-tap restarts the full
    // window instead of being swallowed by the running one (apple #170).
    var copyTick by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copyTick) {
        if (copyTick == 0) return@LaunchedEffect
        copied = true
        delay(COPIED_FEEDBACK_MS)
        copied = false
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                language.ifEmpty { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    // Bare code, never the ``` fences: the paste target is
                    // almost always a terminal.
                    clipboard.setText(AnnotatedString(source))
                    copyTick += 1
                },
            ) {
                Icon(
                    if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = if (copied) "Copied" else "Copy code",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(MatronThemeColors.current.codeBg)
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                source,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/// How long the copy button shows its checkmark after a tap (apple #170: 1.2s).
internal const val COPIED_FEEDBACK_MS = 1_200L
