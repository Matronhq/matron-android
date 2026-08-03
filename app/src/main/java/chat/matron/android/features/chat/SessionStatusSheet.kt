package chat.matron.android.features.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import chat.matron.android.designsystem.ContextGaugeLabel
import chat.matron.android.designsystem.UsageBarScale
import chat.matron.android.designsystem.UsageBarsView
import chat.matron.android.designsystem.UsageMetersFormat
import chat.matron.android.viewmodels.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Session-status sheet content. Ports Features/Chat/SessionStatusSheet.swift: the
 * context-window gauge (with a Compact action that sends `/compact`) and the
 * stacked usage bars from the last journal `status` frame. Reads
 * [ChatViewModel.sessionStatus] directly so an open sheet refreshes when the
 * first status frame lands.
 */
@Composable
fun SessionStatusSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status by viewModel.sessionStatus.collectAsStateWithLifecycle()

    val current = status
    val hasContent = current != null &&
        (current.model != null || current.context != null || !current.limits.isNullOrEmpty() ||
            current.email != null || current.workdir != null || current.vitals != null)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("Session", style = MaterialTheme.typography.titleMedium)

        if (current != null && hasContent) {
            current.context?.let { context ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ContextGaugeLabel(context = context, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        scope.launch { viewModel.sendCommand("/compact") }
                        onDismiss()
                    }) { Text("Compact") }
                }
            }
            current.limits?.takeIf { it.isNotEmpty() }?.let { limits ->
                UsageBarsView(limits = limits, scale = UsageBarScale.Regular)
            }
            // Vitals renders as one quiet caption line, never as a usage bar —
            // machine metrics must not read as subscription meters (#90).
            val vitalsText = current.vitals?.let { UsageMetersFormat.vitalsLine(it) }
            if (current.email != null || current.model != null || current.workdir != null || vitalsText != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    current.workdir?.let {
                        Text(
                            UsageMetersFormat.homeAbbreviated(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    current.email?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    current.model?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    }
}
