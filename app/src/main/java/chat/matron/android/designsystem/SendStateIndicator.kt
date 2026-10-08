package chat.matron.android.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/// Tri-state mirror of `TimelineSendState` for the design-system surface. Kept
/// as a distinct type (rather than re-exporting the model-layer enum) so glyph
/// UX can diverge from the model — bridged via [sendStateGlyphFrom].
sealed interface SendStateGlyph {
    data object Sending : SendStateGlyph
    data object Sent : SendStateGlyph
    /// Waiting in the offline outbox for connectivity.
    data object Queued : SendStateGlyph
    data class Failed(val reason: String) : SendStateGlyph
}

/// Small footer indicator under an own-message bubble reflecting the send
/// state. [SendStateGlyph.Sent] is the default and renders nothing (a checkmark
/// on every row would clutter the timeline); [SendStateGlyph.Sending] shows a
/// clock + "Sending…"; [SendStateGlyph.Failed] shows a red error + retry
/// affordance forwarding taps to [onRetry].
@Composable
fun SendStateIndicator(
    state: SendStateGlyph,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    when (state) {
        is SendStateGlyph.Sent -> Unit // Absence of an indicator IS the success signal.
        is SendStateGlyph.Sending -> Row(
            modifier.semantics { contentDescription = "Sending" },
        ) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Sending…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is SendStateGlyph.Queued -> Row(
            // Offline outbox: honest "not in flight yet" treatment. Clickable
            // (like Failed) so a tap can force a send attempt / reconnect
            // nudge via the same onRetry plumbing.
            modifier
                .semantics { contentDescription = "Queued. Will send when online. Tap to try now." }
                .then(if (onRetry != null) Modifier.clickable { onRetry() } else Modifier),
        ) {
            Icon(
                Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Waiting to send — will retry when online",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is SendStateGlyph.Failed -> Row(
            modifier
                .semantics { contentDescription = "Send failed: ${state.reason}. Tap to retry." }
                .then(if (onRetry != null) Modifier.clickable { onRetry() } else Modifier),
        ) {
            Icon(
                Icons.Filled.Error,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MatronRed,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "Failed — tap to retry",
                style = MaterialTheme.typography.labelSmall,
                color = MatronRed,
            )
        }
    }
}
