package chat.matron.android.designsystem

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.matron.android.viewmodels.AccountUnlockModel

private val LockRed = Color(0xFFD32F2F)

/// "This account is locked", across the top of the app while the journal
/// says so: nothing is accepted until the lock is cleared. Ported from
/// matron-apple's `AccountLockedBanner`.
@Composable
fun AccountLockedBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(LockRed)
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("accountLocked.banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Column {
            Text("This account is locked", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Sessions are stopped and nothing can be sent until it is cleared.",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/// Something the user must read after the lock has gone, with a button to put
/// it away.
@Composable
fun AccountWarningBanner(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /// False below the lock banner, which already clears the status bar.
    clearStatusBar: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x40FF9800))
            .then(if (clearStatusBar) Modifier.statusBarsPadding() else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("accountWarning.banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

/// Covers the app while the account is locked and this device can unlock it:
/// the journal froze it instead of signing it out, because it holds an
/// approval key old enough. Ported from matron-apple's `AccountLockedScreen`.
@Composable
fun AccountLockedScreen(
    /// When the lock began, epoch ms.
    since: Long?,
    /// "fingerprint" or "biometrics".
    method: String,
    isUnlocking: Boolean,
    error: String?,
    onUnlock: () -> Unit,
    /// For a device that cannot unlock (its key no longer works).
    onSignOut: (() -> Unit)? = null,
) {
    var confirmingSignOut by remember { mutableStateOf(false) }
    // Back does nothing here: there is nothing behind the lock to go to.
    BackHandler {}
    Surface(
        modifier = Modifier
            .fillMaxSize()
            // Nothing under the locked screen can be reached.
            .pointerInput(Unit) { detectTapGestures { } }
            .testTag("accountLocked.screen"),
    ) {
        Column(
            modifier = Modifier
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = LockRed, modifier = Modifier.padding(top = 24.dp).size(48.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("This account is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                since?.let {
                    Text(
                        "Locked since ${AccountUnlockModel.sinceText(it)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "It was locked to stop it being misused. While it is locked, your boxes take no new work and no device can sign in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "This device can unlock it with its approval key. Unlocking signs out every other device on the account; sign them in again afterwards.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Only unlock it if you know why it was locked.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onUnlock,
                enabled = !isUnlocking,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag("accountLocked.unlock"),
            ) {
                if (isUnlocking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isUnlocking) "Unlocking…" else "Unlock with your $method", fontWeight = FontWeight.SemiBold)
            }
            error?.let {
                Row(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag("accountLocked.error"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (onSignOut != null) {
                OutlinedButton(onClick = { confirmingSignOut = true }, modifier = Modifier.testTag("accountLocked.signOut")) {
                    Text("Can't unlock? Sign Out of This Device")
                }
            }
        }
    }
    if (confirmingSignOut && onSignOut != null) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text("Sign out of this device?") },
            text = {
                Text("While the account is locked you can't sign in again. Once the lock is cleared, sign in and re-attach this device's key.")
            },
            confirmButton = {
                TextButton(onClick = { confirmingSignOut = false; onSignOut() }) {
                    Text("Sign Out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingSignOut = false }) { Text("Cancel") } },
        )
    }
}
