package chat.matron.android.chat

import chat.matron.android.journal.JournalApi
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
