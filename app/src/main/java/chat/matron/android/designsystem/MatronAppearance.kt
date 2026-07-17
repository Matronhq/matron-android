package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/// The in-app appearance override: follow the system, or force light/dark.
/// Persisted as its raw value under [STORAGE_KEY]; the app root reads it to
/// drive [MatronTheme] and the Device settings form writes it, so the two stay
/// in sync through the preference store.
enum class MatronAppearance(val rawValue: String, val title: String) {
    System("system", "System"),
    Light("light", "Light"),
    Dark("dark", "Dark");

    companion object {
        const val STORAGE_KEY = "MatronAppearance"

        /// Decodes a stored raw value, tolerating an unset or stale default.
        fun fromStored(value: String?): MatronAppearance =
            entries.firstOrNull { it.rawValue == value } ?: System
    }
}

/// The shared settings-form picker — one segmented row, identical across
/// surfaces. `selected`/`onSelect` are hoisted so the host owns persistence.
@Composable
fun AppearancePicker(
    selected: MatronAppearance,
    onSelect: (MatronAppearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier) {
        MatronAppearance.entries.forEach { appearance ->
            FilterChip(
                selected = appearance == selected,
                onClick = { onSelect(appearance) },
                label = { Text(appearance.title) },
                modifier = Modifier.selectable(selected = appearance == selected) {
                    onSelect(appearance)
                },
            )
        }
    }
}
