package chat.matron.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.viewmodels.AccountEmailViewModel
import kotlinx.coroutines.launch

/**
 * Settings → Security's "Email address", as in Matron Web: the address (or
 * "Not set") with why it matters, and Edit, which opens a small inline form —
 * the address and the current password, Save, and Remove when one is set.
 * Hidden until `GET /account/email` answers, and for good on a journal that
 * 404s it.
 */
@Composable
internal fun AccountEmailRows(model: AccountEmailViewModel) {
    LaunchedEffect(model) { model.load() }
    val state by model.state.collectAsStateWithLifecycle()
    val form by model.form.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The password never outlives the screen.
    DisposableEffect(model) { onDispose { model.cancel() } }

    when (val s = state) {
        is AccountEmailViewModel.State.Failed -> {
            Text(
                s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            return
        }
        !is AccountEmailViewModel.State.Loaded -> return
        else -> Unit
    }
    val account = (state as AccountEmailViewModel.State.Loaded).account

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(AccountEmailViewModel.TITLE, style = MaterialTheme.typography.bodyMedium)
            Text(
                account.email ?: AccountEmailViewModel.NOT_SET,
                style = MaterialTheme.typography.bodyMedium,
                color = if (account.email != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                AccountEmailViewModel.helpText(account),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (form == null) {
            TextButton(onClick = model::edit, modifier = Modifier.testTag(ACCOUNT_EMAIL_EDIT_TAG)) {
                Text(AccountEmailViewModel.EDIT)
            }
        }
    }

    val f = form ?: return
    Column(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = f.email,
            onValueChange = model::setEmail,
            label = { Text(AccountEmailViewModel.FIELD) },
            singleLine = true,
            enabled = !f.isSaving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = f.password,
            onValueChange = model::setPassword,
            label = { Text(AccountEmailViewModel.PASSWORD) },
            singleLine = true,
            enabled = !f.isSaving,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        f.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (account.email != null) {
                TextButton(
                    onClick = { scope.launch { model.remove() } },
                    enabled = f.canRemove,
                    modifier = Modifier.testTag(ACCOUNT_EMAIL_REMOVE_TAG),
                ) {
                    Text(
                        AccountEmailViewModel.REMOVE,
                        color = if (f.canRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = model::cancel, enabled = !f.isSaving) { Text(AccountEmailViewModel.CANCEL) }
            Button(
                onClick = { scope.launch { model.save() } },
                enabled = f.canSave,
                modifier = Modifier.testTag(ACCOUNT_EMAIL_SAVE_TAG),
            ) {
                if (f.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(AccountEmailViewModel.SAVE)
                }
            }
        }
    }
}

internal const val ACCOUNT_EMAIL_EDIT_TAG = "account-email-edit"
internal const val ACCOUNT_EMAIL_SAVE_TAG = "account-email-save"
internal const val ACCOUNT_EMAIL_REMOVE_TAG = "account-email-remove"
