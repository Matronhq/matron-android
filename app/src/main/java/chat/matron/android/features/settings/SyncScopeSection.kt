package chat.matron.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.journal.SyncDayStats
import chat.matron.android.journal.SyncScope
import chat.matron.android.viewmodels.SyncScopeSettingModel

/// Settings › Sync on this device. "Everything" keeps a copy of every
/// conversation on the device; "For you only" keeps For you, missions and
/// the Coordinator current and fetches a conversation when it is opened,
/// which saves most of the device's sync work. Per device.
@Composable
internal fun SyncScopeSection(model: SyncScopeSettingModel) {
    val selected by model.selected.collectAsStateWithLifecycle()
    val days by model.days.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.refreshStats() }
    SyncScopeRows(selected = selected, onSelect = model::select, days = days)
}

/// Below the choice, today's and yesterday's sync counts, so the two
/// choices can be compared on a real device.
@Composable
internal fun SyncScopeRows(
    selected: SyncScope,
    onSelect: (SyncScope) -> Unit,
    days: Pair<SyncDayStats, SyncDayStats?>? = null,
) {
    SettingsSection(SYNC_SCOPE_SECTION_TITLE) {
        Text(
            "Keep up to date",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SyncScope.entries.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option.title) },
                    modifier = Modifier.testTag("syncScope.${option.wire}"),
                )
            }
        }
        days?.let { (today, yesterday) ->
            StatsRow("Today", today.summary())
            yesterday?.let { StatsRow("Yesterday", it.summary()) }
        }
        Text(
            selected.footer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

internal const val SYNC_SCOPE_SECTION_TITLE = "Sync on this device"
