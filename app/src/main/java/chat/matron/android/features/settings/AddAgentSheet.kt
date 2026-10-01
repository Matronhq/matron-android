package chat.matron.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.viewmodels.DevicesProviding
import chat.matron.android.viewmodels.PairingViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The "Add agent" pairing sheet. Ports Features/Settings/AddAgentSheet.swift:
 * enter the 8-char code (typed, or scanned from the box's `matron://pair` QR)
 * → see WHO is asking (requester IP, anti-phish) → name
 * it → approve → wait for the box to claim its token.
 *
 * [PairingViewModel.codeInput] / [PairingViewModel.agentName] are custom get/set
 * with side effects (reformat, duplicate-name warning, debounced preview), so the
 * fields write through and read the reformatted value back.
 */
@Composable
fun AddAgentSheet(
    api: DevicesProviding,
    /// The signed-in account's journal base URL; a scanned pairing QR for any
    /// other server is refused.
    accountServerURL: String,
    existingNames: List<String>,
    /// Tag characters already in use across the roster (apple #158).
    existingTags: List<String> = emptyList(),
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val viewModel = remember { PairingViewModel(
            api = api,
            existingNames = existingNames,
            accountServerURL = accountServerURL,
            scope = scope,
            existingTags = existingTags,
        ) }
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val duplicateWarning by viewModel.duplicateNameWarning.collectAsStateWithLifecycle()
    val isApproving by viewModel.isApproving.collectAsStateWithLifecycle()
    val expiresAt by viewModel.expiresAt.collectAsStateWithLifecycle()

    var code by remember { mutableStateOf(viewModel.codeInput) }
    var name by remember { mutableStateOf(viewModel.agentName) }
    var tag by remember { mutableStateOf(viewModel.tagChar) }
    val duplicateTagWarning by viewModel.duplicateTagWarning.collectAsStateWithLifecycle()
    var scannerUnavailable by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { viewModel.cancelWaiting() } }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Add Agent", style = MaterialTheme.typography.titleLarge)

        when (val current = phase) {
            is PairingViewModel.Phase.EnterCode,
            is PairingViewModel.Phase.Preview,
            -> {
                OutlinedTextField(
                    value = code,
                    onValueChange = { viewModel.codeInput = it; code = viewModel.codeInput },
                    label = { Text("Pairing code") },
                    placeholder = { Text("XXXX-XXXX") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        scannerUnavailable = false
                        val options = GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build()
                        GmsBarcodeScanning.getClient(context, options).startScan()
                            .addOnSuccessListener { barcode ->
                                barcode.rawValue?.let { payload ->
                                    viewModel.handleScanned(payload)
                                    code = viewModel.codeInput // reflect the scanned code (or the cleared field)
                                }
                            }
                            .addOnFailureListener { scannerUnavailable = true }
                        // A cancelled scan calls neither listener path we care
                        // about — the user is simply back on this sheet.
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR") }
                if (scannerUnavailable) {
                    Text(
                        "Scanner unavailable — type the code instead.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        "On the box, start pairing — it prints a code like KTNM-3VQ8.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (current is PairingViewModel.Phase.Preview) {
                    Text(
                        "A device at ${current.requesterIP} is asking to connect as an agent on your account. Only approve if this is your machine — check the code on its terminal.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    expiresAt?.let { deadline -> ExpiryCountdown(deadline) }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { viewModel.agentName = it; name = viewModel.agentName },
                        label = { Text("Agent name (e.g. dev-7)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        duplicateWarning ?: "Convention: the box's short hostname. The name can't be changed later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (duplicateWarning == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.tertiary,
                    )
                    // Optional roster tag character (apple #158): journal-held,
                    // so the letter shows on every device from day one.
                    OutlinedTextField(
                        value = tag,
                        onValueChange = { viewModel.tagChar = it; tag = it },
                        label = { Text("Tag character (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        duplicateTagWarning ?: "One character shown before chat titles. Leave empty to derive it from the name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (duplicateTagWarning == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.tertiary,
                    )
                    Button(
                        onClick = { scope.launch { viewModel.approve() } },
                        enabled = !isApproving && name.trim().isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Approve") }
                }
            }

            is PairingViewModel.Phase.WaitingForClaim -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("Waiting for the agent to connect…")
                }
                if (errorMessage != null) {
                    Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "This finishes automatically once the box collects its token — usually a few seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is PairingViewModel.Phase.Success -> {
                Text(
                    "${current.agentName} is connected.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

/** Live "expires in m:ss" countdown driven off the VM's `expiresAt`. */
@Composable
private fun ExpiryCountdown(deadline: Instant) {
    val remaining by produceState(initialValue = secondsUntil(deadline), deadline) {
        while (true) {
            value = secondsUntil(deadline)
            delay(1_000)
        }
    }
    Text(
        text = if (remaining > 0) {
            "Code expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"
        } else {
            "Code expired — get a fresh one from the box."
        },
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (remaining > 60) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.error,
    )
}

private fun secondsUntil(deadline: Instant): Int =
    (deadline.epochSecond - Instant.now().epochSecond).toInt()
