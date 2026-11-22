package chat.matron.android.search

import chat.matron.android.journal.JournalEventType
import chat.matron.android.journal.JournalSearching
import chat.matron.android.models.MatronDebug
import kotlinx.coroutines.CancellationException
import java.time.Instant

/// The app's message search: queries go to the journal server, which holds
/// every message and answers the same on every device; the local index
/// answers only when the server can't be reached. Writes and bookkeeping go
/// to the local index, so anything handed this service keeps feeding the
/// fallback.
///
/// Why server first: the local index is built by walking each
/// conversation's history on this device, and it has holes (a chat never
/// opened here, history older than the walk reached), so a message the
/// server has could not be found on the phone. Subagent chats are left out
/// on both paths; they are most of the history and almost never what a
/// person is looking for. Ported from matron-apple's
/// `ServerFirstSearchService`.
class ServerFirstSearchService(
    private val remote: JournalSearching,
    /// `null` when the index could not be opened: search still works, from
    /// the server alone.
    private val local: SearchService?,
) : SearchService {
    private fun isSubagent(roomID: String) = roomID.contains(JournalEventType.CHILD_CONVO_INFIX)

    // MARK: Queries

    override suspend fun queryGrouped(text: String, limit: Int): List<SearchChatHit> {
        val terms = SearchTerms.parse(text) ?: return emptyList()
        return serverOr(
            server = {
                remote.searchChats(text, limit = minOf(limit, SERVER_LIMIT), excludeSubagents = true).map { chat ->
                    SearchChatHit(
                        roomID = chat.convoID,
                        count = chat.count,
                        newestHit = SearchHit(
                            id = chat.top.seq.toString(),
                            roomID = chat.convoID,
                            sender = chat.top.sender,
                            timestamp = chat.top.ts,
                            snippet = SearchSnippet.make(chat.excerpt, terms),
                        ),
                    )
                }
            },
            fallback = { it.queryGrouped(text, limit).filterNot { hit -> isSubagent(hit.roomID) } },
        )
    }

    override suspend fun query(text: String, limit: Int): List<SearchHit> = recent(text, convoID = null, limit = limit)

    override suspend fun query(text: String, roomID: String, limit: Int): List<SearchHit> =
        recent(text, convoID = roomID, limit = limit)

    private suspend fun recent(text: String, convoID: String?, limit: Int): List<SearchHit> {
        if (SearchTerms.parse(text) == null) return emptyList()
        return serverOr(
            server = {
                // No snippet: find-in-chat only steps through seqs, with the
                // messages already on screen.
                remote.searchRecent(text, convoID = convoID, limit = limit, excludeSubagents = true).map {
                    SearchHit(id = it.seq.toString(), roomID = it.convoID, sender = it.sender, timestamp = it.ts, snippet = "")
                }
            },
            fallback = { index ->
                if (convoID != null) {
                    index.query(text, convoID, limit)
                } else {
                    index.query(text, limit).filterNot { isSubagent(it.roomID) }
                }
            },
        )
    }

    private suspend fun <T> serverOr(server: suspend () -> T, fallback: suspend (SearchService) -> T): T =
        try {
            server()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            val index = local ?: throw error
            MatronDebug.breadcrumb("ServerFirstSearchService: server search unavailable ($error); using the local index")
            fallback(index)
        }

    // MARK: Writes and bookkeeping: the local index's

    override suspend fun index(roomID: String, eventID: String, sender: String, timestamp: Instant, body: String) {
        local?.index(roomID, eventID, sender, timestamp, body)
    }

    override suspend fun indexBatch(entries: List<SearchIndexEntry>) {
        local?.indexBatch(entries)
    }

    override suspend fun remove(eventID: String) {
        local?.remove(eventID)
    }

    override suspend fun removeAll(eventIDs: List<String>) {
        local?.removeAll(eventIDs)
    }

    override suspend fun removeRooms(roomIDs: List<String>) {
        local?.removeRooms(roomIDs)
    }

    override suspend fun wipe() {
        local?.wipe()
    }

    override suspend fun resetBackfill() {
        local?.resetBackfill()
    }

    override suspend fun backfillGeneration(): Long = local?.backfillGeneration() ?: 0

    override suspend fun recordBackfillProgress(roomID: String, indexedCount: Int, oldestEventID: String?, complete: Boolean) {
        local?.recordBackfillProgress(roomID, indexedCount, oldestEventID, complete)
    }

    override suspend fun backfillComplete(roomID: String): Boolean = local?.backfillComplete(roomID) ?: false

    override suspend fun backfillOldestEventID(roomID: String): String? = local?.backfillOldestEventID(roomID)

    override suspend fun eventCount(roomID: String): Int = local?.eventCount(roomID) ?: 0

    override suspend fun contains(eventID: String): Boolean = local?.contains(eventID) ?: false

    private companion object {
        /// The journal caps a `chats` page at 50.
        const val SERVER_LIMIT = 50
    }
}
