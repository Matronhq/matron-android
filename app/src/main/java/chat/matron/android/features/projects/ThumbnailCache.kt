package chat.matron.android.features.projects

import android.util.LruCache
import chat.matron.android.chat.MediaService
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl

/// The Files grid's pictures, by blob id, kept up to [maxBytes] and the
/// least recently used dropped first: a large project's grid never pins
/// every picture it has scrolled past. A miss is fetched through the
/// session's authenticated media service; a failed fetch is not kept, so
/// the tile asks again when it next comes on screen.
class ThumbnailCache(
    private val media: MediaService,
    private val serverURL: HttpUrl,
    maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val cache = object : LruCache<String, ByteArray>(maxBytes) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size.coerceAtLeast(1)
    }

    operator fun get(blobID: String): ByteArray? = cache.get(blobID)

    suspend fun load(blobID: String): ByteArray? {
        cache.get(blobID)?.let { return it }
        val bytes = try {
            media.image(projectMediaURL(serverURL, blobID))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        } ?: return null
        cache.put(blobID, bytes)
        return bytes
    }

    /// What is held now, for the viewer to open neighbours from.
    fun snapshot(): Map<String, Any> = cache.snapshot()

    companion object {
        const val DEFAULT_MAX_BYTES = 32 * 1024 * 1024
    }
}
