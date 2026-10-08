package chat.matron.android.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.ChatSummary
import chat.matron.android.features.pins.PinActions
import chat.matron.android.features.pins.PinGlyph
import chat.matron.android.features.pins.PinMenuItems
import chat.matron.android.features.pins.pinnedEntries
import chat.matron.android.journal.Pins
import chat.matron.android.journal.PinsSyncing

/**
 * Settings → Pinned chats (journal "Pinned desk chats"): every pin with
 * its glyph, label and conversation, reorder by Move up / Move down, a
 * menu to rename (label + emoji), move or unpin, and "Add a pinned chat…"
 * through the Coordinator-style chooser. The shell's controller owns the
 * sheets; the journal's refusals (the 409s) surface in its alert or the
 * sheet that asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinnedChatsScreen(
    sync: PinsSyncing,
    chats: List<ChatSummary>,
    actions: PinActions,
    onBack: () -> Unit,
) {
    val pins by sync.pins.collectAsStateWithLifecycle()
    val limit by sync.limit.collectAsStateWithLifecycle()
    // The limit rides only on `/pins` answers, never the hello.
    LaunchedEffect(sync) { sync.refresh() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pinned chats") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val list = pins
            if (list == null) {
                Text(
                    "This server doesn't support pinned chats yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            Text(
                "Pinned chats sit at the top of Conversations on every device. You can pin up to $limit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            val entries = pinnedEntries(list, chats)
            if (entries.isEmpty()) {
                Text("No pinned chats yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
            }
            entries.forEachIndexed { index, entry ->
                val pin = entry.pin
                var menuOpen by remember(pin.convoID) { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PinGlyph(pin, modifier = if (entry.isMissing) Modifier.alpha(0.5f) else Modifier)
                    Column(Modifier.weight(1f).then(if (entry.isMissing) Modifier.alpha(0.5f) else Modifier)) {
                        Text(pin.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            entry.summary?.title ?: "Conversation unavailable",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { actions.shift(pin.convoID, Pins.Direction.UP) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(onClick = { actions.shift(pin.convoID, Pins.Direction.DOWN) }, enabled = index < entries.size - 1) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Pin options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            // Reorder lives on the arrows here; the menu keeps
                            // the rest (a missing pin: Move pin… and Unpin only).
                            PinMenuItems(pin.convoID, missing = entry.isMissing, index = 0, count = 1, actions = actions) { menuOpen = false }
                        }
                    }
                }
            }
            HorizontalDivider()
            val full = list.size >= limit
            Button(onClick = actions::add, enabled = !full) { Text("Add a pinned chat…") }
            if (full) {
                Text(
                    "You can pin up to $limit chats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
