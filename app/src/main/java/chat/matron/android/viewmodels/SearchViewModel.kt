package chat.matron.android.viewmodels

import chat.matron.android.chat.ChatSummary
import chat.matron.android.chat.SessionTag
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

    /// Chat hits: title/bot-name/session-tag substring matches on the current
    /// snapshot. The tag clause is an Android addition: this branch peels the
    /// bridge's `[bc] ` prefix into [ChatSummary.sessionShort] and stores the
    /// CLEAN title, so without it a short the user can SEE in the row (`b5`,
    /// rendered as `A:b5`) would no longer find the chat. matron-apple's
    /// `chatHits` still matches only title + bot name and has the same gap
    /// (unfixed there as of apple #156).
    val chatHits: List<ChatSummary>
        get() {
            if (query.isEmpty()) return emptyList()
            val lower = query.lowercase()
            return _allChats.value.filter {
                it.title.lowercase().contains(lower) ||
                    it.bot.displayName.lowercase().contains(lower) ||
                    tagMatches(it, lower)
            }
        }

    /// Whether [lower] (an already-lowercased query) matches the chat's
    /// visible session tag: the bare short (`b5`) or the displayed
    /// letter:short form — `y:b5` for a single box, `y↔z:ab` / `y,z,w:ab`
    /// for a room, mirroring `SessionTagText`'s glyph order so what renders
    /// is what matches. A chat with no short has no tag to match, and a
    /// query that IS one of the chat's box letters never tag-matches it
    /// either — `y` substring-matching the rendered `y:b5` would light up
    /// every chat on that box, noise rather than search (the guard mirrors
    /// matron-apple #157; title/bot clauses are untouched, so `y` still
    /// title-matches). Accepted corner: a letter equal to the short's first
    /// character (`b` with short `b5`) is swallowed by the guard too.
    private fun tagMatches(chat: ChatSummary, lower: String): Boolean {
        val short = chat.sessionShort ?: return false
        val letters = if (chat.roomBoxShorts.size >= 2) {
            chat.roomBoxShorts
        } else {
            listOfNotNull(chat.boxShort)
        }
        if (letters.any { it.lowercase() == lower }) return false
        if (short.lowercase().contains(lower)) return true
        if (letters.isEmpty()) return false
        val joined = letters.joinToString(if (letters.size == 2) "↔" else ",")
        return "$joined:$short".lowercase().contains(lower)
    }

    /// Resolves a room ID to its display title, falling back to the raw room ID
    /// when the chat isn't in the snapshot (e.g. a hit from a left room).
    fun chatTitle(forRoomID: String): String =
        _allChats.value.firstOrNull { it.id == forRoomID }?.title ?: forRoomID

    /// Row-ready pieces of a search hit's title line: the colored `A:bc`
    /// tag halves plus the title to sit beside them, resolved HERE so the
    /// call sites compose identically (the row itself lives in the design
    /// system, which by design knows nothing of ChatSummary or the bridge's
    /// title markers). Ports matron-apple's `SearchViewModel.hitTitle`
    /// (apple #154).
    data class HitTitle(
        val title: String,
        val sessionShort: String?,
        val boxLetter: String?,
        val boxName: String?,
        val roomBoxNames: List<String>,
        val roomBoxShorts: List<String>,
    )

    fun hitTitle(roomID: String): HitTitle {
        val chat = _allChats.value.firstOrNull { it.id == roomID }
            ?: return HitTitle(
                title = roomID, sessionShort = null, boxLetter = null,
                boxName = null, roomBoxNames = emptyList(), roomBoxShorts = emptyList(),
            )
        // Same marker discipline as the list rows: the room marker drops
        // only when a room tag will actually render in its place.
        val title = if (chat.roomBoxNames.size >= 2) {
            SessionTag.titleBesideRoomTag(chat.title)
        } else {
            chat.title
        }
        return HitTitle(
            title = title, sessionShort = chat.sessionShort,
            boxLetter = chat.boxShort, boxName = chat.boxName,
            roomBoxNames = chat.roomBoxNames, roomBoxShorts = chat.roomBoxShorts,
        )
    }

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
