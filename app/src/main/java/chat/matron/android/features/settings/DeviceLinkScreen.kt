package chat.matron.android.features.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.matron.android.designsystem.QRCode
import chat.matron.android.journal.RelayRendezvousing
import chat.matron.android.platform.Haptics
import chat.matron.android.viewmodels.DeviceLinkViewModel
import chat.matron.android.viewmodels.DeviceLinking
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

/**
 * Settings → "Link a Device": shows a QR the new device scans, then the
 * approve card once someone claims it. The QR self-refreshes on expiry for
 * as long as the screen is open. Ports matron-apple's DeviceLinkView.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DeviceLinkScreen(
    api: DeviceLinking,
    serverURL: String,
    relay: RelayRendezvousing,
    haptics: Haptics,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val viewModel = remember {
        DeviceLinkViewModel(api = api, serverURL = serverURL, relay = relay, scope = scope, haptics = haptics)
    }
    val phase by viewModel.phase.collectAsState()
    val notice by viewModel.noticeMessage.collectAsState()
    val isSubmitting by viewModel.isSubmitting.collectAsState()

    var linkTab by remember { mutableStateOf(0) } // 0 = Show, 1 = Scan
    var scannerUnavailable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.start() }
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Link a Device") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (notice != null) {
                Text(notice!!, color = MaterialTheme.colorScheme.tertiary)
            }
            when (val p = phase) {
                // Loading/Showing are the only phases that still let the user
                // switch tabs — everything past a claim takes over the whole
                // screen below, exactly as before tabs existed.
                DeviceLinkViewModel.Phase.Loading, is DeviceLinkViewModel.Phase.Showing -> {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf("Show", "Scan").forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = linkTab == index,
                                onClick = { linkTab = index },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                            ) { Text(label) }
                        }
                    }
                    if (linkTab == 0) {
                        when (p) {
                            is DeviceLinkViewModel.Phase.Showing -> {
                                val payload = viewModel.qrPayload
                                if (payload != null) {
                                    val bitmap = remember(payload) { QRCode.bitmap(payload) }
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Sign-in QR code",
                                        modifier = Modifier.size(240.dp),
                                    )
                                }
                                // Camera-less fallback: typed under "Have a link code?"
                                Text(
                                    p.code,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "On your new device, open Matron and choose “Scan QR code” — or type the code under “Have a link code?”. Codes refresh automatically.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> CircularProgressIndicator()
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "If your computer is showing a Matron QR code, scan it to sign it in as you.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = {
                                scannerUnavailable = false
                                val options = GmsBarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                    .build()
                                GmsBarcodeScanning.getClient(context, options).startScan()
                                    .addOnSuccessListener { barcode ->
                                        barcode.rawValue?.let { payload ->
                                            scope.launch { viewModel.offerScanned(payload) }
                                        }
                                    }
                                    .addOnFailureListener { scannerUnavailable = true }
                            }) { Text("Scan the computer's QR code") }
                            if (scannerUnavailable) {
                                Text("Couldn't open the scanner on this device.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                is DeviceLinkViewModel.Phase.Claimed -> {
                    Text(
                        "${p.deviceName} at ${p.requesterIP} wants to sign in to your account. Only approve if this is your device.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { scope.launch { viewModel.deny() } },
                            enabled = !isSubmitting,
                        ) { Text("Deny") }
                        Button(
                            onClick = { scope.launch { viewModel.approve() } },
                            enabled = !isSubmitting,
                        ) { Text("Approve") }
                    }
                    Text(
                        "This signs a computer into your account — only approve if it's yours, in front of you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DeviceLinkViewModel.Phase.Approved ->
                    Text("Approved — finishing sign-in on the other device.")
                DeviceLinkViewModel.Phase.Denied ->
                    Text("Denied. No device was signed in.")
                DeviceLinkViewModel.Phase.Unsupported ->
                    Text("Server doesn't support device linking yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                is DeviceLinkViewModel.Phase.Error -> {
                    Text(p.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { scope.launch { viewModel.start() } }) { Text("Try again") }
                }
            }
        }
    }
}
