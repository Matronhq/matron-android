package chat.matron.android.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp

/// Floating "stop the current turn" affordance for the chat timeline. Shown as
/// an overlay in the top-trailing corner while the conversation's durable
/// session_state is "running" (with the ephemeral activity indicator OR-ed in
/// as a fast path); tapping invokes [onClick], which the host binds to sending
/// the bridge's `!esc` interrupt. Same shape language and tint as
/// [JumpToBottomButton] so the two floating chat controls read as one family —
/// this one sits on the opposite end of the same trailing edge. Ported from
/// matron-apple's `StopTurnButton` (neutral tint per Dan, 2026-08-05).
@Composable
fun StopTurnButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier
            .padding(end = 16.dp, top = 8.dp)
            .size(44.dp)
            .shadow(4.dp, CircleShape),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(
            Icons.Filled.Stop,
            contentDescription = "Stop the current turn",
            modifier = Modifier.size(24.dp),
        )
    }
}
