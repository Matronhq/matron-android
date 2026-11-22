package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.matron.android.models.CronWords
import chat.matron.android.models.Routine
import chat.matron.android.models.RoutineDraft
import chat.matron.android.models.RoutineRules
import chat.matron.android.models.RoutineTrigger
import kotlinx.coroutines.launch

/// What the routine editor holds while the user types. A new routine
/// chooses schedule or trigger once: the journal keeps a routine that kind
/// for life, so an existing one edits only its own kind's fields. Ported
/// from matron-apple's `RoutineEditorView.Draft`.
data class RoutineEditorDraft(
    val name: String = "",
    val title: String = "",
    val prompt: String = "",
    val tz: String = RoutineRules.DEFAULT_ZONE,
    val mode: Mode = Mode.SCHEDULE,
    /// minute, hour, day of month, month, day of week.
    val cron: List<String> = DEFAULT_CRON,
    val triggerKind: RoutineTrigger.Kind = RoutineTrigger.Kind.CONTEXT_OVER,
    val threshold: Int = 40,
    /// A trigger the editor shows but cannot change (the tiered spare
    /// allowance trigger): it rides back unchanged.
    val fixedTrigger: RoutineTrigger? = null,
    /// An `allowance_expiring` routine's reset window, as the journal has
    /// it. The editor changes only the percent, so this rides back.
    val allowanceFromHours: Double? = null,
    val allowanceToHours: Double? = null,
    val enabled: Boolean = true,
) {
    enum class Mode(val label: String) { SCHEDULE("On a schedule"), TRIGGER("When something happens") }

    val schedule: String get() = cron.joinToString(" ") { it.trim() }

    val trigger: RoutineTrigger
        get() = fixedTrigger ?: if (triggerKind == RoutineTrigger.Kind.ALLOWANCE_EXPIRING) {
            RoutineTrigger.AllowanceExpiring(threshold, allowanceFromHours, allowanceToHours)
        } else {
            RoutineTrigger.make(triggerKind, threshold)
        }

    /// What saving sends. `enabled` rides only on a create: on an edit the
    /// list's switch owns it.
    fun routineDraft(isNew: Boolean): RoutineDraft = RoutineDraft(
        title = title, prompt = prompt, tz = tz,
        schedule = if (mode == Mode.SCHEDULE) schedule else null,
        trigger = if (mode == Mode.TRIGGER) trigger else null,
        enabled = if (isNew) enabled else null,
    )

    /// The plain-words preview under the cron fields.
    val schedulePreview: String
        get() = RoutineRules.scheduleError(schedule) ?: CronWords.describe(schedule) ?: "Runs on the cron schedule $schedule."

    /// The kind picker changed: a new kind starts from its default threshold.
    fun withKind(kind: RoutineTrigger.Kind): RoutineEditorDraft =
        if (kind == triggerKind) this else copy(triggerKind = kind, threshold = kind.defaultTrigger.threshold ?: 0)

    /// One stepper tap: 30 minutes for a stall, 5 points for a percentage,
    /// kept inside the journal's range.
    fun stepped(up: Boolean): RoutineEditorDraft {
        val step = if (triggerKind == RoutineTrigger.Kind.STALLED) 30 else 5
        val range = triggerKind.thresholdRange
        return copy(threshold = (threshold + if (up) step else -step).coerceIn(range.first, range.last))
    }

    companion object {
        val DEFAULT_CRON = listOf("0", "9", "*", "*", "1-5")

        fun from(routine: Routine): RoutineEditorDraft {
            val cron = routine.schedule?.trim()?.split(Regex("\\s+"))?.takeIf { it.size == 5 } ?: DEFAULT_CRON
            val trigger = routine.trigger ?: RoutineTrigger.Kind.CONTEXT_OVER.defaultTrigger
            val allowance = trigger as? RoutineTrigger.AllowanceExpiring
            return RoutineEditorDraft(
                name = routine.name, title = routine.title, prompt = routine.prompt, tz = routine.tz,
                mode = if (routine.isTriggered) Mode.TRIGGER else Mode.SCHEDULE,
                cron = cron, triggerKind = trigger.kind, threshold = trigger.threshold ?: 0,
                fixedTrigger = trigger.takeIf { !it.isEditable },
                allowanceFromHours = allowance?.fromHours, allowanceToHours = allowance?.toHours,
                enabled = routine.enabled,
            )
        }

        val CRON_LABELS = listOf("Minute", "Hour", "Day", "Month", "Weekday")

        fun thresholdLabel(trigger: RoutineTrigger): String = when (trigger) {
            is RoutineTrigger.ContextOver -> "${trigger.pct}% of the context window"
            is RoutineTrigger.DiskUnder -> "${trigger.pct}% free disk"
            is RoutineTrigger.AllowanceExpiring -> "Weekly limit under ${trigger.pct}%"
            is RoutineTrigger.Stalled ->
                if (trigger.resetMinutes == 0) "Any reset time" else "No reset within ${RoutineTrigger.duration(trigger.resetMinutes)}"
            is RoutineTrigger.AllowanceTiers -> trigger.words
        }
    }
}

