package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/// The app lock screen. Ports iOS's `AppLockOverlay` / Mac's `MacLockOverlay`
/// (matron-apple #83), with one deliberate structural difference.
///
/// On iOS the shield was a separate `UIWindow` above `.alert`, because sheets
/// present in their own windows that a root `.overlay` cannot cover. Compose has
/// the same problem — `ModalBottomSheet` and `Dialog` each render in their own
/// platform window, so an in-composition `Box` drawn "on top" would leave an
/// open sheet visible. Android has no ordered-window equivalent to reach for, so
/// the app root renders this INSTEAD of the app content rather than over it:
/// nothing composed means no sheet or dialog can exist to escape the shield, and
/// a cold launch that starts locked never composes content for a frame. That is
/// a stronger guarantee than the overlay it replaces, at the cost of resetting
/// the navigation stack on unlock.
///
/// Presentational only — the host owns the [AppLockController] and passes state
/// down, matching this package's plain-params convention.
///
/// [onUnlock] fires once automatically when the shield appears, so the common
/// case is "return to the app, prompt is already up". It is NOT retried on
/// recomposition: the credential fallback runs in a separate activity, and
/// re-prompting on every return would loop the user in a prompt they cannot
/// dismiss. The button covers retries.
@Composable
fun AppLockShield(
    isAuthenticating: Boolean,
    errorMessage: String?,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { onUnlock() }

    val colors = LocalMatronColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            // Opaque by construction — the app's own timeline gradient, so the
            // shield reads as part of Matron rather than a system screen.
            .matronTimelineBackground(colors),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = colors.accent,
            )
            Text(
                "Matron is locked",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (errorMessage != null) {
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Button(onClick = onUnlock, enabled = !isAuthenticating) {
                if (isAuthenticating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Unlock")
                }
            }
        }
    }
}
