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

/// One conversation's aggregate in the grouped message-search results: how
/// many messages match, plus the newest matching message's snippet for the
/// row's preview line. The search UI shows ONE of these per chat
/// (WhatsApp-style) instead of a flat flood of per-message hits — a common
/// word's screenful of same-chat rows drowned everything else. [newestHit]
/// doubles as the jump target when the user opens the chat's in-conversation
/// search. Port of matron-apple's `SearchChatHit` (#172).
data class SearchChatHit(val roomID: String, val count: Int, val newestHit: SearchHit)
