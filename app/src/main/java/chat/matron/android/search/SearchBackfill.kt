package chat.matron.android.search

import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.previewText
import chat.matron.android.models.MatronDebug
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/// Walks every conversation's server-side history backward through
/// `GET /convo/:id/messages` and indexes it into the local FTS store.
///
/// Ported from matron-apple's `SearchBackfillCoordinator`
/// (MatronShared/Sources/Journal/SearchBackfill.swift), including Apple
/// PR #130's page-batched indexing.
///
/// Why this exists: the live index feeders only cover events this device has
/// actually seen. A device that bootstraps from `/snapshot` (fresh install, or
/// a replay gap past the server's valve) receives conversation metadata but no
/// message bodies, so its search index starts empty and pre-bootstrap history
/// is findable only by manually scrolling each chat. This coordinator fills
/// that hole from the server's durable journal.
///
/// The walk is serial across conversations and throttled between pages — it's
/// a low-priority background sweep, not a race. Per-conversation progress
/// persists in the search store's `indexed_rooms` bookkeeping
/// ([SearchService.recordBackfillProgress] / [SearchService.backfillOldestEventID]),
/// so an interrupted walk resumes where it left off and a completed
/// conversation costs one local read on later sweeps. Indexing is idempotent
/// (delete-then-insert on event id), so overlap with the live feeders is
/// harmless.
///
/// The searchable-body mapping lives in [previewText] — Android's existing
/// single source of truth for all three index feeders (live sync, backward
/// pagination, and this backfill), the analogue of Apple's
/// `JournalEvent.searchableBody`.
class SearchBackfillCoordinator(
    private val search: SearchService,
    private val pageSize: Int = 200,
    private val throttleMillis: Long = 100,
    /// Page fetcher, `JournalApi.messages(convoID, beforeSeq, limit)`-shaped.
    /// A lambda rather than the concrete API client so tests script pages
    /// without a network stack.
    private val fetchPage: suspend (convoID: String, beforeSeq: Long?, limit: Int) -> List<JournalEvent>,
) {
    /// Sweeps [convoIDs] serially. Returns `true` when every conversation is
    /// fully indexed; `false` when any failed (offline, server error) or the
    /// job was cancelled, so the caller can retry the sweep later. A failed
    /// conversation never blocks the rest of the sweep.
    suspend fun run(convoIDs: List<String>): Boolean {
        var allComplete = true
        for (convoID in convoIDs) {
            if (!currentCoroutineContext().isActive) return false
            try {
                backfill(convoID)
            } catch (e: CancellationException) {
                // Mirrors Apple's `catch is CancellationError { return false }`:
                // the sweep reports "not complete" and stops doing work; the
                // cancelled job still dies at its caller's next suspension.
                return false
            } catch (e: Throwable) {
                // Transient by assumption (the sweep retries): record and move on.
                allComplete = false
                MatronDebug.breadcrumb("SearchBackfill: backfill failed for $convoID: $e")
            }
        }
        return allComplete
    }

    private suspend fun backfill(convoID: String) {
        if (search.backfillComplete(convoID)) return
        // Resume point: the oldest seq a previous walk reached. `null` starts
        // at the newest page — those events are usually live-indexed already,
        // but re-indexing is idempotent and the head page is what anchors the
        // downward walk.
        var oldest: Long? = search.backfillOldestEventID(convoID)?.toLongOrNull()
        while (true) {
            currentCoroutineContext().ensureActive()
            val events = fetchPage(convoID, oldest, pageSize)
            // The server already filters `seq < before_seq`; keep the guard
            // anyway so a misbehaving page can't rewind `oldest` (mirrors
            // `paginateBackward`'s belt-and-braces filter).
            val older = oldest?.let { bound -> events.filter { it.seq < bound } } ?: events
            // ONE transaction per page, not one per event (Apple PR #130). The
            // per-event `search.index` version fsync'd a write transaction per
            // message and re-dirtied FTS b-tree interior pages for every
            // commit — against a ~175 MB index that amplified to ~1 MB of WAL
            // writes per MESSAGE, and Apple's 2026-08-10 post-wipe sweep
            // (2,179 rooms, ~200K messages) tripped the OS disk-writes
            // resource limit (8.6 GB dirtied in 12 minutes). Batching a
            // 200-event page into one commit amortises the tree churn.
            val entries = older.mapNotNull { event ->
                val body = event.previewText()
                if (body.isNullOrEmpty()) return@mapNotNull null
                SearchIndexEntry(
                    roomID = event.convoID, eventID = event.seq.toString(),
                    sender = event.sender, timestamp = event.ts, body = body,
                )
            }
            search.indexBatch(entries)
            val pageOldest = older.minOfOrNull { it.seq }
            if (pageOldest != null) oldest = pageOldest
            // A short page means history is exhausted. A full page that made
            // no progress can only come from a server ignoring `before_seq`;
            // terminating (as complete) beats looping on it forever.
            val exhausted = events.size < pageSize || pageOldest == null
            val indexedCount = search.eventCount(convoID)
            search.recordBackfillProgress(
                roomID = convoID, indexedCount = indexedCount,
                oldestEventID = oldest?.toString(), complete = exhausted,
            )
            if (exhausted) return
            delay(throttleMillis)
        }
    }
}
