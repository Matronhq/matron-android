package chat.matron.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import chat.matron.android.journal.BoxDefaults
import chat.matron.android.journal.DeviceDTO
import chat.matron.android.viewmodels.BoxDefaultsChoices
import chat.matron.android.viewmodels.title

/**
 * Settings ▸ Devices ▸ New sessions for one agent box: Agent, Model and
 * Effort, each saved the moment it is picked (`PUT /devices/:id/defaults`,
 * optimistic in [chat.matron.android.viewmodels.DevicesViewModel]). [device]
 * is the live roster row, so a `box_defaults` frame from another device
 * redraws the picks while the dialog is open. Model choices follow the
 * agent: Claude aliases, or a typed Codex model id saved with its own button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxDefaultsDialog(
    device: DeviceDTO,
    onPick: (BoxDefaults.Key, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaults = device.defaults ?: BoxDefaults()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New sessions on ${device.name.ifEmpty { "this box" }}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    BoxDefaultsChoices.HELP_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChoiceGroup(BoxDefaults.Key.AGENT, BoxDefaultsChoices.agentChoices, defaults.agent, onPick)
                // With no agent picked the bridge applies no box model or
                // effort (both belong to the box's agent), so there is
                // nothing to choose yet.
                when (defaults.agent) {
                    null -> Text(
                        "Pick an agent to choose its model and effort.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    "codex" -> {
                        // Keyed on the stored model so a save or a live frame
                        // resets the draft to what the journal holds.
                        CodexModelField(defaults.model, onSave = { onPick(BoxDefaults.Key.MODEL, it) })
                        ChoiceGroup(BoxDefaults.Key.EFFORT, BoxDefaultsChoices.effortChoices(defaults), defaults.effort, onPick)
                    }
                    else -> {
                        ChoiceGroup(BoxDefaults.Key.MODEL, BoxDefaultsChoices.modelChoices(defaults), defaults.model, onPick)
                        ChoiceGroup(BoxDefaults.Key.EFFORT, BoxDefaultsChoices.effortChoices(defaults), defaults.effort, onPick)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceGroup(
    key: BoxDefaults.Key,
    choices: List<BoxDefaultsChoices.Choice>,
    selected: String?,
    onPick: (BoxDefaults.Key, String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(key.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice.value == selected,
                    onClick = { if (choice.value != selected) onPick(key, choice.value) },
                    label = { Text(choice.label) },
                )
            }
        }
    }
}

@Composable
private fun CodexModelField(stored: String?, onSave: (String?) -> Unit) {
    var draft by remember(stored) { mutableStateOf(stored ?: "") }
    val parsed = BoxDefaultsChoices.codexModel(draft)
    val valid = parsed as? BoxDefaultsChoices.ModelDraft.Valid
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(BoxDefaults.Key.MODEL.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Codex default") },
                singleLine = true,
                isError = valid == null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { valid?.let { onSave(it.model) } },
                enabled = valid != null && valid.model != stored,
            ) { Text("Save") }
        }
        Text(
            if (valid == null) "That isn't a model id the journal accepts." else BoxDefaultsChoices.CODEX_MODEL_HELP,
            style = MaterialTheme.typography.bodySmall,
            color = if (valid == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
