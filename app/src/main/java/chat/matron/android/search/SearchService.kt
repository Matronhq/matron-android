package chat.matron.android.search

import chat.matron.android.journal.SearchIndexer

/// Local full-text search index over message bodies, backed by SQLite/FTS4 in
/// production ([SearchServiceLive]). Ported from matron-apple's `SearchService`
/// protocol.
///
/// Extends [SearchIndexer] (the seam the sync engine already indexes through) so
/// a single [SearchServiceLive] satisfies both the engine's index-on-apply hook
/// and the timeline's pagination-time indexing + query surface — the Apple side
/// uses one `SearchService` type for both.
interface SearchService : SearchIndexer {
    /// Inserts a single message into the index. Idempotent on (roomID, eventID).
    override suspend fun index(
        roomID: String,
        eventID: String,
        sender: String,
        timestamp: java.time.Instant,
        body: String,
    )

    /// Removes a single event (used for redactions).
    suspend fun remove(eventID: String)

    /// Queries by free-text. Returns at most [limit] hits, newest first.
    suspend fun query(text: String, limit: Int): List<SearchHit>

    /// Wipes all data (used on sign-out).
    suspend fun wipe()

    /// Records progress for a room's backfill.
    suspend fun recordBackfillProgress(
        roomID: String,
        indexedCount: Int,
        oldestEventID: String?,
        complete: Boolean,
    )

    /// True if backfill has previously completed for [roomID].
    suspend fun backfillComplete(roomID: String): Boolean

    /// Number of indexed events for [roomID] (used to resume a backfill).
    suspend fun eventCount(roomID: String): Int

    /// True if an event with [eventID] is already indexed (skip duplicates).
    suspend fun contains(eventID: String): Boolean
}
