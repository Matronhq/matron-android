package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/// Design-system primitive for a fenced code block: monospaced source in a
/// horizontally scrollable container, with a language label and a copy button.
/// Used by [MarkdownText] and reusable directly.
///
/// [language] is the fence's whole info string. Its first word is the
/// language shown in the label; a later word `collapsed` folds the block
/// behind a one-line "Show <language>" header that opens it on tap (the copy
/// button still copies the code while it is folded). The bridge writes the
/// exact JSON input of an "Allow once" ask this way, under a description of
/// it in plain words, so the JSON stays for audit without taking over the card.
@Composable
fun CodeBlock(
    language: String,
    source: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val info = remember(language) { FenceInfo.parse(language) }
    var expanded by remember { mutableStateOf(false) }
    val shown = !info.collapsed || expanded
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
            if (info.collapsed) {
                val label = info.toggleLabel(expanded)
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide the block" else "Show the block") {
                            expanded = !expanded
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MatronThemeColors.current.accent,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MatronThemeColors.current.accent,
                    )
                }
            } else {
                Text(
                    info.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
            }
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
        if (shown) Row(
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

/// A fence's info string, split into the language (its first word) and
/// whether the block starts folded (a later word `collapsed`). A block whose
/// only word is `collapsed` is a block in a language of that name, not a
/// folded one.
internal data class FenceInfo(val language: String, val collapsed: Boolean) {
    /// What the label shows: the language, or "code" for none.
    val label: String get() = language.ifEmpty { "code" }

    /// The fold header's text.
    fun toggleLabel(expanded: Boolean): String = (if (expanded) "Hide " else "Show ") + label

    companion object {
        fun parse(info: String): FenceInfo {
            val words = info.split(' ', '\t').filter { it.isNotEmpty() }
            return FenceInfo(words.firstOrNull() ?: "", words.drop(1).contains("collapsed"))
        }
    }
}

/// How long the copy button shows its checkmark after a tap (apple #170: 1.2s).
internal const val COPIED_FEEDBACK_MS = 1_200L
