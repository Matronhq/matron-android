package chat.matron.android.chat

import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/// Live [MediaService] backed by the journal server's `GET /media/:id`
/// endpoint. Any failure (unknown/expired blob, network error) maps to `null` —
/// the UI renders placeholders for `null` results. Ported from matron-apple's
/// `JournalMediaService`.
class JournalMediaService(private val api: JournalApi) : MediaService {

    /// Resolves a `serverURL/media/<ref>` URL to its bytes. A URL not under that
    /// prefix (e.g. a legacy `mxc://` URL) returns `null` without a request.
    override suspend fun image(url: String): ByteArray? {
        val blobRef = blobRef(url, api.serverURL) ?: return null
        return runCatching { api.mediaData(blobRef) }.getOrNull()
    }

    /// Surfaces the server's 404 as [MediaFetchOutcome.NotFound] so the
    /// attachment paths can mark a reaped blob permanently unavailable instead
    /// of treating every failure as retryable (port of apple #139). A URL
    /// outside this journal's media namespace is `Failure`, not `NotFound` —
    /// we know nothing definitive about it.
    override suspend fun fetchOutcome(url: String): MediaFetchOutcome {
        val blobRef = blobRef(url, api.serverURL) ?: return MediaFetchOutcome.Failure
        return try {
            MediaFetchOutcome.Data(api.mediaData(blobRef))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (error is JournalApiError.NotFound) MediaFetchOutcome.NotFound
            else MediaFetchOutcome.Failure
        }
    }

    companion object {
        /// Extracts the blob reference from a URL of the form
        /// `serverURL/media/<ref>`. `internal` so the extraction logic can be
        /// pinned by a direct test.
        internal fun blobRef(url: String, serverURL: HttpUrl): String? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            if (parsed.scheme != serverURL.scheme) return null
            if (parsed.host != serverURL.host) return null
            if (parsed.port != serverURL.port) return null
            // Honor a server mounted under a subpath: the media prefix is the
            // server's own path + "/media/", not a bare "/media/" (bugbot
            // "Subpath media URLs rejected").
            val prefix = serverURL.encodedPath.trimEnd('/') + "/media/"
            val path = parsed.encodedPath
            if (!path.startsWith(prefix)) return null
            val ref = path.removePrefix(prefix)
            return ref.ifEmpty { null }
        }
    }
}
