package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.matron.android.events.ConsentDecisionEvent
import chat.matron.android.events.CoordinatorConsentBadge
import chat.matron.android.events.SessionStopState

/// "Approved by the Coordinator — <reason>" on a spawn card the Coordinator
/// answered, with **Stop session** once the session it let start is
/// running: the one tap that overrules the Coordinator. Ported from
/// matron-apple's `CoordinatorConsentBadgeView`.
///
/// Pure: plain values and lambdas only.
@Composable
fun CoordinatorConsentBadgeView(
    badge: CoordinatorConsentBadge,
    /// Stops the conversation the badge names. Null omits the button.
    onStopSession: ((convoID: String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val approved = badge.decision.decision == ConsentDecisionEvent.Decision.APPROVE
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (approved) Icons.Filled.VerifiedUser else Icons.Filled.GppBad,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                badge.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val convoID = badge.stoppableConvoID
        if (convoID != null && onStopSession != null) StopControl(badge.stop) { onStopSession(convoID) }
    }
}

@Composable
private fun StopControl(stop: SessionStopState, onStop: () -> Unit) {
    when (stop) {
        SessionStopState.Idle, is SessionStopState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = onStop,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Stop session") }
            (stop as? SessionStopState.Failed)?.let {
                Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        SessionStopState.Sending -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Stopping…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SessionStopState.Sent -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.StopCircle, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
            )
            Text(
                "Stop sent to the session",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
