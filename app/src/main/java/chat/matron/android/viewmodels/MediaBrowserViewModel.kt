package chat.matron.android.viewmodels

import chat.matron.android.chat.JournalTimelineMapper
import chat.matron.android.chat.LinkExtractor
import chat.matron.android.chat.MediaFetchOutcome
import chat.matron.android.chat.MediaService
import chat.matron.android.chat.TimelineItem
import chat.matron.android.journal.MediaBrowserStoreReading
import chat.matron.android.journal.body
import chat.matron.android.storage.LRUCache
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl

/// Backs the per-chat "Media, Files and Links" sheet. Reads the FULL local
/// event history (the timeline's `ChatViewModel.items` is a 120-row window —
/// it cannot see a chat's older attachments), maps attachments through the
/// same payload contract the timeline uses (`JournalTimelineMapper`), and
/// extracts links with `LinkExtractor`. Ported from matron-apple's
/// `MediaBrowserViewModel` (apple #142).
///
/// Platform adaptations: published lists are `StateFlow`s (Swift's
/// `@Observable` properties); URLs are plain `String`s (the app-wide media-URL
/// representation); [scope] hosts the coalesced thumbnail fetches (Swift's
/// `@MainActor Task`s); and [thumbnail] caches the fetched *bytes* rather than
/// a downscaled image — Coil decodes to the grid cell's target size at render,
/// so the ImageIO downscale step has no work to do here. The byte cache is a
/// bounded LRU per the `ChatViewModel.resolvedImages` precedent.
class MediaBrowserViewModel(
    private val store: MediaBrowserStoreReading,
    private val convoID: String,
    private val serverURL: HttpUrl,
    private val media: MediaService,
    private val scope: CoroutineScope,
) {
    data class MediaEntry(
        val id: Long,
        val url: String?,
        val caption: String?,
        val expired: Boolean,
    )

    data class FileEntry(
        val id: Long,
        val url: String?,
        val name: String,
        val sizeBytes: Long?,
        val caption: String?,
        val expired: Boolean,
    )

    data class LinkEntry(
        /// The URL itself — unique post-dedup, so it doubles as the row id.
        val id: String,
        val url: String,
        /// First line of the containing message, capped at 200 chars.
        val context: String,
        val timestamp: Instant,
    )

    private val _mediaItems = MutableStateFlow<List<MediaEntry>>(emptyList())
    val mediaItems: StateFlow<List<MediaEntry>> = _mediaItems.asStateFlow()

    private val _fileItems = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileItems: StateFlow<List<FileEntry>> = _fileItems.asStateFlow()

    private val _links = MutableStateFlow<List<LinkEntry>>(emptyList())
    val links: StateFlow<List<LinkEntry>> = _links.asStateFlow()

    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /// URLs whose fetch returned a definitive 404 — reaped server-side,
    /// permanently gone. Sheet-local twin of `ChatViewModel.unavailableMedia`
    /// (the browser owns its own fetches, not the timeline's); a StateFlow so
    /// grid cells recompose when a 404 lands.
    private val _unavailableMedia = MutableStateFlow<Set<String>>(emptySet())
    val unavailableMedia: StateFlow<Set<String>> = _unavailableMedia.asStateFlow()

    /// Error copy for a tapped open that failed transiently — the sheet shows
    /// it in the same banner surface as `ChatViewModel.attachmentError` (the
    /// file-tap twin). Set only by [openMedia], never by the grid's passive
    /// [thumbnail] loads: a background thumbnail miss renders as a
    /// placeholder, not a banner.
    private val _attachmentError = MutableStateFlow<String?>(null)
    val attachmentError: StateFlow<String?> = _attachmentError.asStateFlow()

    fun dismissAttachmentError() {
        _attachmentError.value = null
    }

    /// Monotonic counter bumped whenever a fetch lands new bytes in the cache.
    /// The grid keys its cell loaders on it, so a cell whose own fetch failed
    /// transiently retries once any later fetch succeeds (a full-size open via
    /// [openMedia], another cell's load) instead of sitting on the placeholder
    /// until the cell happens to leave and re-enter composition. Never bumped
    /// on failure — that would re-key the loaders into a retry loop.
    private val _cacheVersion = MutableStateFlow(0)
    val cacheVersion: StateFlow<Int> = _cacheVersion.asStateFlow()

    /// Fetched thumbnail bytes, bounded LRU (see the class doc note on the
    /// downscale adaptation).
    private val thumbnails = LRUCache<String, ByteArray>(THUMBNAIL_CACHE_LIMIT)

    /// One shared task per in-flight URL so a grid redraw or a re-entrant tap
    /// joins the running fetch instead of stacking a second download. The
    /// task applies its own cache/expire side effects before completing, so a
    /// joiner can never observe a 404's `null` with `isUnavailable == false`
    /// (the ordering apple #142 pins with its coalescing tests).
    private val inFlight = mutableMapOf<String, Deferred<ByteArray?>>()

    /// One-shot read of both store queries into the published lists. Any
    /// store error flips [loadFailed] (the sheet renders its failure state)
    /// rather than throwing into the UI.
    suspend fun load() {
        try {
            val attachments = store.attachmentEvents(convoID)
            val candidates = store.linkCandidateEvents(convoID)
            val mediaAcc = mutableListOf<MediaEntry>()
            val fileAcc = mutableListOf<FileEntry>()
            for (event in attachments) {
                // Same payload contract as the timeline: blob_ref → media URL,
                // reaper tombstone → expired (ownSender is irrelevant here).
                val item = JournalTimelineMapper.timelineItem(event, ownSender = "", serverURL = serverURL)
                    ?: continue
                when (val kind = item.kind) {
                    is TimelineItem.Kind.Image ->
                        mediaAcc.add(MediaEntry(event.seq, kind.url, kind.caption, kind.expired))
                    is TimelineItem.Kind.File ->
                        fileAcc.add(
                            FileEntry(event.seq, kind.url, kind.filename, kind.sizeBytes, kind.caption, kind.expired)
                        )
                    else -> {}
                }
            }
            val seen = mutableSetOf<String>()
            val linkAcc = mutableListOf<LinkEntry>()
            for (event in candidates) {   // newest first → first sighting IS the newest
                val body = event.body() ?: continue
                val context = body.lineSequence().firstOrNull().orEmpty().take(200)
                for (url in LinkExtractor.links(body)) {
                    if (seen.add(url)) linkAcc.add(LinkEntry(url, url, context, event.ts))
                }
            }
            _mediaItems.value = mediaAcc
            _fileItems.value = fileAcc
            _links.value = linkAcc
            _loadFailed.value = false
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _loadFailed.value = true
            _mediaItems.value = emptyList()
            _fileItems.value = emptyList()
            _links.value = emptyList()
        }
    }

    fun isUnavailable(url: String): Boolean = url in _unavailableMedia.value

    /// The conversation's images as the media grid lists them — store order
    /// (newest first), tombstones kept. This is the list the fullscreen
    /// viewer's previous/next stepping walks; it goes through the same
    /// mapping as [load] and so can never disagree with the grid (apple #175).
    suspend fun imageEntries(): List<MediaEntry> =
        store.attachmentEvents(convoID).mapNotNull { event ->
            val item = JournalTimelineMapper.timelineItem(event, ownSender = "", serverURL = serverURL) ?: return@mapNotNull null
            (item.kind as? TimelineItem.Kind.Image)?.let { MediaEntry(event.seq, it.url, it.caption, it.expired) }
        }

    /// Fetch one grid thumbnail's bytes. A 404 is permanent (blob ids are
    /// immutable; the journal reaper deletes over-quota blobs) — the entry
    /// flips to expired and is never re-fetched. A transient failure returns
    /// `null` but stays retryable. Overlapping calls for the same URL
    /// coalesce onto a single fetch (see [inFlight]).
    suspend fun thumbnail(url: String): ByteArray? {
        thumbnails[url]?.let { return it }
        if (url in _unavailableMedia.value) return null
        inFlight[url]?.let { return it.await() }
        val task = scope.async {
            val result: ByteArray? = when (val outcome = media.fetchOutcome(url)) {
                is MediaFetchOutcome.Data -> {
                    thumbnails[url] = outcome.bytes
                    _cacheVersion.value += 1
                    outcome.bytes
                }
                MediaFetchOutcome.NotFound -> {
                    markExpired(url)
                    null
                }
                MediaFetchOutcome.Failure -> null
            }
            // Clear before completing: side effects above are visible to every
            // awaiter — owner or joiner — by the time `await` resumes.
            inFlight.remove(url)
            result
        }
        inFlight[url] = task
        return task.await()
    }

    /// Tap-path wrapper around [thumbnail] for opening a grid cell full-size:
    /// same bytes/cache/coalescing, but a *transient* failure (`null` on a
    /// still-available URL) sets [attachmentError] so the tap doesn't read as
    /// dead — `ChatViewModel.writeTempFile`'s contract for file taps. A 404
    /// stays banner-free: [markExpired] flips the cell and that is the
    /// feedback (same split as `writeTempFile`).
    suspend fun openMedia(url: String): ByteArray? {
        val bytes = thumbnail(url)
        if (bytes == null && url !in _unavailableMedia.value) {
            _attachmentError.value = "Couldn't open image — check your connection and try again."
        }
        return bytes
    }

    /// A 404-discovered expiry: record the URL as gone and flip every entry
    /// that references it, so the grid cell / file row re-renders as Expired
    /// without a reload.
    private fun markExpired(url: String) {
        _unavailableMedia.value += url
        _mediaItems.value = _mediaItems.value.map {
            if (it.url == url) it.copy(expired = true) else it
        }
        _fileItems.value = _fileItems.value.map {
            if (it.url == url) it.copy(expired = true) else it
        }
    }

    companion object {
        /// Mirrors apple #142's `thumbnailCacheLimit`.
        const val THUMBNAIL_CACHE_LIMIT = 64
    }
}
