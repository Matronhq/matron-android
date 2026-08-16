package chat.matron.android.search

import java.time.Instant

/// A single full-text search match. `snippet` carries `<mark>…</mark>` markup
/// around the matched terms. Ported from matron-apple's `SearchHit`.
data class SearchHit(
    val id: String, // event ID
    val roomID: String,
    val sender: String,
    val timestamp: Instant,
    val snippet: String, // contains <mark>…</mark> markup
)

/// Per-room backfill progress, recorded into / read from `indexed_rooms`.
data class BackfillProgress(
    val roomID: String,
    val eventsIndexed: Int,
    val isComplete: Boolean,
)

/// One row for [SearchService.indexBatch]. Ported from matron-apple's
/// `SearchIndexEntry` (Apple PR #130): a value the backfill coordinator can
/// assemble per page so the whole page lands in ONE write transaction.
data class SearchIndexEntry(
    val roomID: String,
    val eventID: String,
    val sender: String,
    val timestamp: Instant,
    val body: String,
)
