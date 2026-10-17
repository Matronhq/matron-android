package chat.matron.android.features.chatlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.ChatService
import chat.matron.android.chat.ChatSummary
import chat.matron.android.models.MatronDebug
import kotlinx.coroutines.launch

/**
 * Archived (journal protocol "Archived conversations"): every archived
 * conversation, most recently active first, with a search field over titles
 * and previews. Tap opens one (reading it does not bring it back; a new
 * message does); long-press offers Unarchive. Ports the iPhone
 * `ArchivedChatsView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedChatsScreen(
    chat: ChatService,
    onOpenChat: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val archivedFlow = remember(chat) { chat.archivedChatSummaries() }
    val archived by archivedFlow.collectAsStateWithLifecycle(initialValue = null)
    var query by rememberSaveable { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archived") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search archived chats") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(SEARCH_FIELD_TAG),
            )
            val list = archived
            val shown = list?.let { filterArchived(it, query) }
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyMessage(
                    "No archived chats. Long-press a chat and choose Archive to move it here.",
                )
                shown.isNullOrEmpty() -> EmptyMessage("No archived chats match “${query.trim()}”.")
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { summary ->
                        ChatRow(
                            summary = summary,
                            onOpen = { onOpenChat(summary.id) },
                            onMute = null,
                            onArchive = null,
                            onUnarchive = {
                                scope.launch {
                                    runCatching { chat.setArchived(listOf(summary.id), archived = false) }
                                        .onFailure { MatronDebug.breadcrumb("ArchivedChatsScreen: unarchive failed: $it") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/// The archived chats whose title or preview contains every word of
/// [query], case-insensitively; all of them for a blank query.
internal fun filterArchived(chats: List<ChatSummary>, query: String): List<ChatSummary> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return chats
    return chats.filter { chat ->
        val haystack = "${chat.title}\n${chat.snippet}".lowercase()
        words.all { it in haystack }
    }
}

internal const val SEARCH_FIELD_TAG = "archived-search"