/// One routine's editor, or the new-routine form. A pure leaf view:
/// [onSave] validates and writes (it is `RoutinesViewModel.save`),
/// answering `null` on success or the reason it refused, which the form
/// shows without clearing what was typed.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditorView(
    routine: Routine?,
    onSave: suspend (RoutineEditorDraft) -> String?,
    onDelete: suspend () -> String?,
    onRun: (suspend () -> String?)?,
    modifier: Modifier = Modifier,
    initialDraft: RoutineEditorDraft? = null,
) {
    val isNew = routine == null
    var draft by remember(routine?.id) { mutableStateOf(initialDraft ?: routine?.let(RoutineEditorDraft::from) ?: RoutineEditorDraft()) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isNew) {
            EditorSection("Name", footer = "Lowercase letters, digits and dashes. It can't be changed later.") {
                OutlinedTextField(
                    value = draft.name, onValueChange = { draft = draft.copy(name = it) },
                    singleLine = true, enabled = !busy,
                    placeholder = { Text("weekly-review") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().testTag("routine.name"),
                )
            }
        } else {
            Text(routine!!.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        EditorSection("Title") {
            OutlinedTextField(
                value = draft.title, onValueChange = { draft = draft.copy(title = it) },
                singleLine = true, enabled = !busy,
                placeholder = { Text("Weekly review") },
                modifier = Modifier.fillMaxWidth().testTag("routine.title"),
            )
        }
        EditorSection("Prompt", footer = "${draft.prompt.trim().length}/${RoutineRules.PROMPT_MAX}") {
            OutlinedTextField(
                value = draft.prompt, onValueChange = { draft = draft.copy(prompt = it) },
                enabled = !busy, minLines = 4,
                placeholder = { Text("What the Coordinator should do when this fires") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).testTag("routine.prompt"),
            )
        }
        if (isNew) {
            EditorSection("Runs", footer = "A routine stays scheduled or triggered: this can't be changed later.") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("routine.mode")) {
                    RoutineEditorDraft.Mode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = draft.mode == mode,
                            onClick = { draft = draft.copy(mode = mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, RoutineEditorDraft.Mode.entries.size),
                            enabled = !busy,
                        ) { Text(mode.label, maxLines = 1) }
                    }
                }
            }
        }
        when (draft.mode) {
            RoutineEditorDraft.Mode.SCHEDULE -> ScheduleSection(draft, busy) { draft = it }
            RoutineEditorDraft.Mode.TRIGGER -> TriggerSection(draft, busy) { draft = it }
        }
        if (isNew) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Active", modifier = Modifier.weight(1f))
                Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) }, enabled = !busy)
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("routine.error"))
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                if (busy) return@Button
                busy = true; note = null
                scope.launch {
                    error = onSave(draft)
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("routine.save"),
        ) { Text(if (busy) "Saving…" else "Save") }
        if (onRun != null && !isNew) {
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        note = onRun() ?: "Sent to the Coordinator."
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("routine.run"),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("Run now", modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (!isNew) {
            TextButton(
                onClick = { confirmingDelete = true }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("routine.delete"),
            ) { Text("Delete routine", color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this routine?") },
            text = { Text("The Coordinator stops receiving it. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    busy = true
                    scope.launch {
                        error = onDelete()
                        busy = false
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ScheduleSection(draft: RoutineEditorDraft, busy: Boolean, onChange: (RoutineEditorDraft) -> Unit) {
    EditorSection(
        "Schedule",
        footer = "Five cron fields, in the time zone below. It may fire at most every ${RoutineRules.MIN_GAP_MINUTES} minutes.",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (index in 0 until 5) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    OutlinedTextField(
                        value = draft.cron[index],
                        onValueChange = { value -> onChange(draft.copy(cron = draft.cron.toMutableList().also { it[index] = value })) },
                        singleLine = true, enabled = !busy,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth().testTag("routine.cron.$index"),
                    )
                    Text(RoutineEditorDraft.CRON_LABELS[index], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(draft.schedulePreview, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("routine.schedulePreview"))
        OutlinedTextField(
            value = draft.tz, onValueChange = { onChange(draft.copy(tz = it)) },
            singleLine = true, enabled = !busy,
            label = { Text("Time zone") },
            placeholder = { Text(RoutineRules.DEFAULT_ZONE) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("routine.tz"),
        )
    }
}

@Composable
private fun TriggerSection(draft: RoutineEditorDraft, busy: Boolean, onChange: (RoutineEditorDraft) -> Unit) {
    EditorSection(
        "Trigger",
        footer = "It fires once for each session or box that crosses the line, at most every ${RoutineRules.MIN_GAP_MINUTES} minutes.",
    ) {
        val fixed = draft.fixedTrigger
        if (fixed != null) {
            Text(fixed.words, style = MaterialTheme.typography.bodyMedium)
            Text(
                "This trigger has several tiers. Ask your Coordinator to change them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            EditableTrigger(draft, busy, onChange)
        }
    }
}

@Composable
private fun EditableTrigger(draft: RoutineEditorDraft, busy: Boolean, onChange: (RoutineEditorDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var kindMenu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { kindMenu = true }, enabled = !busy, modifier = Modifier.testTag("routine.triggerKind")) {
                Text(draft.triggerKind.label)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                RoutineTrigger.Kind.entries.forEach { kind ->
                    DropdownMenuItem(text = { Text(kind.label) }, onClick = { kindMenu = false; onChange(draft.withKind(kind)) })
                }
            }
        }
        val range = draft.triggerKind.thresholdRange
        Row(Modifier.fillMaxWidth().testTag("routine.threshold"), verticalAlignment = Alignment.CenterVertically) {
            Text(RoutineEditorDraft.thresholdLabel(draft.trigger), modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange(draft.stepped(up = false)) }, enabled = !busy && draft.threshold > range.first) {
                Icon(Icons.Filled.Remove, contentDescription = "Lower")
            }
            IconButton(onClick = { onChange(draft.stepped(up = true)) }, enabled = !busy && draft.threshold < range.last) {
                Icon(Icons.Filled.Add, contentDescription = "Raise")
            }
        }
        Text(draft.trigger.words, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EditorSection(title: String, footer: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        content()
        footer?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
