package chat.matron.android.features.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.SubChatSummary
import chat.matron.android.designsystem.ContextGaugeLabel
import chat.matron.android.designsystem.UsageBarScale
import chat.matron.android.designsystem.UsageBarsView
import chat.matron.android.designsystem.UsageMetersFormat
import chat.matron.android.viewmodels.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Session-status sheet content. Ports Features/Chat/SessionStatusSheet.swift: the
 * context-window gauge (with a Compact action that sends `/compact`), the
 * stacked usage bars from the last journal `status` frame, and — since apple
 * #189 — this chat's subagents. Reads [ChatViewModel.sessionStatus] directly so
 * an open sheet refreshes when the first status frame lands.
 */
@Composable
fun SessionStatusSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    /// The agent box this session runs on, or null when the user has fewer
    /// than two boxes (the chip gate — see `JournalChatService.boxName`).
    boxName: String? = null,
    /// This chat's subagents (running and finished), oldest first — the list
    /// that used to be a section of the toolbar's ellipsis menu (Dan,
    /// 2026-09-09: the toolbar carried too many items and the menu is rarely
    /// used). Hoisted state: [ChatScreen] collects the strip VM's `children`
    /// and passes the current list, so an open sheet recomposes as children
    /// arrive or finish (the Apple sheet holds the observable VM itself for
    /// the same reason — Bugbot, apple #189). Empty ⇒ the section is absent.
    subagents: List<SubChatSummary> = emptyList(),
    /// Ride-along to a subagent's sub-chat: receives the tapped child's id
    /// AFTER [onDismiss] has run, so the caller navigates from a closed
    /// sheet — the same dismiss-then-act order the summaries sheet uses.
    onOpenSubagent: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val status by viewModel.sessionStatus.collectAsStateWithLifecycle()

    val current = status
    // Gated on content alone, not on a non-null status frame: the box name is
    // known from the chat list, so open the sheet before the first status
    // lands and the footer is the only thing there is to show — requiring a
    // frame here hid the box name behind "No usage data yet" (apple #131).
    val hasContent = boxName != null || (current != null &&
        (current.model != null || current.context != null || !current.limits.isNullOrEmpty() ||
            current.email != null || current.workdir != null || current.vitals != null))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Session", style = MaterialTheme.typography.titleMedium)

        if (hasContent) {
            current?.context?.let { context ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContextGaugeLabel(context = context, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        scope.launch { viewModel.sendCommand("/compact") }
                        onDismiss()
                    }) { Text("Compact") }
                }
            }
            current?.limits?.takeIf { it.isNotEmpty() }?.let { limits ->
                UsageBarsView(limits = limits, scale = UsageBarScale.Regular)
            }
            // Vitals renders as one quiet caption line, never as a usage bar —
            // machine metrics must not read as subscription meters (#90).
            val vitalsText = current?.vitals?.let { UsageMetersFormat.vitalsLine(it) }
            if (boxName != null || current?.email != null || current?.model != null ||
                current?.workdir != null || vitalsText != null
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // Leads the block: "which machine am I talking to"
                    // outranks the account and path.
                    boxName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    current?.workdir?.let {
                        Text(
                            UsageMetersFormat.homeAbbreviated(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    current?.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    current?.model?.let {
                        Text(
                            UsageMetersFormat.modelLine(it, current?.effort),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    vitalsText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } else {
            Text(
                "No usage data yet. Appears after the next reply.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Outside the `hasContent` gate: the children are known from the
        // strip's own stream, so they must be reachable before the first
        // status frame lands. Below the session info, not on top of it
        // (Dan, 2026-09-09: the sheet is for the session info; a long list
        // above it buried the info). One row per child — hollow circle while
        // running, check once finished; the running strip hides itself the
        // moment the last subagent finishes, so this is the way back into a
        // finished sub-chat besides its timeline card.
        if (showsSubagentsSection(subagents)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Subagents (${subagents.size})", style = MaterialTheme.typography.titleSmall)
                subagents.forEachIndexed { index, child ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { handOffSubagentTap(child.id, onDismiss, onOpenSubagent) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (child.isRunning) Icons.Outlined.Circle else Icons.Outlined.CheckCircle,
                            contentDescription = if (child.isRunning) "Running" else "Finished",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(child.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}

/// Whether the sheet shows its "Subagents" section: only when the chat has
/// children (running or finished). Deliberately independent of the status
/// frame — the section is reachable before the first one lands. Port of the
/// `!subagents.isEmpty()` gate in apple #189's `SessionStatusSheet`.
internal fun showsSubagentsSection(subagents: List<SubChatSummary>): Boolean = subagents.isNotEmpty()

/// A subagent row's tap: dismiss the sheet, THEN hand the child's id to the
/// host, which navigates to the sub-chat. The sheet can't navigate itself (it
/// has no NavController); [ChatScreen] wires [onOpenSubagent] to its
/// `onOpenChild`. Order matters — navigating from a still-open sheet leaves a
/// stale sheet flag behind when the user comes back. Port of apple #189's
/// `onOpenSubagent` handoff (there: arm the intent, dismiss, push from
/// `onDismiss`; Compose's sheet state is immediate, so the two steps run in
/// sequence here).
internal fun handOffSubagentTap(childID: String, onDismiss: () -> Unit, onOpenSubagent: ((String) -> Unit)?) {
    onDismiss()
    onOpenSubagent?.invoke(childID)
}
