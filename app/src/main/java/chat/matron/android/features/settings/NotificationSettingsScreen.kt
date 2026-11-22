package chat.matron.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.journal.NotifyChange
import chat.matron.android.journal.NotifyDeviceLevel
import chat.matron.android.journal.NotifySettings
import chat.matron.android.viewmodels.NotifySettingsStore
import chat.matron.android.viewmodels.NotifySettingsTitles

/**
 * Settings → Notifications: what may push to the user's devices. The mode,
 * the event switches and the conversation overrides are synced per user, so
 * a change here shows on the user's other devices at once; "On this device"
 * is this device's alone. Every change shows at once and is taken back if
 * the journal refuses it ([NotifySettingsStore]). Port of matron-apple's
 * `NotificationSettingsView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    store: NotifySettingsStore,
    /// An overrides row's title, from the local conversation list.
    title: (String) -> String,
    onBack: () -> Unit,
) {
    val view by store.view.collectAsStateWithLifecycle()
    val supported by store.isSupported.collectAsStateWithLifecycle()
    val error by store.error.collectAsStateWithLifecycle()
    val now by store.clock.collectAsStateWithLifecycle()
    // On screen entry as well as on every connect: a frame missed while the
    // socket was down is caught here.
    LaunchedEffect(store) { store.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            val current = view
            when {
                supported == false -> Text(
                    NotifySettingsTitles.UNSUPPORTED,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                current == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                else -> {
                    ModeSection(current.settings) { store.post(NotifyChange.SetMode(it)) }
                    EventsSection(current.settings) { event, on -> store.post(NotifyChange.SetEvent(event, on)) }
                    DeviceSection(current.deviceLevel) { store.post(NotifyChange.SetDeviceLevel(it)) }
                    val overrides = store.activeOverrides(current, now)
                    if (overrides.isNotEmpty()) {
                        SettingsSection("Conversation overrides") {
                            overrides.forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(title(row.convoID), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            NotifySettingsTitles.summary(store.state(row.convoID, current, now), now),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { store.post(NotifyChange.ClearConvo(row.convoID)) }) {
                                        Text("Clear")
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        NotifySettingsTitles.FOOTER,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ModeSection(settings: NotifySettings, onSelect: (NotifySettings.Mode) -> Unit) {
    SettingsSection("Notify me about") {
        NotifySettings.Mode.entries.forEach { mode ->
            RadioRow(
                selected = settings.mode == mode,
                title = NotifySettingsTitles.title(mode),
                subtitle = NotifySettingsTitles.subtitle(mode),
                onClick = { if (settings.mode != mode) onSelect(mode) },
                tag = "notify-mode-${mode.wire}",
            )
        }
        if (settings.mode == NotifySettings.Mode.COORDINATOR && !settings.hasCoordinator) {
            Footnote(NotifySettingsTitles.NO_COORDINATOR)
        }
    }
}

/// Always editable: switching one while on a preset moves to Custom,
/// starting from that preset's switches (the journal's rule).
@Composable
private fun EventsSection(settings: NotifySettings, onToggle: (NotifySettings.Event, Boolean) -> Unit) {
    SettingsSection("Events") {
        NotifySettings.Event.entries.forEach { event ->
            val locked = event == NotifySettings.Event.PROMPTS
            val on = settings.isOn(event)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = on, enabled = !locked, role = Role.Switch) { onToggle(event, it) }
                    .padding(vertical = 6.dp)
                    .testTag("notify-event-${event.wire}")
                    .then(if (locked) Modifier.semantics { stateDescription = NotifySettingsTitles.PROMPTS_ALWAYS_ON } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (locked) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(NotifySettingsTitles.title(event), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                // The row is the toggle; the switch only draws it.
                Switch(checked = on, onCheckedChange = null, enabled = !locked)
            }
        }
        if (settings.mode != NotifySettings.Mode.CUSTOM) Footnote(NotifySettingsTitles.SWITCH_MOVES_TO_CUSTOM)
    }
}

@Composable
private fun DeviceSection(level: NotifyDeviceLevel, onSelect: (NotifyDeviceLevel) -> Unit) {
    SettingsSection("On this device") {
        NotifyDeviceLevel.entries.forEach { option ->
            RadioRow(
                selected = level == option,
                title = NotifySettingsTitles.title(option),
                subtitle = null,
                onClick = { if (level != option) onSelect(option) },
                tag = "notify-device-${option.wire}",
            )
        }
        Footnote(NotifySettingsTitles.ANDROID_PUSH_NOTE)
    }
}

@Composable
private fun RadioRow(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(tag)
            .semantics(mergeDescendants = true) { contentDescription = subtitle?.let { "$title, $it" } ?: title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
