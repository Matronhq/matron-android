package chat.matron.android.features.memories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.models.Memory
import chat.matron.android.models.MemoryType
import chat.matron.android.models.memoryAuthorLabel
import chat.matron.android.models.memoryTypeLabel
import chat.matron.android.viewmodels.MemoryEditorViewModel
import kotlinx.coroutines.launch

/// The memory editor (spec 2026-09-27 memories, "Apps"): one form for an
/// existing memory (name fixed; type, description and notes editable;
/// delete behind a confirm) and for a new one (name editable). The draft
/// lives here; the writes and their errors on the view model. A save sends
/// the whole memory — `PUT` overwrites — and pops back on success.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryEditorScreen(
    viewModel: MemoryEditorViewModel,
    onBack: () -> Unit,
) {
    val existing by viewModel.existing.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val isNew = viewModel.isNew

    DisposableEffect(viewModel) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    // Seeded once from the stored row; a later marker refetch must not
    // overwrite what the user is typing.
    var seeded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(viewModel.name ?: "") }
    var type by remember { mutableStateOf(MemoryType.FEEDBACK) }
    var description by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var confirmingDelete by remember { mutableStateOf(false) }
    existing?.let { stored ->
        if (!seeded) {
            seeded = true
            type = stored.type; description = stored.description; body = stored.body
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New memory" else viewModel.name ?: "Memory") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            MatronTimelineBackground()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                existing?.let { stored ->
                    Text(
                        "Saved ${memoryAuthorLabel(stored.createdBy).let { if (it == "you") "by you" else "by an agent" }}, " +
                            "last updated by ${memoryAuthorLabel(stored.updatedBy)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isNew) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        placeholder = { Text("avoid-eric-and-fatima") },
                        singleLine = true,
                        enabled = !isBusy,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MemoryType.entries.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { if (!isBusy) type = option },
                            label = { Text(memoryTypeLabel(option)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= Memory.DESCRIPTION_MAX) description = it.replace('\n', ' ') },
                    label = { Text("Description") },
                    placeholder = { Text("The rule, in one line — this is what the Coordinator reads") },
                    singleLine = true,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Notes") },
                    placeholder = { Text("Why, and how to apply it (markdown)") },
                    minLines = 4,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !isBusy,
                        onClick = {
                            coroutineScope.launch {
                                if (viewModel.save(name, description, body, type)) onBack()
                            }
                        },
                    ) { Text(if (isBusy) "Saving…" else "Save") }
                    if (isBusy) CircularProgressIndicator(Modifier.padding(4.dp))
                    if (!isNew) {
                        TextButton(enabled = !isBusy, onClick = { confirmingDelete = true }) {
                            Text("Delete memory", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this memory?") },
            text = { Text("Every agent stops seeing it. To change a rule instead, edit it and save.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        coroutineScope.launch { if (viewModel.delete()) onBack() }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}
