package chat.matron.android.search

import androidx.room.withTransaction
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/// Room/FTS4-backed [SearchService]. Ported from matron-apple's
/// `SearchServiceLive` (GRDB → Room). Idempotency and redaction are handled by
/// explicit rowid lookup + delete rather than the Apple content-table triggers
/// (see [MessageFtsEntity] for why).
class SearchServiceLive(private val db: SearchDatabase) : SearchService {
    private val dao = db.searchDao()

    /// See [chat.matron.android.journal.SearchIndexer.backfillGeneration].
    private val generation = AtomicLong(0)

    override suspend fun index(
        roomID: String,
        eventID: String,
        sender: String,
        timestamp: Instant,
        body: String,
    ) {
        db.withTransaction {
            // Replace any prior row for this event so a re-index refreshes the
            // FTS entry (true idempotency on event id).
            dao.rowidFor(eventID)?.let { dao.deleteByRowid(it) }
            dao.insertMessage(
                MessageFtsEntity(
                    rowid = null,
                    roomId = roomID,
                    eventId = eventID,
                    sender = sender,
                    timestamp = timestamp.epochSecond,
                    body = body,
                )
            )
        }
    }

    override suspend fun indexBatch(entries: List<SearchIndexEntry>) {
        if (entries.isEmpty()) return
        // ONE transaction (and one fsync) for the whole batch — the backfill
        // coordinator hands over a full fetched page at a time, and per-row
        // transactions made that hundreds of journal commits (Apple PR #130's
        // 2026-08-10 disk-write blowup). Same delete-then-insert idempotency
        // as [index], just amortised over one commit.
        db.withTransaction {
            for (entry in entries) {
                dao.rowidFor(entry.eventID)?.let { dao.deleteByRowid(it) }
                dao.insertMessage(
                    MessageFtsEntity(
                        rowid = null,
                        roomId = entry.roomID,
                        eventId = entry.eventID,
                        sender = entry.sender,
                        timestamp = entry.timestamp.epochSecond,
                        body = entry.body,
                    )
                )
            }
        }
    }

    override suspend fun remove(eventID: String) {
        dao.rowidFor(eventID)?.let { dao.deleteByRowid(it) }
    }

    override suspend fun query(text: String, limit: Int): List<SearchHit> {
        val pattern = buildPattern(text) ?: return emptyList()
        return dao.search(pattern, limit).map {
            SearchHit(
                id = it.id,
                roomID = it.roomID,
                sender = it.sender,
                timestamp = Instant.ofEpochSecond(it.timestamp),
                snippet = it.snippet,
            )
        }
    }

    override suspend fun queryGrouped(text: String, limit: Int): List<SearchChatHit> {
        val pattern = buildPattern(text) ?: return emptyList()
        val groups = dao.searchGrouped(pattern, limit)
        if (groups.isEmpty()) return emptyList()
        val snippets = dao.snippetsFor(pattern, groups.map { it.newestRowid }).associate { it.id to it.snippet }
        return groups.map { row ->
            SearchChatHit(
                roomID = row.roomID,
                count = row.hitCount,
                newestHit = SearchHit(
                    id = row.newestEventId,
                    roomID = row.roomID,
                    sender = row.newestSender,
                    timestamp = Instant.ofEpochSecond(row.newestTs),
                    snippet = snippets[row.newestEventId] ?: "",
                ),
            )
        }
    }

    override suspend fun query(text: String, roomID: String, limit: Int): List<SearchHit> {
        val pattern = buildPattern(text) ?: return emptyList()
        return dao.searchInRoom(pattern, roomID, limit).map {
            SearchHit(
                id = it.id,
                roomID = it.roomID,
                sender = it.sender,
                timestamp = Instant.ofEpochSecond(it.timestamp),
                snippet = it.snippet,
            )
        }
    }

    override suspend fun wipe() {
        db.withTransaction {
            dao.deleteAllMessages()
            dao.deleteAllRooms()
        }
    }

    override suspend fun recordBackfillProgress(
        roomID: String,
        indexedCount: Int,
        oldestEventID: String?,
        complete: Boolean,
    ) {
        dao.upsertRoom(
            IndexedRoomEntity(
                roomId = roomID,
                backfillComplete = complete,
                backfillOldestEventId = oldestEventID,
                backfillEventCount = indexedCount,
            )
        )
    }

    override suspend fun backfillComplete(roomID: String): Boolean = dao.backfillComplete(roomID) ?: false

    override suspend fun backfillOldestEventID(roomID: String): String? = dao.backfillOldestEventId(roomID)

    /// Bookkeeping only — indexed messages stay (see [SearchIndexer.resetBackfill]).
    override suspend fun resetBackfill() {
        // Bump BEFORE the delete: a backfill walk that observes the new
        // generation is guaranteed the delete is underway, so it discards its
        // stale progress instead of racing to re-assert it. (A walk that read
        // the old generation and commits its upsert in the sliver before the
        // delete lands gets wiped BY the delete.) The residual window — check
        // passes, then reset bumps+deletes, then the stale upsert commits — is
        // microseconds against a sweep that idles 15 minutes between passes;
        // closing it fully would need the generation inside the DB
        // transaction, which isn't worth it for a self-healing background
        // index (next cold start resets again).
        generation.incrementAndGet()
        dao.deleteAllRooms()
    }

    override suspend fun backfillGeneration(): Long = generation.get()

    override suspend fun eventCount(roomID: String): Int = dao.countForRoom(roomID)

    override suspend fun contains(eventID: String): Boolean = dao.contains(eventID)

    /// Builds the FTS4 MATCH pattern: each free-text token becomes a prefix
    /// query joined by implicit AND (`auth bug` → `auth* bug*`). This deviates
    /// from the Apple phrase-prefix pattern (`"auth bug"*`, which is FTS5
    /// grammar); a per-token prefix-AND is robust FTS4 syntax that preserves
    /// prefix search. Returns `null` for an all-punctuation query so the caller
    /// short-circuits to an empty result instead of issuing an invalid MATCH.
    private fun buildPattern(text: String): String? {
        val tokens = text.split(Regex("\\W+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }
}
