package chat.matron.android.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.models.UserSession
import chat.matron.android.viewmodels.LinkSignInViewModel
import chat.matron.android.viewmodels.SignInViewModel
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

/**
 * Sign-in screen. Ports Features/Onboarding/SignInView.swift.
 *
 * [SignInViewModel] holds `serverURL` / `username` / `password` as plain vars
 * (not flows), so each field mirrors the var in local snapshot state and writes
 * through on change; `state` is a StateFlow observed for the busy / error / signed
 * -in transitions. `onSignedIn` fires once when the VM reaches `SignedIn`.
 *
 * [LinkSignInViewModel] adds the "sign in from another device" path: scan a QR
 * code via the Play-services code scanner (no camera permission needed) or type
 * the link code shown under the QR manually. While `linkState` is
 * `WaitingForApproval` the form is replaced with a waiting indicator; reaching
 * `SignedIn` on either view model funnels through the same `onSignedIn`.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    viewModel: SignInViewModel,
    linkViewModel: LinkSignInViewModel,
    onSignedIn: (UserSession) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()

    var server by remember { mutableStateOf(viewModel.serverURL) }
    var username by remember { mutableStateOf(viewModel.username) }
    var password by remember { mutableStateOf(viewModel.password) }

    LaunchedEffect(state) {
        (state as? SignInViewModel.State.SignedIn)?.let { onSignedIn(it.session) }
    }

    val linkState by linkViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showManualCode by remember { mutableStateOf(false) }
    var linkCode by remember { mutableStateOf(linkViewModel.codeInput) }
    var scannerUnavailable by remember { mutableStateOf(false) }

    LaunchedEffect(linkState) {
        (linkState as? LinkSignInViewModel.State.SignedIn)?.let { onSignedIn(it.session) }
    }

    val busy = state is SignInViewModel.State.Busy
    val errorMessage = (state as? SignInViewModel.State.Error)?.message
    val canSubmit = !busy && server.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()

    fun submit() {
        if (!canSubmit) return
        scope.launch { viewModel.submit() }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Sign in to Matron") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (linkState is LinkSignInViewModel.State.WaitingForApproval) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    Text("Waiting for approval on your other device…")
                }
                Text(
                    "Approve the request on your signed-in device to finish.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { linkViewModel.cancel() }) { Text("Cancel") }
            } else {
                Text(
                    "Server",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it; viewModel.serverURL = it },
                    label = { Text("Homeserver URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Credentials", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; viewModel.username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; viewModel.password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMessage != null) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }

                Button(
                    onClick = { submit() },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                    else Text("Sign in")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("From another device", style = MaterialTheme.typography.labelLarge)
                Button(
                    onClick = {
                        val options = GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build()
                        GmsBarcodeScanning.getClient(context, options).startScan()
                            .addOnSuccessListener { barcode ->
                                barcode.rawValue?.let { payload ->
                                    scope.launch { linkViewModel.handleScanned(payload) }
                                }
                            }
                            .addOnFailureListener { scannerUnavailable = true }
                        // Cancelled scans call neither listener path we care
                        // about — the user is simply back on this form.
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Scan QR code") }
                if (scannerUnavailable) {
                    Text(
                        "Scanner unavailable — use a link code instead.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { showManualCode = !showManualCode }) {
                    Text(if (showManualCode) "Hide link code" else "Have a link code?")
                }
                if (showManualCode) {
                    OutlinedTextField(
                        value = linkCode,
                        onValueChange = {
                            linkViewModel.codeInput = it
                            linkCode = linkViewModel.codeInput // reflect auto-formatting
                        },
                        label = { Text("XXXX-XXXX") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            linkViewModel.serverURL = server // shares the form's server field
                            scope.launch { linkViewModel.submitManual() }
                        },
                        enabled = server.isNotEmpty() && linkCode.length >= 9,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Sign in with code") }
                    Text(
                        "On your signed-in device: Settings → Link a Device. Enter the server URL above and the code shown under the QR.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                (linkState as? LinkSignInViewModel.State.Error)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
