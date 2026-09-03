package chat.matron.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.journal.DeviceDTO
import chat.matron.android.viewmodels.DevicesProviding
import chat.matron.android.viewmodels.DevicesViewModel
import chat.matron.android.viewmodels.isClient
import chat.matron.android.viewmodels.lagText
import chat.matron.android.viewmodels.lastSeenText
import kotlinx.coroutines.launch

/**
 * Settings → Manage Devices. Ports Features/Settings/DevicesView.swift: the
 * signed-in user's device roster with per-device revoke and the Add Agent
 * pairing sheet. Pull-based — refreshed on appear and after every mutation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    api: DevicesProviding,
    onSelfRevoked: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember { DevicesViewModel(api = api, onSelfRevoked = onSelfRevoked) }
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var confirming by remember { mutableStateOf<DeviceDTO?>(null) }
    // The device whose rename dialog is open, and the draft in its field.
    // Two pieces of state, not one, mirroring the iOS alert (the field's
    // binding must survive the dialog's own recompositions).
    var renaming by remember { mutableStateOf<DeviceDTO?>(null) }
    var draftName by remember { mutableStateOf("") }
    var showingAddAgent by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    IconButton(onClick = { showingAddAgent = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Agent")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (errorMessage != null) {
                item {
                    Text(
                        errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(devices, key = { it.id }) { device ->
                DeviceRow(
                    device = device,
                    onRevoke = { confirming = device },
                    onRename = { draftName = device.name; renaming = device },
                )
            }
            item {
                Text(
                    "Agents are headless machines running the bridge. Revoking a device signs it out immediately — there's no undo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    confirming?.let { device ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(if (device.isSelf) "Sign out this device?" else "Revoke “${device.name}”?") },
            text = {
                Text(
                    if (device.isSelf) {
                        "This device loses access immediately and you'll be returned to sign-in."
                    } else {
                        "The device loses access immediately. There's no undo — re-enroll it to restore access."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = device
                    confirming = null
                    scope.launch { viewModel.revoke(target) }
                }) { Text(if (device.isSelf) "Sign Out" else "Revoke") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } },
        )
    }

    renaming?.let { device ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This name labels the box everywhere — in Devices and on the chip beside each conversation.")
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = device
                    val name = draftName
                    renaming = null
                    scope.launch { viewModel.rename(target, name) }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    if (showingAddAgent) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                showingAddAgent = false
                scope.launch { viewModel.refresh() }
            },
            sheetState = sheetState,
        ) {
            AddAgentSheet(
                api = api,
                existingNames = devices.map { it.name },
                onDone = {
                    showingAddAgent = false
                    scope.launch { viewModel.refresh() }
                },
            )
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceDTO, onRevoke: () -> Unit, onRename: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (device.isClient) Icons.Default.PhoneAndroid else Icons.Default.Dns,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    device.name.ifEmpty { "Unnamed device" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (device.isSelf) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("This device") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
            Text(
                "${device.kind.replaceFirstChar { it.uppercase() }} · Last seen ${device.lastSeenText()} · ${device.lagText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Rename sits before the destructive action (the Mac row's button
        // pair; iOS reaches the same alert via a leading swipe).
        TextButton(onClick = onRename) { Text("Rename") }
        TextButton(onClick = onRevoke) {
            Text(
                if (device.isSelf) "Sign Out" else "Revoke",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
