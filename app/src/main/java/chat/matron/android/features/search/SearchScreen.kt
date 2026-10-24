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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import chat.matron.android.chat.ChatSummary
import chat.matron.android.journal.TrackerSearchDecoding
import chat.matron.android.journal.TrackerSearchHit
import chat.matron.android.journal.TrackerSearchKind
import chat.matron.android.designsystem.SearchResultRow
import chat.matron.android.designsystem.SessionTagText
import chat.matron.android.search.SearchChatHit
import chat.matron.android.search.SearchHit
import chat.matron.android.viewmodels.GoToNumberParser
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
    /// `null` on the Search tab's root, which has nowhere to go back to.
    onBack: (() -> Unit)?,
    liveChats: List<ChatSummary>,
    /// Opens a tracker hit (the Search tab); unused where the view model
    /// has no tracker search.
    onSelectTracker: (TrackerSearchHit) -> Unit = {},
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
    val trackerResults by viewModel.trackerResults.collectAsStateWithLifecycle()
    // Only the hits for the text in the field: an older answer never shows.
    val trackerHits = if (trackerResults.query == query.trim()) trackerResults.hits else emptyList()

    LaunchedEffect(liveChats) { viewModel.updateChats(liveChats) }

    // Re-read the computed getter under the current query + chats.
    val chatHits: List<ChatSummary> = viewModel.chatHits

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
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
                label = { Text(if (onBack == null) "Search everything" else "Search chats, bots, and messages") },
                singleLine = true,
                // Search does Go to as well: Enter on a number opens what carries it.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    // Only the hit for the text in the field: a search for the new
                    // text may not have cleared the last one yet.
                    val hit = numberHit?.takeIf { it.num == GoToNumberParser.parse(query.trim()) }
                        ?: return@KeyboardActions
                    if (numberOpening) return@KeyboardActions
                    numberOpening = true
                    numberNote = null
                    scope.launch {
                        try {
                            numberNote = onSelectNumber(hit)
                        } finally {
                            numberOpening = false
                        }
                    }
                }),
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

                // The journal's tracker hits, one section per kind, best match first.
                for ((kind, heading) in TRACKER_SECTIONS) {
                    val hits = trackerHits.filter { it.kind == kind }
                    if (hits.isEmpty()) continue
                    item(key = "tracker-head-${kind.wire}") { SectionHeader(heading) }
                    items(hits, key = { "tracker-${it.kind.wire}-${it.id}" }) { hit ->
                        TrackerHitRow(hit, onTap = { onSelectTracker(hit) })
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
                            // Inset like the chat and tracker rows above it.
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }

                if (query.isEmpty()) {
                    item {
                        HintRow(
                            if (onBack == null) "Search conversations, items, missions, memories and messages."
                            else "Search across chat titles, bots, and messages.",
                        )
                    }
                } else if (numberHit == null && chatHits.isEmpty() && messageHits.isEmpty() && trackerHits.isEmpty() && !isSearching) {
                    val hint = if (searchFailed) "Search failed. Try again." else viewModel.emptyResultsMessage
                    item { HintRow(hint) }
                }
                // Messages may still show; say that items, missions and memories were not searched.
                if (trackerResults.failed && trackerResults.query == query.trim() && query.isNotBlank()) {
                    item { HintRow("Items, missions and memories could not be searched.") }
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

private val TRACKER_SECTIONS = listOf(
    TrackerSearchKind.ITEM to "Items",
    TrackerSearchKind.MISSION to "Missions",
    TrackerSearchKind.PROJECT to "Projects",
    TrackerSearchKind.MEMORY to "Memories",
)

/// One tracker hit: `#N title` (a memory's name), then the matched snippet
/// or the item's state, and how it was found when that was loose.
@Composable
private fun TrackerHitRow(hit: TrackerSearchHit, onTap: () -> Unit) {
    val heading = when {
        hit.kind == TrackerSearchKind.MEMORY -> hit.name ?: hit.title
        hit.num != null -> "#${hit.num} ${hit.title}"
        else -> hit.title
    }
    val detail = hit.snippet?.let { TrackerSearchDecoding.snippetBeyondTitle(it, listOf(hit.name, hit.title)) }
        ?: if (hit.kind == TrackerSearchKind.MEMORY && hit.name != null) hit.title else hit.state
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(heading, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        detail?.let {
            Text(
                starredSnippet(it, accent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TrackerSearchDecoding.whyLabel(hit.why)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/// The journal's snippets mark matches with `**…**`; bold and tint them.
internal fun starredSnippet(raw: String, highlight: Color): AnnotatedString = buildAnnotatedString {
    raw.split("**").forEachIndexed { index, part ->
        if (index % 2 == 1) withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) { append(part) }
        else append(part)
    }
}
