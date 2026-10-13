package chat.matron.android.features.gotonumber

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.viewmodels.GoToNumberCopy
import chat.matron.android.viewmodels.GoToNumberViewModel
import kotlinx.coroutines.launch

/// The top-bar entry to [GoToNumberDialog], shared by the tab roots that
/// offer it so the icon and its description cannot drift between them.
@Composable
fun GoToNumberButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Outlined.Tag, contentDescription = GoToNumberCopy.TITLE) }
}

/// "Go to number": type `412`, land on whatever carries that number.
/// Modelled on the pin editor's dialog — the outcome that is not a
/// navigation ("No #412", a project, a failed lookup) shows INLINE under
/// the field, so correcting a typo is one edit, not an alert to dismiss
/// first. All of the logic is [GoToNumberViewModel]'s; this only renders it.
///
/// The lookup runs in this dialog's own scope: dismissing mid-lookup cancels
/// it, so a late answer can never navigate after the user backed out.
@Composable
fun GoToNumberDialog(viewModel: GoToNumberViewModel, onDismiss: () -> Unit) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val canGo = !busy && text.isNotBlank()
    val go = {
        if (canGo) scope.launch { if (viewModel.go()) onDismiss() }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(GoToNumberCopy.TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { viewModel.setText(it.replace("\n", "")) },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(GoToNumberCopy.FIELD_LABEL) },
                    prefix = { Text("#") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canGo, onClick = { go() }) {
                Text(if (busy) GoToNumberCopy.GOING else GoToNumberCopy.GO)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(GoToNumberCopy.CANCEL) } },
    )
}
