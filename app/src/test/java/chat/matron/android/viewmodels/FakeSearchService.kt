package chat.matron.android.viewmodels

import chat.matron.android.search.SearchHit
import chat.matron.android.search.SearchService
import java.time.Instant

/// Fake [SearchService] for view-model tests, ported from matron-apple's
/// `FakeSearchService`. Returns [hits] from [query]; everything else is inert.
class FakeSearchService(var hits: List<SearchHit> = emptyList()) : SearchService {
    override suspend fun index(
        roomID: String,
        eventID: String,
        sender: String,
        timestamp: Instant,
        body: String,
    ) {
    }

    override suspend fun remove(eventID: String) {}
    override suspend fun query(text: String, limit: Int): List<SearchHit> = hits
    override suspend fun wipe() {}
    override suspend fun recordBackfillProgress(
        roomID: String,
        indexedCount: Int,
        oldestEventID: String?,
        complete: Boolean,
    ) {
    }

    override suspend fun backfillComplete(roomID: String): Boolean = true
    override suspend fun eventCount(roomID: String): Int = 0
    override suspend fun contains(eventID: String): Boolean = false
}
