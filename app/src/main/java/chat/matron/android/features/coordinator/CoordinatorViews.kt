package chat.matron.android.features.coordinator

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.matron.android.chat.ChatSummary
import chat.matron.android.features.chatlist.ChatRow

/// What the Coordinator tab shows at its root (app shell, spec §3): the
/// setup view without a coordinator conversation, that chat with one.
sealed interface CoordinatorRoot {
    data object Setup : CoordinatorRoot
    data class Chat(val convoID: String) : CoordinatorRoot
}

/// The root rule, from the setting alone. An empty stored value is no
/// coordinator. Ported from matron-apple's `CoordinatorTabView.root(for:)`.
fun coordinatorRoot(convoID: String?): CoordinatorRoot =
    if (convoID.isNullOrEmpty()) CoordinatorRoot.Setup else CoordinatorRoot.Chat(convoID)

/// Title match, case-insensitive, whitespace-trimmed; an empty query keeps
/// every chat. Ported from matron-apple's `CoordinatorChooserSheet.filtered`.
fun coordinatorChoices(chats: List<ChatSummary>, query: String): List<ChatSummary> {
    val q = query.trim()
    if (q.isEmpty()) return chats
    return chats.filter { it.title.contains(q, ignoreCase = true) }
}

/// The Coordinator tab's root when no coordinator conversation is set: a
/// short explanation and the chooser button. The bottom bar stays visible
/// beneath it (the shell shows the bar at every tab root).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinatorSetupView(onChoose: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(topBar = { TopAppBar(title = { Text("Coordinator") }) }) { padding ->
        Box(modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Filled.SupervisorAccount, contentDescription = null,
                    modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Coordinator", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(
                    "Pick one conversation to act as your coordinator. It keeps its own tab; everything else about it stays the same.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onChoose) { Text("Choose a conversation…") }
            }
        }
    }
}

/// Picks the coordinator conversation (app shell, spec §5b): the user's
/// existing conversations (the chat-list rows, search box on top) plus a
/// "New coordinator chat…" row that runs the existing New Chat flow and
/// stores the resulting id. The shell hosts it as a bottom sheet and feeds
/// it the live chat list (Apple's sheet owns a `ChatListViewModel` because
/// its Settings screen has no list; here Settings and the shell share one).
@Composable
fun CoordinatorChooserSheet(
    chats: List<ChatSummary>,
    isLoading: Boolean,
    onPick: (String) -> Unit,
    onNewChat: () -> Unit,
    onCancel: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val choices = remember(chats, query) { coordinatorChoices(chats, query) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Coordinator", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search conversations") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text("New coordinator chat…") },
            leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onNewChat),
        )
        HorizontalDivider()
        Text(
            "Conversations", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        when {
            isLoading && chats.isEmpty() -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            choices.isEmpty() -> Text(
                "No conversations match.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            else -> LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(choices, key = { it.id }) { summary ->
                    ChatRow(summary = summary, onOpen = { onPick(summary.id) }, onMute = null, onLeave = null)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
