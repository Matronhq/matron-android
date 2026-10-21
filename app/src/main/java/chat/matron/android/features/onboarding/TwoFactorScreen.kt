package chat.matron.android.features.onboarding

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.matron.android.designsystem.QRCode
import chat.matron.android.journal.TwoFactorDecoding
import chat.matron.android.journal.TwoFactorSetup
import chat.matron.android.viewmodels.SignInViewModel
import kotlinx.coroutines.launch

/**
 * The two-factor steps of a password sign-in, shown in place of the sign-in
 * form while [SignInViewModel.state] is one of them: the code step, setup at
 * sign-in (QR code, setup key, first code), the recovery codes it produces,
 * and the note after signing in with a recovery code.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun TwoFactorSignInScreen(viewModel: SignInViewModel, state: SignInViewModel.State) {
    val title = when (state) {
        is SignInViewModel.State.TwoFactorEnrol -> "Set up two-factor"
        is SignInViewModel.State.RecoveryCodes -> "Recovery codes"
        is SignInViewModel.State.RecoveryCodeUsed -> "Signed in"
        else -> "Two-factor code"
    }
    val cancellable = state is SignInViewModel.State.TwoFactorCode || state is SignInViewModel.State.TwoFactorEnrol
    // System back: Cancel on the code step and setup. Once a token is issued
    // the recovery codes stay up until "I've saved these" (they are shown this
    // once), and back on the recovery-code note simply continues.
    BackHandler {
        when (state) {
            is SignInViewModel.State.RecoveryCodes -> Unit
            is SignInViewModel.State.RecoveryCodeUsed -> viewModel.finishTwoFactor()
            else -> viewModel.cancelTwoFactor()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (cancellable) TextButton(onClick = { viewModel.cancelTwoFactor() }) { Text("Cancel") }
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                is SignInViewModel.State.TwoFactorCode -> CodeStep(viewModel, state)
                is SignInViewModel.State.TwoFactorEnrol -> EnrolStep(viewModel, state)
                is SignInViewModel.State.RecoveryCodes -> RecoveryCodesStep(viewModel, state.codes)
                is SignInViewModel.State.RecoveryCodeUsed -> RecoveryCodeUsedStep(viewModel, state.codesLeft)
                else -> Unit
            }
        }
    }
}

@Composable
private fun CodeStep(viewModel: SignInViewModel, state: SignInViewModel.State.TwoFactorCode) {
    val scope = rememberCoroutineScope()
    var recovery by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf(viewModel.code) }

    fun submit() {
        scope.launch { viewModel.submitCode() }
    }

    Text(
        if (recovery) {
            "Enter one of your recovery codes. Each code works once."
        } else {
            "Enter the 6-digit code from your authenticator app."
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 16.dp),
    )
    if (recovery) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it; viewModel.code = it },
            label = { Text("Recovery code") },
            placeholder = { Text("xxxx-xxxx-xxxx-xxxx") },
            singleLine = true,
            enabled = !state.busy,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        SixDigitField(
            value = code,
            enabled = !state.busy,
            onValueChange = { code = it; viewModel.code = it },
            onComplete = { submit() },
        )
    }

    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

    Button(
        onClick = { submit() },
        enabled = !state.busy && (if (recovery) code.isNotBlank() else code.length == 6),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp)) else Text("Verify")
    }

    TextButton(
        onClick = {
            recovery = !recovery
            code = ""
            viewModel.code = ""
        },
        enabled = !state.busy,
    ) { Text(if (recovery) "Use a code from your authenticator app" else "Use a recovery code instead") }
}

@Composable
private fun EnrolStep(viewModel: SignInViewModel, state: SignInViewModel.State.TwoFactorEnrol) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(viewModel.code) }

    Text(
        "This server needs two-factor sign-in. Set it up now with an authenticator app such as " +
            "Google Authenticator or 1Password.",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 16.dp),
    )

    val setup = state.setup
    if (setup == null) {
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                Text("Getting your setup key…")
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { scope.launch { viewModel.retryEnrolment() } }) { Text("Try again") }
        }
        return
    }

    Text("1. Add Matron to your authenticator app", style = MaterialTheme.typography.labelLarge)
    TwoFactorSetupKey(setup)

    Text("2. Enter the 6-digit code it shows", style = MaterialTheme.typography.labelLarge)
    SixDigitField(
        value = code,
        enabled = !state.busy,
        onValueChange = { code = it; viewModel.code = it },
        onComplete = { scope.launch { viewModel.confirmEnrolment() } },
    )
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = { scope.launch { viewModel.confirmEnrolment() } },
        enabled = !state.busy && code.length == 6,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp)) else Text("Turn on and sign in")
    }
}

/**
 * The setup key for an authenticator app: the QR code, the key as text for
 * an app that can't scan, "Open in authenticator" and "Copy setup key".
 * Shared by setup at sign-in and Settings → Security.
 */
