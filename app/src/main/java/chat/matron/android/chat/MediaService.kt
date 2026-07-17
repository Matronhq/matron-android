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
}
