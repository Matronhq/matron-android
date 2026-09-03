package chat.matron.android.viewmodels

import chat.matron.android.search.SearchHit
import kotlinx.coroutines.CompletableDeferred
import chat.matron.android.search.SearchService
import java.time.Instant

/// Fake [SearchService] for view-model tests, ported from matron-apple's
/// `FakeSearchService`. Returns [hits] from [query]; everything else is inert.
class FakeSearchService(var hits: List<SearchHit> = emptyList(), var queryError: Throwable? = null) : SearchService {
    override suspend fun index(
        roomID: String,
        eventID: String,
        sender: String,
        timestamp: Instant,
        body: String,
    ) {
    }

    override suspend fun remove(eventID: String) {}
    /// When set, every query parks here until the test completes it.
    var queryGate: CompletableDeferred<Unit>? = null
    var queryCalls = 0
        private set

    override suspend fun query(text: String, limit: Int): List<SearchHit> {
        queryCalls += 1
        queryGate?.await()
        queryError?.let { throw it }
        return hits
    }
    override suspend fun wipe() {}
    override suspend fun recordBackfillProgress(
        roomID: String,
        indexedCount: Int,
        oldestEventID: String?,
        complete: Boolean,
    ) {
    }

    override suspend fun backfillComplete(roomID: String): Boolean = true
    override suspend fun backfillOldestEventID(roomID: String): String? = null
    override suspend fun eventCount(roomID: String): Int = 0
    override suspend fun contains(eventID: String): Boolean = false
}
