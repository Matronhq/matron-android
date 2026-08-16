package chat.matron.android.search

import androidx.room.withTransaction
import java.time.Instant

/// Room/FTS4-backed [SearchService]. Ported from matron-apple's
/// `SearchServiceLive` (GRDB → Room). Idempotency and redaction are handled by
/// explicit rowid lookup + delete rather than the Apple content-table triggers
/// (see [MessageFtsEntity] for why).
class SearchServiceLive(private val db: SearchDatabase) : SearchService {
    private val dao = db.searchDao()

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
        dao.deleteAllRooms()
    }

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
