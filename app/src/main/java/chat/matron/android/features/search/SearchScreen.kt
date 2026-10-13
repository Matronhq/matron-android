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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.luminance
import chat.matron.android.chat.ChatSummary
import chat.matron.android.designsystem.SearchResultRow
import chat.matron.android.designsystem.SessionTagText
import chat.matron.android.search.SearchChatHit
import chat.matron.android.search.SearchHit
import chat.matron.android.viewmodels.GoToNumberCopy
import chat.matron.android.viewmodels.NumberHit
import chat.matron.android.viewmodels.SearchViewModel
import kotlinx.coroutines.launch

/**
 * Search screen. Ports Features/Search/SearchView.swift: two sections — Chats
 * (title/bot matches filtered in-memory) and Messages (FTS hits). Tapping a
 * result routes back through [onSelectChat] / [onSelectMessage]. A query that
 * is a bare number (`412`, `#412`) adds the thing with that number above both.
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
    onSelectMessage: (SearchChatHit) -> Unit,
    /// Opens the number hit. Resolves to why it could not open, in words,
    /// shown under the row; `null` once it has navigated.
    onSelectNumber: suspend (NumberHit) -> String? = { null },
    onBack: () -> Unit,
    liveChats: List<ChatSummary>,
) {
    val scope = rememberCoroutineScope()
    val messageHits by viewModel.messageHits.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchFailed by viewModel.searchFailed.collectAsStateWithLifecycle()
    val numberHit by viewModel.numberHit.collectAsStateWithLifecycle()
    // Why the number hit did not open, keyed to the hit so it leaves with it.
    var numberNote by remember(numberHit) { mutableStateOf<String?>(null) }
    var numberOpening by remember(numberHit) { mutableStateOf(false) }

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
                // First: a query that IS a number most likely means "take me
                // to #N", so that answer sits above any chat or message that
                // merely contains the digits.
                numberHit?.let { hit ->
                    item(key = "number-${hit.num}") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !numberOpening) {
                                    numberOpening = true
                                    numberNote = null
                                    scope.launch {
                                        try {
                                            numberNote = onSelectNumber(hit)
                                        } finally {
                                            numberOpening = false
                                        }
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(GoToNumberCopy.hitHeading(hit), style = MaterialTheme.typography.bodyLarge)
                            hit.title?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            numberNote?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                if (chatHits.isNotEmpty()) {
                    item { SectionHeader("Chats") }
                    items(chatHits, key = { "chat-${it.id}" }) { chat ->
                        // Search rows carry the same colored `A:bc` tag as
                        // the chat list (apple #154), resolved by the VM so
                        // every call site composes identically.
                        val line = viewModel.hitTitle(chat.id)
                        val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectChat(chat) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                SessionTagText.titleLine(
                                    title = line.title,
                                    boxLetter = line.boxLetter,
                                    boxName = line.boxName,
                                    sessionShort = line.sessionShort,
                                    roomBoxNames = line.roomBoxNames,
                                    roomBoxShorts = line.roomBoxShorts,
                                    darkTheme = darkTheme,
                                    secondary = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
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
                    items(messageHits, key = { "msg-${it.roomID}" }) { hit ->
                        val line = viewModel.hitTitle(hit.roomID)
                        SearchResultRow(
                            hit = hit.newestHit,
                            matchCount = hit.count,
                            chatTitle = line.title,
                            sessionShort = line.sessionShort,
                            boxLetter = line.boxLetter,
                            boxName = line.boxName,
                            roomBoxNames = line.roomBoxNames,
                            roomBoxShorts = line.roomBoxShorts,
                            onTap = { onSelectMessage(hit) },
                        )
                    }
                }

                if (query.isEmpty()) {
                    item {
                        HintRow("Search across chat titles, bots, and messages.")
                    }
                } else if (numberHit == null && chatHits.isEmpty() && messageHits.isEmpty() && !isSearching) {
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
