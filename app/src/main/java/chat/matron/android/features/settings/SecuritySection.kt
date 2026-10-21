package chat.matron.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.features.onboarding.RecoveryCodesPanel
import chat.matron.android.features.onboarding.SixDigitField
import chat.matron.android.features.onboarding.TwoFactorSetupKey
import chat.matron.android.viewmodels.TwoFactorSettingViewModel
import chat.matron.android.viewmodels.TwoFactorSettingViewModel.ConfirmAction
import chat.matron.android.viewmodels.TwoFactorSettingViewModel.SetupPhase
import chat.matron.android.viewmodels.TwoFactorSettingViewModel.Sheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings → Security, as in Matron Web: two-factor sign-in on or off, with
 * Set Up… while it's off, and New Recovery Codes…, Change App… and Turn
 * Off… while it's on (Turn Off… hidden while the server requires it).
 * Hidden until `GET /2fa` answers, and for good on a journal that 404s it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SecuritySection(model: TwoFactorSettingViewModel) {
    LaunchedEffect(model) { model.load() }
    // The sheets' work runs here, not in a scope of their own: closing a
    // sheet leaves its composition, and the reload that follows must outlive it.
    val scope = rememberCoroutineScope()
    val state by model.state.collectAsStateWithLifecycle()
    val sheet by model.sheet.collectAsStateWithLifecycle()
    if (state == TwoFactorSettingViewModel.State.Loading || state == TwoFactorSettingViewModel.State.Unsupported) return

    SettingsSection("Security") {
        when (val s = state) {
            is TwoFactorSettingViewModel.State.Loaded -> {
                val status = s.status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(TwoFactorSettingViewModel.TITLE, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            TwoFactorSettingViewModel.detailText(status),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!status.enabled && status.available) {
                        TextButton(onClick = { model.openSetUp() }) { Text(TwoFactorSettingViewModel.SET_UP) }
                    }
                }
                if (TwoFactorSettingViewModel.runningLow(status)) {
                    Text(
                        TwoFactorSettingViewModel.RUNNING_LOW,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (status.enabled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { model.openNewRecoveryCodes() }) {
                            Text(TwoFactorSettingViewModel.NEW_RECOVERY_CODES)
                        }
                        TextButton(onClick = { model.openChangeApp() }) { Text(TwoFactorSettingViewModel.CHANGE_APP) }
                        if (!status.required) {
                            TextButton(onClick = { model.openTurnOff() }) {
                                Text(TwoFactorSettingViewModel.TURN_OFF, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (status.required) {
                    Text(
                        TwoFactorSettingViewModel.REQUIRED,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            is TwoFactorSettingViewModel.State.Failed -> Text(
                s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            else -> Unit
        }
    }

    sheet?.let { TwoFactorSheet(model, it, scope) }
}

/// A full-screen sheet over Settings, like the sign-in steps: Cancel in the
/// top bar until the recovery codes are up, then only Done closes it.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TwoFactorSheet(model: TwoFactorSettingViewModel, sheet: Sheet, scope: CoroutineScope) {
    val showingCodes = when (sheet) {
        is Sheet.Setup -> sheet.phase == SetupPhase.Codes
        is Sheet.Confirm -> sheet.recoveryCodes != null
    }
    val title = when (sheet) {
        is Sheet.Setup -> TwoFactorSettingViewModel.setupTitle(sheet.moving)
        is Sheet.Confirm -> TwoFactorSettingViewModel.confirmTitle(sheet.action)
    }
    fun close() {
        scope.launch { model.closeSheet() }
    }
    Dialog(
        // The recovery codes are shown this once: back does nothing until Done.
        onDismissRequest = { if (!showingCodes) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (!showingCodes) TextButton(onClick = { close() }) { Text("Cancel") }
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
                when (sheet) {
                    is Sheet.Setup -> SetupSheet(model, sheet, scope, onDone = { close() })
                    is Sheet.Confirm -> ConfirmSheet(model, sheet, scope, onDone = { close() })
                }
            }
        }
    }
}

@Composable
private fun SetupSheet(model: TwoFactorSettingViewModel, sheet: Sheet.Setup, scope: CoroutineScope, onDone: () -> Unit) {
    when (sheet.phase) {
        SetupPhase.Password -> {
            var password by remember { mutableStateOf("") }
            var currentCode by remember { mutableStateOf("") }
            val ready = password.isNotEmpty() && (!sheet.moving || currentCode.isNotBlank())
            fun submit() {
                if (ready) scope.launch { model.submitPassword(password, currentCode) }
            }
            Text(
                TwoFactorSettingViewModel.setupIntro(sheet.moving),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            PasswordField(password, enabled = !sheet.busy, onChange = { password = it })
            if (sheet.moving) {
                RecoveryOrCodeField(
                    value = currentCode,
                    label = "Code from your current app",
                    enabled = !sheet.busy,
                    onChange = { currentCode = it },
                    onDone = ::submit,
                )
            }
            sheet.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BusyButton("Continue", busy = sheet.busy, enabled = ready, onClick = ::submit)
        }
        SetupPhase.Scan -> {
            val setup = sheet.setup ?: return
            var code by remember(setup) { mutableStateOf("") }
            var signOutOthers by remember { mutableStateOf(false) }
            fun submit() {
                scope.launch {
                    model.confirmSetup(code, signOutOthers)
                    code = ""
                }
            }
            Text(
                "Scan this with your authenticator app, or type in the key.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 16.dp),
            )
            TwoFactorSetupKey(setup)
            Text("Code from the app", style = MaterialTheme.typography.labelLarge)
            SixDigitField(value = code, enabled = !sheet.busy, onValueChange = { code = it }, onComplete = ::submit)
            if (!sheet.moving) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = signOutOthers,
                            enabled = !sheet.busy,
                            role = Role.Checkbox,
                            onValueChange = { signOutOthers = it },
                        ),
                ) {
                    Checkbox(checked = signOutOthers, onCheckedChange = null, enabled = !sheet.busy)
                    Text(TwoFactorSettingViewModel.SIGN_OUT_OTHERS, style = MaterialTheme.typography.bodyMedium)
                }
            }
            sheet.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BusyButton("Turn On", busy = sheet.busy, enabled = code.length == 6, onClick = ::submit)
        }
        SetupPhase.Codes -> {
            Text(
                TwoFactorSettingViewModel.setupDone(sheet.moving, sheet.signedOut),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            RecoveryCodesPanel(sheet.recoveryCodes, doneLabel = "Done", onDone = onDone)
        }
    }
}

@Composable
private fun ConfirmSheet(
    model: TwoFactorSettingViewModel,
    sheet: Sheet.Confirm,
    scope: CoroutineScope,
    onDone: () -> Unit,
) {
    sheet.recoveryCodes?.let { codes ->
        Text(
            "Your new recovery codes are ready.",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        RecoveryCodesPanel(codes, doneLabel = "Done", onDone = onDone)
        return
    }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val ready = password.isNotEmpty() && code.isNotBlank()
    fun submit() {
        if (!ready) return
        scope.launch {
            model.confirmAction(password, code)
            code = ""
        }
    }
    Text(
        TwoFactorSettingViewModel.confirmIntro(sheet.action),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 16.dp),
    )
    PasswordField(password, enabled = !sheet.busy, onChange = { password = it })
    RecoveryOrCodeField(
        value = code,
        label = "Code from your app (or a recovery code)",
        enabled = !sheet.busy,
        onChange = { code = it },
        onDone = ::submit,
    )
    sheet.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    BusyButton(
        TwoFactorSettingViewModel.confirmButton(sheet.action),
        busy = sheet.busy,
        enabled = ready,
        destructive = sheet.action == ConfirmAction.TurnOff,
        onClick = ::submit,
    )
}

@Composable
private fun PasswordField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Password") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

/// A 6-digit code or a recovery code, which has letters.
@Composable
private fun RecoveryOrCodeField(
    value: String,
    label: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 24) onChange(it) },
        label = { Text(label) },
        placeholder = { Text("123456 or recovery code") },
        singleLine = true,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BusyButton(
    label: String,
    busy: Boolean,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = !busy && enabled,
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.padding(4.dp)) else Text(label)
    }
}