@Composable
internal fun TwoFactorSetupKey(setup: TwoFactorSetup) {
    val context = LocalContext.current
    var note by remember(setup) { mutableStateOf<String?>(null) }
    val bitmap = remember(setup.otpauthURI) { QRCode.bitmap(setup.otpauthURI) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Two-factor setup QR code",
            modifier = Modifier.size(200.dp),
        )
        Text(
            "Setup key",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            TwoFactorDecoding.groupedSecret(setup.secret),
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            textAlign = TextAlign.Center,
        )
        TwoFactorDecoding.shownAs(setup)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                note = try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(setup.otpauthURI)))
                    null
                } catch (_: ActivityNotFoundException) {
                    "No authenticator app on this phone opens setup links. Scan the QR code or type the setup key."
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open in authenticator") }
        OutlinedButton(
            onClick = {
                copySensitive(context, "Matron setup key", setup.secret)
                note = "Setup key copied."
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Copy setup key") }
    }
    note?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecoveryCodesStep(viewModel: SignInViewModel, codes: List<String>) {
    Text(
        "Two-factor sign-in is on.",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
    )
    RecoveryCodesPanel(codes, doneLabel = "I've saved these", onDone = { viewModel.finishTwoFactor() })
}

/**
 * Recovery codes, shown this once: the list, Copy and Share, and [doneLabel]
 * to move on. Shared by setup at sign-in and Settings → Security.
 */
@Composable
internal fun RecoveryCodesPanel(codes: List<String>, doneLabel: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var copied by remember(codes) { mutableStateOf(false) }
    val text = codes.joinToString("\n")

    Text(
        "Save these recovery codes somewhere safe, such as a password manager. " +
            "Each one signs you in once if you lose your authenticator app.",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "They won't be shown again.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            codes.forEach { code ->
                Text(
                    code,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                copySensitive(context, "Matron recovery codes", text)
                copied = true
            },
            modifier = Modifier.weight(1f),
        ) { Text(if (copied) "Copied" else "Copy") }
        OutlinedButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Matron recovery codes")
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Save recovery codes"))
            },
            modifier = Modifier.weight(1f),
        ) { Text("Share") }
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(doneLabel) }
}

@Composable
private fun RecoveryCodeUsedStep(viewModel: SignInViewModel, codesLeft: Int?) {
    Text(
        SignInViewModel.recoveryCodesLeftText(codesLeft),
        style = MaterialTheme.typography.bodyLarge,
        color = if (codesLeft != null && codesLeft <= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 16.dp),
    )
    Text(
        "You can make a new set of recovery codes in Settings → Security.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { viewModel.finishTwoFactor() }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
}

/// A 6-digit code field: digits only, number keyboard, the one-time-code
/// autofill hint, and [onComplete] once the sixth digit lands.
@Composable
internal fun SixDigitField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onComplete: () -> Unit,
) {
    fun accept(raw: String) {
        val digits = raw.filter { it.isDigit() }.take(6)
        if (digits == value) return
        onValueChange(digits)
        if (digits.length == 6) onComplete()
    }
    OutlinedTextField(
        value = value,
        onValueChange = ::accept,
        label = { Text("6-digit code") },
        singleLine = true,
        enabled = enabled,
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (value.length == 6) onComplete() }),
        modifier = Modifier
            .fillMaxWidth()
            .oneTimeCodeAutofill(::accept),
    )
}

/// Offers the field to autofill services as a one-time code.
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.oneTimeCodeAutofill(onFill: (String) -> Unit): Modifier = composed {
    val autofill = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val node = remember { AutofillNode(autofillTypes = listOf(AutofillType.SmsOtpCode), onFill = onFill) }
    DisposableEffect(node) {
        tree += node
        onDispose { autofill?.cancelAutofillForNode(node) }
    }
    onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { focus ->
            if (focus.isFocused) autofill?.requestAutofillForNode(node) else autofill?.cancelAutofillForNode(node)
        }
}

/// Copies a secret, flagged sensitive so Android 13+ hides it from the
/// clipboard preview.
private fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}
