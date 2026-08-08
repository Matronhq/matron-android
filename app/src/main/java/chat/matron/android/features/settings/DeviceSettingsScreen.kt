package chat.matron.android.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.AppearancePicker
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.models.UserSession
import chat.matron.android.viewmodels.AppLockController
import chat.matron.android.viewmodels.AppLockTimeout
import chat.matron.android.viewmodels.DevicesProviding
import kotlinx.coroutines.launch

/**
 * Settings → Device. Ports Features/Settings/DeviceSettingsView.swift: a
 * read-only account summary (userID, deviceID, homeserver host), a Manage
 * Devices link, the appearance picker, and the Privacy section hosting the app
 * lock. Verification/recovery-key sections were Matrix-SDK-only and are absent
 * from the journal stack.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsScreen(
    session: UserSession,
    devicesApi: DevicesProviding?,
    appearance: MatronAppearance,
    onAppearanceChange: (MatronAppearance) -> Unit,
    onManageDevices: () -> Unit,
    onLinkDevice: (() -> Unit)? = null,
    /// Settings → Agent Chats. `null` in hosts without the surface (previews).
    onAgentChats: (() -> Unit)? = null,
    /// `null` in hosts that have no lock (and in previews); the Privacy section
    /// then doesn't render at all.
    appLock: AppLockController? = null,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSection("Account") {
                LabeledRow("User ID", session.userID)
                LabeledRow("Device ID", session.deviceID)
                LabeledRow("Server", hostOf(session.homeserverURL))
            }

            if (devicesApi != null) {
                SettingsSection("Devices") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onManageDevices)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Manage Devices", modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    if (onLinkDevice != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onLinkDevice)
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Link a Device", modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                    if (onAgentChats != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onAgentChats)
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Agent Chats", modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
            }

            SettingsSection("Appearance") {
                AppearancePicker(selected = appearance, onSelect = onAppearanceChange)
            }

            if (appLock != null) AppLockSection(appLock)
        }
    }
}

/**
 * Privacy → app lock. Renders only when the device can actually authenticate:
 * offering a toggle that would immediately fail is worse than offering nothing,
 * and it is the same rule the lock itself stands down on.
 *
 * The toggle writes through [AppLockController.setEnabled], which authenticates
 * BEFORE enabling — so flipping it on raises the system prompt and the switch
 * only moves once that succeeds. The local `switchOn` mirror exists so the
 * switch tracks the controller rather than the user's optimistic tap.
 */
@Composable
private fun AppLockSection(appLock: AppLockController) {
    // Read once per composition: a settings screen is not on screen while the
    // screen lock is being reconfigured, so re-probing on recomposition buys
    // nothing over a stable read.
    val methodName = remember(appLock) { appLock.methodName } ?: return

    val enabled by appLock.isEnabled.collectAsStateWithLifecycle()
    val timeout by appLock.timeout.collectAsStateWithLifecycle()
    val authenticating by appLock.isAuthenticating.collectAsStateWithLifecycle()
    val error by appLock.authError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    SettingsSection("Privacy") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Require unlock", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Lock Matron with $methodName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                enabled = !authenticating,
                onCheckedChange = { wanted -> scope.launch { appLock.setEnabled(wanted) } },
            )
        }

        if (error != null) {
            Text(
                error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (enabled) {
            Text(
                "Lock",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppLockTimeout.entries.forEach { option ->
                    FilterChip(
                        selected = option == timeout,
                        onClick = { appLock.setTimeout(option) },
                        label = { Text(option.shortTitle) },
                    )
                }
            }
            Text(
                timeout.title.replaceFirstChar { it.lowercase() }
                    .let { "Locks $it in the background." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider()
        content()
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Extracts the host from a homeserver URL string, falling back to the whole value. */
private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url
