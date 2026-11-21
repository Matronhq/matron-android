package chat.matron.android.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.viewmodels.ApprovalCenter.Status
import chat.matron.android.viewmodels.ApprovalKeysScreenModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/// The orange iOS uses for "act on this".
private val AttentionOrange = Color(0xFFE08A00)

/// Settings ▸ Approval keys: this device's key and its fingerprint, requests
/// from other devices waiting to be approved, and every key of the account.
/// Ported from matron-apple's `ApprovalKeysScreen`.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ApprovalKeysScreen(model: ApprovalKeysScreenModel, onBack: () -> Unit, poll: Boolean = true) {
    // Every flow the words are computed from, read so the screen recomposes.
    val status by model.center.status.collectAsStateWithLifecycle()
    val state by model.center.state.collectAsStateWithLifecycle()
    model.center.loadError.collectAsStateWithLifecycle().value
    model.center.deviceKeyID.collectAsStateWithLifecycle().value
    val busy by model.isBusy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val form by model.form.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<ApprovalKeysScreenModel.KeyRow?>(null) }

    if (poll) {
        LaunchedEffect(model) {
            // Nothing pushes another device's request, or its approval of this
            // one's: look again every few seconds while the screen is up.
            while (true) {
                model.load()
                delay(if (model.center.status.value == Status.WaitingForApproval) 3_000 else 6_000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ApprovalKeysScreenModel.TITLE) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
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
            SettingsSection("This device") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("approvalKeys.status"),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        statusIcon(status),
                        contentDescription = null,
                        tint = if (status == Status.Ready) Color(0xFF2E9E4F) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(model.statusTitle, style = MaterialTheme.typography.titleMedium)
                        if (model.statusDetail.isNotEmpty()) {
                            Text(model.statusDetail, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                model.fingerprint?.let {
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("approvalKeys.fingerprint")) {
                        Text("Fingerprint", modifier = Modifier.weight(1f))
                        FingerprintText(it)
                    }
                }
                if (model.canAttach) {
                    TextButton(onClick = { scope.launch { model.attach() } }, enabled = !busy,
                        modifier = Modifier.testTag("approvalKeys.attach")) { Text("Re-attach This Device's Key") }
                }
                if (model.canSetUp) {
                    TextButton(onClick = { model.beginSetUp() }, enabled = !busy, modifier = Modifier.testTag("approvalKeys.setUp")) {
                        Text(if (status == Status.WaitingForApproval) "Use Password and Code Instead" else "Set Up with Password and Code")
                    }
                }
                if (model.canAskAnotherDevice) {
                    TextButton(onClick = { scope.launch { model.askAnotherDevice() } }, enabled = !busy,
                        modifier = Modifier.testTag("approvalKeys.ask")) { Text("Ask a Device That Has a Key") }
                }
                if (form == null) {
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("approvalKeys.error"))
                    }
                }
                Footer(ApprovalKeysScreenModel.INTRO)
            }

            val requests = model.requests
            if (requests.isNotEmpty()) {
                SettingsSection("Waiting to be added") {
                    requests.forEach { request ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(request.name)
                                FingerprintText(request.fingerprint)
                            }
                            Button(
                                onClick = { scope.launch { model.approve(request) } },
                                enabled = !busy && model.center.canSign,
                                modifier = Modifier.testTag("approvalKeys.approve"),
                            ) { Text("Approve…") }
                        }
                    }
                    Footer(
                        if (model.center.canSign) "Approve only when the other device shows the same fingerprint."
                        else "Only a device that has a key can approve these.",
                    )
                }
            }

            val rows = model.rows(state)
            if (rows.isNotEmpty()) {
                SettingsSection("Keys on this account") {
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        row.key.name,
                                        textDecoration = if (row.isLive) null else TextDecoration.LineThrough,
                                        color = if (row.isLive) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (row.isThisDevice) {
                                        Text(
                                            "This device",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                                FingerprintText(row.key.fingerprint)
                                Text(row.detail, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                row.state?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = if (row.isLive) AttentionOrange else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (row.isLive) {
                                TextButton(onClick = { removing = row }, enabled = !busy, modifier = Modifier.testTag("approvalKeys.remove")) {
                                    Text("Remove", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    Footer(ApprovalKeysScreenModel.PINNING_NOTE)
                }
            }
        }
    }

    removing?.let { row ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove the approval key ${row.key.name}?") },
            text = {
                Text(
                    "It will no longer approve anything, and can't be added back. Boxes that pin it keep trusting it " +
                        "until their key file is changed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch { model.remove(row.key) }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    form?.let { ApprovalKeyPasswordForm(model, it) }
}

private fun statusIcon(status: Status) = when (status) {
    Status.Ready -> Icons.Filled.GppGood
    Status.NeedsAttach, Status.WaitingForApproval -> Icons.Filled.GppMaybe
    Status.Revoked, Status.Invalidated -> Icons.Filled.GppBad
    Status.NoAccountKey, Status.NoDeviceKey -> Icons.Outlined.Shield
    else -> Icons.Filled.Security
}

@Composable
private fun Footer(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp))
}

/// A key's short fingerprint, in monospace and split in two so two devices'
/// are easy to compare by eye.
@Composable
fun FingerprintText(fingerprint: String) {
    SelectionContainer {
        Text(
            ApprovalKeysScreenModel.grouped(fingerprint),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/// The password-and-code form: setting this device's key up, or removing a
/// key this device cannot sign for.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalKeyPasswordForm(model: ApprovalKeysScreenModel, form: ApprovalKeysScreenModel.Form) {
    val scope = rememberCoroutineScope()
    val password by model.password.collectAsStateWithLifecycle()
    val code by model.code.collectAsStateWithLifecycle()
    val busy by model.isBusy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val method = model.center.method ?: "fingerprint"
    val (title, explanation, button) = when (form) {
        ApprovalKeysScreenModel.Form.SetUp -> Triple(
            "Set Up a Key",
            "A new key is made in this device's secure hardware and added to your account. Afterwards, dangerous " +
                "actions need your $method on a device that holds a key. A recovery code can't be used here.",
            "Set Up",
        )
        is ApprovalKeysScreenModel.Form.Remove -> Triple(
            "Remove Key",
            "Removes ${form.key.name} (${ApprovalKeysScreenModel.grouped(form.key.fingerprint)}) with your password " +
                "and a code, because this device has no key to sign with.",
            "Remove Key",
        )
    }
    Dialog(
        onDismissRequest = { if (!busy) model.closeForm() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { TextButton(onClick = { model.closeForm() }, enabled = !busy) { Text("Cancel") } },
                    actions = {
                        TextButton(
                            onClick = { scope.launch { model.submitForm() } },
                            enabled = model.canSubmitForm,
                            modifier = Modifier.testTag("approvalKeyForm.submit"),
                        ) { Text(button) }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = password, onValueChange = model::setPassword, label = { Text("Password") },
                    singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("approvalKeyForm.password"),
                )
                OutlinedTextField(
                    value = code, onValueChange = model::setCode, label = { Text("Code from your authenticator app") },
                    singleLine = true, enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().testTag("approvalKeyForm.code"),
                )
                Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("approvalKeyForm.error"))
                }
                if (busy) CircularProgressIndicator()
            }
        }
    }
}

/// The Settings row that opens the keys screen, with where this device stands
/// under it.
@Composable
internal fun ApprovalKeysRow(model: ApprovalKeysScreenModel, onOpen: () -> Unit) {
    model.center.status.collectAsStateWithLifecycle().value
    model.center.state.collectAsStateWithLifecycle().value
    LaunchedEffect(model) { model.load() }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp).testTag("settings.approvalKeys"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(ApprovalKeysScreenModel.TITLE)
            val summary = model.summary
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model.needsAttention) AttentionOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (model.needsAttention) {
            Icon(Icons.Filled.Error, contentDescription = "Needs attention", tint = AttentionOrange)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
