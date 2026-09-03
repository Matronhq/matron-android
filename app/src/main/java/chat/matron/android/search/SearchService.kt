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

    /// Inserts a batch of messages in ONE write transaction. Ported from
    /// matron-apple's `SearchService.indexBatch` (Apple PR #130): the per-event
    /// [index] path commits (and fsyncs) a transaction per message, which
    /// against a large FTS index amplifies to megabytes of WAL writes per
    /// MESSAGE — the 2026-08-10 backfill sweep on Apple tripped the OS
    /// disk-writes resource limit. Batching a page into one commit amortises
    /// the b-tree churn. Default implementation loops [index] (fine for fakes;
    /// [SearchServiceLive] overrides with a real single transaction).
    suspend fun indexBatch(entries: List<SearchIndexEntry>) {
        for (entry in entries) {
            index(entry.roomID, entry.eventID, entry.sender, entry.timestamp, entry.body)
        }
    }

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

    /// The oldest event id a previous backfill walk reached for [roomID]
    /// (recorded via [recordBackfillProgress]), or `null` if backfill has
    /// never run there. The walk's resume point. Ported from matron-apple's
    /// `SearchService.backfillOldestEventID`.
    suspend fun backfillOldestEventID(roomID: String): String?

    /// Number of indexed events for [roomID] (used to resume a backfill).
    suspend fun eventCount(roomID: String): Int

    /// True if an event with [eventID] is already indexed (skip duplicates).
    suspend fun contains(eventID: String): Boolean
}
