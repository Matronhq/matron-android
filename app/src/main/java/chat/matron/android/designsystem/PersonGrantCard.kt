package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.GrantCardState
import chat.matron.android.events.KEEP_CONTRIBUTING_LABEL
import chat.matron.android.events.KeepLevel
import chat.matron.android.events.PersonGrantRequest
import chat.matron.android.events.grantAnsweredText
import chat.matron.android.events.keepLevelHint

/// A person-to-person grant card inline in the timeline: a mission share or
/// a hand-over, on the owner's side (Approve / Decline, and on a hand-over a
/// tick per file to copy) or the recipient's (Accept / Decline).
///
/// [onApprove] receives the media ids the owner unticked — always empty on
/// the recipient's card and on a share.
///
/// The owner's hand-over card also has a "Keep contributing" switch. Its
/// value is [keepLevel] and a flip goes to [onKeepLevelChange]: the caller
/// holds it (the chat view model, keyed by event id), so a rebuilt row keeps
/// the choice and Approve can send it.
@Composable
fun PersonGrantCard(
    request: PersonGrantRequest,
    state: GrantCardState,
    onApprove: (excludeMedia: List<String>) -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    /// The files unticked when the card first draws — previews and
    /// screenshots only; the user starts with every file ticked.
    initiallyExcluded: List<String> = emptyList(),
    keepLevel: KeepLevel = request.keepLevel,
    onKeepLevelChange: (KeepLevel) -> Unit = {},
) {
    val answerable = state is GrantCardState.Idle || state is GrantCardState.Failed
    val busy = state is GrantCardState.Sending
    // Keyed by the grant so a recycled row never carries another card's ticks.
    var excluded by rememberSaveable(request.grantId) { mutableStateOf(initiallyExcluded) }

    Column(
        modifier
            .shadow(2.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MatronThemeColors.current.bubbleBot)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(request.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(request.headline, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)

        request.levelLabel?.let { level ->
            GrantDetail("Access", level.replaceFirstChar { it.uppercase() })
        }

        request.brief?.let { brief ->
            // A brief can run to pages: clamp it and let it scroll inside
            // the card, so the buttons stay in reach.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                MarkdownText(brief, textStyle = MaterialTheme.typography.bodyMedium)
            }
        }

        request.preview.countsLine?.let { counts ->
            Text(counts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (request.preview.attachments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(
                    "Files to copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                request.preview.attachments.forEach { file ->
                    val ticked = file.mediaId !in excluded
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = ticked,
                                enabled = answerable,
                                role = Role.Checkbox,
                                onValueChange = { on ->
                                    excluded = if (on) excluded - file.mediaId else excluded + file.mediaId
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = ticked, onCheckedChange = null, enabled = answerable, modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            grantFileCaption(file)?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (request.sendsKeepLevel) {
            val on = keepLevel == KeepLevel.CONTRIBUTE
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = on,
                            enabled = answerable,
                            role = Role.Switch,
                            onValueChange = { onKeepLevelChange(if (it) KeepLevel.CONTRIBUTE else KeepLevel.READ) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        KEEP_CONTRIBUTING_LABEL,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Switch(checked = on, onCheckedChange = null, enabled = answerable)
                }
                Text(
                    keepLevelHint(keepLevel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        request.recipientKeepLine?.let { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }

        if (request.isHandover && request.direction == PersonGrantRequest.Direction.OUT) {
            Text(
                PersonGrantRequest.OWNER_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (state) {
            is GrantCardState.Answered -> ResolvedLine(
                icon = if (state.approved) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                text = grantAnsweredText(request, state.approved),
                positive = state.approved,
            )

            GrantCardState.Unavailable -> ResolvedLine(
                icon = Icons.Filled.HourglassEmpty,
                text = "This request is no longer waiting for an answer.",
                positive = false,
            )

            else -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onDecline, enabled = !busy) { Text("Decline") }
                Button(
                    onClick = { onApprove(request.attachmentIds.filter { it in excluded }) },
                    enabled = !busy,
                ) { Text(request.approveLabel) }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
            }
        }

        (state as? GrantCardState.Failed)?.let {
            Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/// "PDF · 1.2 MB · item #12" under a file's name; null when the card said
/// nothing about it.
fun grantFileCaption(file: PersonGrantRequest.Attachment): String? {
    val parts = listOfNotNull(
        file.size?.let(::grantFileSize),
        file.itemNum?.let { "item #$it" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/// A byte count as the card shows it: "512 B", "3.4 KB", "1.2 MB".
fun grantFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
private fun ResolvedLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, positive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (positive) MatronGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GrantDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}
