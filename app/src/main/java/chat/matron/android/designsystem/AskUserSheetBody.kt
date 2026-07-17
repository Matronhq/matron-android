package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.matron.android.events.AskUserEvent

/// The inner content of an ask-user prompt — prompt text, one of four input
/// kinds, expiry notice, error line. Single-choice options and Yes/No are
/// one-tap answer buttons (web/desktop parity — [onPickChoice] / [onPickBoolean]
/// select AND send); a Send button appears only for the kinds a tap can't fully
/// answer (free text, multi-select, "Other…").
///
/// Fully hoisted on plain values + intent lambdas so the design system stays
/// decoupled from app/service types.
@Composable
fun AskUserSheetBody(
    event: AskUserEvent,
    textInput: String,
    onTextChange: (String) -> Unit,
    selectedChoiceIDs: Set<String>,
    onToggleChoice: (String) -> Unit,
    onPickChoice: (String) -> Unit,
    onPickBoolean: (Boolean) -> Unit,
    isSending: Boolean,
    isExpired: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    Column(
        modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(event.prompt, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)

        when (val kind = event.kind) {
            is AskUserEvent.InputKind.Text ->
                OutlinedTextField(
                    value = textInput,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Your answer…") },
                    enabled = !isExpired,
                    minLines = 3,
                    maxLines = 8,
                )

            is AskUserEvent.InputKind.Choice -> {
                val reserveGlyph = optionsHaveGlyph(kind.options)
                kind.options.forEach { opt ->
                    AccentChip(
                        onClick = { onPickChoice(opt.id) },
                        enabled = !isSending && !isExpired,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val (glyph, text) = splitLeadingGlyph(opt.label)
                        GlyphSlot(glyph, reserveGlyph)
                        Text(text)
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (kind.allowOther) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Other…") },
                        enabled = !isExpired,
                        singleLine = true,
                    )
                }
            }

            is AskUserEvent.InputKind.MultiChoice -> {
                val reserveGlyph = optionsHaveGlyph(kind.options)
                kind.options.forEach { opt ->
                    val checked = selectedChoiceIDs.contains(opt.id)
                    val (glyph, text) = splitLeadingGlyph(opt.label)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isExpired) { onToggleChoice(opt.id) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (checked) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        GlyphSlot(glyph, reserveGlyph)
                        Text(text, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (kind.allowOther) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = onTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Other…") },
                        enabled = !isExpired,
                        singleLine = true,
                    )
                }
            }

            is AskUserEvent.InputKind.Boolean ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentChip(onClick = { onPickBoolean(true) }, enabled = !isSending && !isExpired) {
                        Text("Yes")
                    }
                    AccentChip(onClick = { onPickBoolean(false) }, enabled = !isSending && !isExpired) {
                        Text("No")
                    }
                }
        }

        if (isExpired) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("This question has expired.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MatronRed)
        }

        // Send exists only where a tap can't be the whole answer: free text,
        // multi-select, or a choice set with an "Other…" field. Instant kinds
        // surface in-flight state as a bare spinner instead.
        if (needsSendButton(event.kind)) {
            Button(
                onClick = onSend,
                enabled = !isSending && !isExpired,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                if (isSending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Send")
            }
        } else if (isSending) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

private fun needsSendButton(kind: AskUserEvent.InputKind): Boolean = when (kind) {
    is AskUserEvent.InputKind.Text -> true
    is AskUserEvent.InputKind.MultiChoice -> true
    is AskUserEvent.InputKind.Choice -> kind.allowOther
    is AskUserEvent.InputKind.Boolean -> false
}

private fun optionsHaveGlyph(options: List<AskUserEvent.Option>): Boolean =
    options.any { splitLeadingGlyph(it.label).first != null }

/// The fixed 18dp leading slot: the glyph centred when present, an empty
/// reservation when a sibling row has one, nothing otherwise.
@Composable
private fun GlyphSlot(glyph: String?, reserve: Boolean) {
    when {
        glyph != null -> Text(glyph, modifier = Modifier.width(18.dp), textAlign = TextAlign.Center)
        reserve -> Spacer(Modifier.width(18.dp))
        else -> Unit
    }
}

/// A light, airy accent-tinted answer chip shared by `.choice` and `.boolean`
/// buttons: an accent fill + hairline border with accent-coloured text that
/// reads clearly on the white card in both colour schemes. Dimmed when disabled.
@Composable
private fun AccentChip(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val rowScope = this
        CompositionLocalProvider(LocalContentColor provides accent) {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = accent)) {
                rowScope.content()
            }
        }
    }
}
