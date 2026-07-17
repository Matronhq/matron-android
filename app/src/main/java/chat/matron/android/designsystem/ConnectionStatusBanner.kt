package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/// Local mirror of the connection-state enum the banner consumes. Kept in the
/// design-system layer so the banner doesn't import the sync engine; hosts
/// translate from `SyncConnectionState` at the boundary via [syncBannerStateFrom].
sealed interface SyncBannerState {
    data object Connecting : SyncBannerState
    data object Running : SyncBannerState
    data class Offline(val reason: String?) : SyncBannerState
}

/// Thin coloured strip at the top of the chat list surfacing the sync
/// connection state. `.connecting` picks "Connecting…" vs "Reconnecting…" via
/// [hasEverConnected]; `.running` renders nothing; `.offline` is an opaque red
/// strip with the reason.
@Composable
fun ConnectionStatusBanner(
    state: SyncBannerState,
    hasEverConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is SyncBannerState.Running -> Unit
        is SyncBannerState.Connecting -> Row(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (hasEverConnected) "Reconnecting…" else "Connecting…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is SyncBannerState.Offline -> Row(
            modifier
                .fillMaxWidth()
                .background(MatronRed.copy(alpha = 0.9f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(
                state.reason ?: "Offline",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
