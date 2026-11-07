package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import chat.matron.android.events.ContactInviteAsk
import chat.matron.android.events.InviteAskState

/// An agent's ask for an invite link, inline in its conversation: Decline /
/// Approve, and after Approve the link once (QR, Copy link, Share…) until
/// [onDone].
@Composable
fun ContactInviteAskCard(
    ask: ContactInviteAsk,
    state: InviteAskState,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .shadow(2.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(MatronThemeColors.current.bubbleBot)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(ContactInviteAsk.LABEL, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(ContactInviteAsk.HEADLINE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        ask.fromName?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val link = (state as? InviteAskState.Approved)?.link
        if (link != null) {
            InviteLinkReveal(link = link, note = ContactInviteAsk.SHOWN_ONCE)
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        } else if (state.answerable || state is InviteAskState.Sending) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val busy = state is InviteAskState.Sending
                OutlinedButton(onClick = onDecline, enabled = !busy) { Text("Decline") }
                Button(onClick = onApprove, enabled = !busy) { Text("Approve") }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp))
            }
        } else {
            state.resolvedText?.let { text ->
                val positive = state is InviteAskState.Approved || state is InviteAskState.ApprovedElsewhere
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        when {
                            positive -> Icons.Filled.CheckCircle
                            state is InviteAskState.Declined -> Icons.Filled.Cancel
                            else -> Icons.Filled.HourglassEmpty
                        },
                        contentDescription = null,
                        tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        (state as? InviteAskState.Failed)?.let {
            Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
