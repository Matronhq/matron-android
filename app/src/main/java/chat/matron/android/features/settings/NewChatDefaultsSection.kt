package chat.matron.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.journal.NewChatDefaults
import chat.matron.android.viewmodels.NewChatDefaultsStore
import kotlinx.coroutines.launch

/// Settings → New chats: the user's default model and effort, which every
/// bridge applies to a chat started without a choice of its own (journal
/// `GET`/`PUT /defaults`). Synced per user, so a pick here shows on every
/// device at once. Every pick shows at once and goes back if the journal
/// refuses it ([NewChatDefaultsStore]). Ported from matron-apple's
/// `NewChatDefaultsSection`.
@Composable
internal fun NewChatDefaultsSection(store: NewChatDefaultsStore) {
    DisposableEffect(store) {
        store.start()
        onDispose { store.stop() }
    }
    val defaults by store.defaults.collectAsStateWithLifecycle()
    val isSupported by store.isSupported.collectAsStateWithLifecycle()
    val error by store.errorMessage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    SettingsSection("New chats") {
        if (isSupported == false) {
            Text(
                NewChatDefaultsStore.UNSUPPORTED_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            NewChatDefaultsRows(defaults, error, onPick = { key, value -> scope.launch { store.set(key, value) } })
        }
    }
}

@Composable
private fun NewChatDefaultsRows(defaults: NewChatDefaults?, error: String?, onPick: (NewChatDefaults.Key, String?) -> Unit) {
    for (key in NewChatDefaults.Key.entries) {
        DefaultRow(
            key = key,
            defaults = defaults,
            unavailable = error != null,
            onPick = { value -> onPick(key, value) },
        )
    }
    Text(
        NewChatDefaultsStore.HELP_TEXT,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/// A picker once the first read has answered; until then a placeholder that
/// says whether it is still loading or the read failed.
@Composable
private fun DefaultRow(
    key: NewChatDefaults.Key,
    defaults: NewChatDefaults?,
    unavailable: Boolean,
    onPick: (String?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(key.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (defaults == null) {
            Text(
                if (unavailable) "Unavailable" else "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            DefaultPicker(key, defaults, onPick)
        }
    }
}

@Composable
private fun DefaultPicker(key: NewChatDefaults.Key, defaults: NewChatDefaults, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = defaults[key]
    val choices = NewChatDefaultsStore.choices(key, current)
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("newChatDefaults.${key.wire}")) {
            Text(choices.firstOrNull { it.value == current }?.label ?: current ?: "Box default")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    trailingIcon = if (choice.value == current) ({ Icon(Icons.Filled.Check, contentDescription = "Selected") }) else null,
                    onClick = { open = false; onPick(choice.value) },
                )
            }
        }
    }
}
