package chat.matron.android.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.viewmodels.ChangePasswordViewModel
import kotlinx.coroutines.launch

/**
 * Settings → Security's "Change password", as in Matron Web: a row that opens
 * a small inline form, closed again on success with "Password changed." (and
 * how many other devices were signed out) under the row.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ChangePasswordRows(model: ChangePasswordViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    // The form's work runs here: the request must not die with the form when
    // it closes on success.
    val scope = rememberCoroutineScope()
    // Passwords never outlive the screen.
    DisposableEffect(model) { onDispose { model.cancel() } }

    if (!state.isOpen) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = model::open)
                .padding(vertical = 12.dp)
                .testTag(CHANGE_PASSWORD_ROW_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ChangePasswordViewModel.TITLE, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        state.confirmation?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(ChangePasswordViewModel.TITLE, style = MaterialTheme.typography.titleSmall)
        PasswordField(
            value = state.currentPassword,
            onValueChange = model::setCurrentPassword,
            label = ChangePasswordViewModel.CURRENT,
            autofillType = AutofillType.Password,
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Next,
        )
        PasswordField(
            value = state.newPassword,
            onValueChange = model::setNewPassword,
            label = ChangePasswordViewModel.NEW,
            autofillType = AutofillType.NewPassword,
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Next,
        )
        PasswordField(
            value = state.confirmPassword,
            onValueChange = model::setConfirmPassword,
            label = ChangePasswordViewModel.CONFIRM,
            autofillType = AutofillType.NewPassword,
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Done,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.signOutOthers,
                    enabled = !state.isSubmitting,
                    role = Role.Switch,
                    onValueChange = model::setSignOutOthers,
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ChangePasswordViewModel.SIGN_OUT_OTHERS, style = MaterialTheme.typography.bodyMedium)
                Text(
                    ChangePasswordViewModel.SIGN_OUT_OTHERS_HELP,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The row toggles; the switch only shows the value.
            Switch(checked = state.signOutOthers, onCheckedChange = null, enabled = !state.isSubmitting)
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = model::cancel, enabled = !state.isSubmitting) {
                Text(ChangePasswordViewModel.CANCEL)
            }
            Button(
                onClick = { scope.launch { model.submit() } },
                enabled = state.canSubmit,
                modifier = Modifier.testTag(CHANGE_PASSWORD_SUBMIT_TAG),
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(ChangePasswordViewModel.SUBMIT)
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    autofillType: AutofillType,
    enabled: Boolean,
    imeAction: ImeAction,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        modifier = Modifier
            .fillMaxWidth()
            .passwordAutofill(autofillType, onValueChange),
    )
}

/// Offers the field to autofill services as the current password or as a new
/// one, so a password manager fills the first and offers to save the others.
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.passwordAutofill(type: AutofillType, onFill: (String) -> Unit): Modifier = composed {
    val autofill = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val node = remember(type) { AutofillNode(autofillTypes = listOf(type), onFill = onFill) }
    DisposableEffect(node) {
        tree += node
        onDispose { autofill?.cancelAutofillForNode(node) }
    }
    onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { focus ->
            if (focus.isFocused) autofill?.requestAutofillForNode(node) else autofill?.cancelAutofillForNode(node)
        }
}

internal const val CHANGE_PASSWORD_ROW_TAG = "change-password-row"
internal const val CHANGE_PASSWORD_SUBMIT_TAG = "change-password-submit"
