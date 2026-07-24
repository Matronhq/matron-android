package chat.matron.android.features.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.chat.ChatSummary
import chat.matron.android.designsystem.SearchResultRow
import chat.matron.android.search.SearchHit
import chat.matron.android.viewmodels.SearchViewModel
import kotlinx.coroutines.launch

/**
 * Search screen. Ports Features/Search/SearchView.swift: two sections — Chats
 * (title/bot matches filtered in-memory) and Messages (FTS hits). Tapping a
 * result routes back through [onSelectChat] / [onSelectMessage].
 *
 * [SearchViewModel.query] is a plain var and [SearchViewModel.chatHits] a
 * computed getter off it, so the field mirrors the var and each edit both
 * launches `search()` (for the FTS `messageHits` flow) and recomposes to re-read
 * `chatHits`. [liveChats] is folded in so newly-created rooms stay searchable.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onSelectChat: (ChatSummary) -> Unit,
    onSelectMessage: (SearchHit) -> Unit,
    onBack: () -> Unit,
    liveChats: List<ChatSummary>,
) {
    val scope = rememberCoroutineScope()
    val messageHits by viewModel.messageHits.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchFailed by viewModel.searchFailed.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf(viewModel.query) }

    LaunchedEffect(liveChats) { viewModel.updateChats(liveChats) }

    // Re-read the computed getter under the current query + chats.
    val chatHits: List<ChatSummary> = viewModel.chatHits

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
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
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.query = it
                    scope.launch { viewModel.search() }
                },
                label = { Text("Search chats, bots, and messages") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (chatHits.isNotEmpty()) {
                    item { SectionHeader("Chats") }
                    items(chatHits, key = { "chat-${it.id}" }) { chat ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChat(chat) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(chat.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                chat.bot.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (messageHits.isNotEmpty()) {
                    item { SectionHeader("Messages") }
                    items(messageHits, key = { "msg-${it.id}" }) { hit ->
                        SearchResultRow(
                            hit = hit,
                            chatTitle = viewModel.chatTitle(forRoomID = hit.roomID),
                            onTap = { onSelectMessage(hit) },
                        )
                    }
                }

                if (query.isEmpty()) {
                    item {
                        HintRow("Search across chat titles, bots, and messages.")
                    }
                } else if (chatHits.isEmpty() && messageHits.isEmpty() && !isSearching) {
                    val hint = if (searchFailed) "Search failed. Try again." else viewModel.emptyResultsMessage
                    item { HintRow(hint) }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun HintRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
