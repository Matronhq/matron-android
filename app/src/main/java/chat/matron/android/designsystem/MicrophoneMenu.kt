package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.Microphone

/// Names the microphone a voice note is recording from and lets the user
/// pick another. Sits under the recording bar while a note records; the pick
/// is remembered for later notes. The list is read when the menu opens, so a
/// headset connected mid-note shows up. Until the user picks, the microphone
/// in use is ticked (the phone's own microphone is in the list, so there is
/// no separate "default" row).
///
/// Values only: the composers bind it to `VoiceRecorder`.
@Composable
fun MicrophoneMenu(
    current: Microphone?,
    preferredID: String?,
    available: () -> List<Microphone>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    var options by remember { mutableStateOf(if (initiallyExpanded) available() else emptyList()) }
    val selection = microphoneMenuSelection(current, preferredID)
    val name = current?.name ?: "Microphone"
    Box(modifier) {
        TextButton(
            onClick = {
                options = available()
                expanded = true
            },
            modifier = Modifier.semantics { contentDescription = "Microphone: $name. Choose which microphone records" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(text = { Text("No other microphones") }, onClick = { expanded = false }, enabled = false)
            }
            options.forEach { mic ->
                DropdownMenuItem(
                    text = { Text(mic.name) },
                    leadingIcon = {
                        if (mic.id == selection) {
                            Icon(Icons.Default.Check, contentDescription = "Selected")
                        } else {
                            Box(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        if (mic.id != selection) onSelect(mic.id)
                    },
                )
            }
        }
    }
}

/// The ticked row: the user's pick, or the microphone in use until they pick.
fun microphoneMenuSelection(current: Microphone?, preferredID: String?): String? = preferredID ?: current?.id
