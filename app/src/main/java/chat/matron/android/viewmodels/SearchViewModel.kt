package chat.matron.android.viewmodels

import chat.matron.android.chat.ChatSummary
import chat.matron.android.models.MatronDebug
import chat.matron.android.search.SearchHit
import chat.matron.android.search.SearchService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Drives the unified search UI. Owns the query string, the FTS message hits, and
/// the chat (title/bot) hits derived from a chat-list snapshot. Ported from
/// matron-apple's `SearchViewModel`.
class SearchViewModel(
    private val search: SearchService,
    allChats: List<ChatSummary>,
) {
    /// User-editable query.
    var query: String = ""

    private val _messageHits = MutableStateFlow<List<SearchHit>>(emptyList())
    val messageHits: StateFlow<List<SearchHit>> = _messageHits.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    /// True when the most recent [search] call failed (index corruption, DB
    /// error) rather than genuinely finding nothing — lets the UI distinguish
    /// "No results." from "Search failed" instead of rendering both the same.
    private val _searchFailed = MutableStateFlow(false)
    val searchFailed: StateFlow<Boolean> = _searchFailed.asStateFlow()

    private val _allChats = MutableStateFlow(allChats)
    val allChats: StateFlow<List<ChatSummary>> = _allChats.asStateFlow()

    /// Refreshes the chat-list snapshot backing chat-title hits. The long-lived
    /// search surface must track later chat-list updates (new rooms, renamed
    /// titles) rather than clinging to the first snapshot.
    fun updateChats(chats: List<ChatSummary>) {
        _allChats.value = chats
    }

    /// Chat hits: title/bot-name substring matches on the current snapshot.
    val chatHits: List<ChatSummary>
        get() {
            if (query.isEmpty()) return emptyList()
            val lower = query.lowercase()
            return _allChats.value.filter {
                it.title.lowercase().contains(lower) ||
                    it.bot.displayName.lowercase().contains(lower)
            }
        }

    /// Resolves a room ID to its display title, falling back to the raw room ID
    /// when the chat isn't in the snapshot (e.g. a hit from a left room).
    fun chatTitle(forRoomID: String): String =
        _allChats.value.firstOrNull { it.id == forRoomID }?.title ?: forRoomID

    /// Text shown when the query has no chat or message hits.
    val emptyResultsMessage: String get() = "No results."

    suspend fun search() {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _messageHits.value = emptyList()
            _searchFailed.value = false
            return
        }
        _isSearching.value = true
        try {
            val result = runCatching { search.query(trimmed, limit = 100) }
            result.onFailure { MatronDebug.breadcrumb("SearchViewModel: search failed for query \"$trimmed\": $it") }
            _searchFailed.value = result.isFailure
            _messageHits.value = result.getOrDefault(emptyList())
        } finally {
            _isSearching.value = false
        }
    }
}
