package chat.matron.android.features.gotonumber

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.viewmodels.GoToNumberCopy
import chat.matron.android.viewmodels.GoToNumberResult
import chat.matron.android.viewmodels.GoToNumberViewModel
import kotlinx.coroutines.launch

/// The top-bar entry to [GoToNumberDialog], shared by the tab roots that
/// offer it so the icon and its description cannot drift between them.
@Composable
fun GoToNumberButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Outlined.Tag, contentDescription = GoToNumberCopy.TITLE) }
}

/// "Go to number": type `412`, see what carries that number, open it.
/// The number is looked up as it is typed, and the answer shows INLINE
/// under the field: a row to tap when something has the number, a quiet
/// line when nothing does, an error line only when the lookup itself failed.
/// All of the logic is [GoToNumberViewModel]'s; this only renders it.
///
/// Every lookup runs in this dialog's own scope: an edit cancels the one
/// before it, and dismissing cancels them all, so a late answer can never
/// show for other text or navigate after the user backed out.
@Composable
fun GoToNumberDialog(viewModel: GoToNumberViewModel, onDismiss: () -> Unit) {
    val text by viewModel.text.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val looking by viewModel.isLooking.collectAsStateWithLifecycle()
    val busy by viewModel.isBusy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val canGo = GoToNumberViewModel.canGo(text, result, busy)
    val go = {
        if (canGo) scope.launch { if (viewModel.go()) onDismiss() }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Keyed on `busy` too: Go resolves for itself, so a lookup still out
    // when it is pressed is cancelled rather than asked twice.
    LaunchedEffect(text, busy) { viewModel.lookUp() }
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
                    // Always present, drawn only while looking: the field's
                    // own layout never shifts as lookups start and finish.
                    trailingIcon = {
                        Box(modifier = Modifier.size(18.dp)) {
                            if (looking) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .semantics { contentDescription = GoToNumberCopy.LOOKING_UP },
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                // Room for a two-line row is held even when empty, so the
                // dialog does not grow and shrink with every answer.
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when (val shown = result) {
                        is GoToNumberResult.Hit -> ResultRow(shown, enabled = canGo, onClick = { go() })
                        is GoToNumberResult.Missing -> Text(
                            GoToNumberCopy.nothingNumbered(shown.asked),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                        is GoToNumberResult.Failed -> ErrorLine(shown.message)
                        null -> Unit
                    }
                    message?.let { ErrorLine(it) }
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

/// What the number is: kind and number, then its title. Tapping it is Go.
/// A project gets the row too — it IS what the number names — with a note
/// in place of the tap.
@Composable
private fun ResultRow(result: GoToNumberResult.Hit, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (result.canOpen) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Text(
            GoToNumberCopy.rowHeading(result.hit),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        result.hit.title?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!result.canOpen) {
            Text(
                GoToNumberCopy.PROJECT_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}
