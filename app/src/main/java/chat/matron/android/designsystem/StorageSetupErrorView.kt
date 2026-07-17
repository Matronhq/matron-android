package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/// Hard-gate UI surfaced when the setup-time secure-storage probe fails during
/// app bootstrap. The recovery-key flow can't function without working
/// encrypted storage (Android Keystore–backed `EncryptedSharedPreferences`),
/// so the host app deliberately replaces the normal onboarding chrome with
/// this view rather than a dismissable banner — the user must see the error
/// before they can sign in.
///
/// The iOS sibling is `KeychainSetupErrorView`; the composable is named for
/// Android's storage layer. [message] is the underlying probe error, surfaced
/// verbatim in monospace so a developer / support engineer can interpret it.
@Composable
fun StorageSetupErrorView(
    message: String,
    modifier: Modifier = Modifier,
    docPath: String = "docs/setup-android.md",
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.GppMaybe,
            contentDescription = null,
            modifier = Modifier.size(60.dp),
            tint = MatronRed,
        )
        Text(
            "Secure storage not configured",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Matron cannot persist your recovery key without secure storage. " +
                "See `$docPath` to fix the configuration, then relaunch.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            message,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
