package chat.matron.android.chat

/// Resolves an attachment URL into raw bytes the UI can decode. Ported from
/// matron-apple's `MediaService` (which takes a `URL`); here the argument is the
/// `String` URL carried on [TimelineItem.Kind.Image]/`File`, since a legacy
/// `mxc://` URL isn't a valid `HttpUrl`.
///
/// The SwiftUI `swiftUIImage`/`fetchBytes` convenience extensions are UI-layer
/// and out of scope for the services port.
interface MediaService {
    /// Resolve a media URL to raw bytes. Returns `null` if the URL isn't a
    /// resolvable media URL or the fetch fails (network error, missing media).
    suspend fun image(url: String): ByteArray?

    /// Like [image] but distinguishes a definitive "this blob no longer
    /// exists" (HTTP 404 — permanent: blob ids are immutable, and the
    /// journal's media reaper deletes blobs for over-quota users) from a
    /// transient failure worth retrying. The attachment paths use
    /// [MediaFetchOutcome.NotFound] to flip the chip to its Expired state
    /// (port of apple #139).
    ///
    /// Default: no status information available, so a `null` byte result is a
    /// plain (retryable) failure. Fakes get this for free;
    /// [JournalMediaService] overrides it to surface the 404.
    suspend fun fetchOutcome(url: String): MediaFetchOutcome =
        image(url)?.let { MediaFetchOutcome.Data(it) } ?: MediaFetchOutcome.Failure
}

/// Outcome of a media fetch where the caller needs to tell "gone forever" from
/// "try again". Plain class (not data class) for [Data] — ByteArray equality is
/// referential and irrelevant here.
sealed interface MediaFetchOutcome {
    class Data(val bytes: ByteArray) : MediaFetchOutcome
    /// The server definitively reports the blob missing (404). Permanent.
    data object NotFound : MediaFetchOutcome
    /// Anything else — network error, auth failure, decode problem.
    data object Failure : MediaFetchOutcome
}
