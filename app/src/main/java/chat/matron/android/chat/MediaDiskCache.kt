package chat.matron.android.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/// On-disk cache of media blobs (chat images and files), keyed by the
/// journal's blob id. Ported from matron-apple's `MediaDiskCache`.
///
/// Blob ids are immutable (the bytes behind one never change), so an entry
/// never goes stale; it only ages out. An entry is kept for [maxAgeMs] after
/// it was last read or written (reading one renews it), and the whole cache is
/// held under [byteLimit] by evicting the least recently used entries first.
///
/// Without this, every image and file was fetched again each time a chat was
/// opened: the bytes reach Coil as a `ByteArray`, which Coil's own disk cache
/// does not keep (it caches only what it fetched over the network itself).
///
/// One cache per signed-in account, in its own directory under the app's
/// private cache dir (named by a hash, so no account or blob id appears in the
/// file system). The OS may clear it under storage pressure; that only costs
/// a re-fetch. Sign-out calls [removeAll] on each live cache and
/// [removeAllOnDisk] for anything left by earlier launches.
class MediaDiskCache(
    private val directory: File,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val byteLimit: Long = DEFAULT_BYTE_LIMIT,
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /// Running total of the bytes on disk; `null` until the first write scans
    /// the directory.
    private var totalBytes: Long? = null

    /// Set by [removeAll]: a fetch that was in flight at sign-out must not
    /// write the previous account's media back to disk.
    private var closed = false

    /// The cached bytes for [blobRef], or `null` when absent or expired. A hit
    /// renews the entry's age.
    suspend fun data(blobRef: String): ByteArray? = locked {
        if (closed) return@locked null
        val file = file(blobRef)
        val modified = file.lastModified().takeIf { it > 0 } ?: return@locked null
        if (now() - modified > maxAgeMs) {
            val size = file.length()
            if (file.delete()) totalBytes = totalBytes?.let { maxOf(0, it - size) }
            return@locked null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@locked null
        file.setLastModified(now())
        bytes
    }

    /// Stores [bytes] under [blobRef], then evicts the least recently used
    /// entries if the cache has grown past its limit. Empty and oversized
    /// blobs (a long video would push out hundreds of images) are skipped.
    suspend fun store(bytes: ByteArray, blobRef: String) = locked {
        val size = bytes.size.toLong()
        if (closed || size == 0L || size > maxEntryBytes || size > byteLimit) return@locked
        if (!directory.isDirectory && !directory.mkdirs()) return@locked
        var total = totalBytes ?: scan()
        val file = file(blobRef)
        val existing = if (file.exists()) file.length() else 0L
        // Write beside the entry and rename over it, so a reader never sees a
        // half-written file and a failed write leaves the old one in place.
        val temp = File(directory, "${file.name}.tmp")
        val written = runCatching { temp.writeBytes(bytes) }.isSuccess && temp.renameTo(file)
        if (!written) {
            temp.delete()
            return@locked
        }
        file.setLastModified(now())
        total += size - existing
        totalBytes = total
        if (total > byteLimit) evict()
    }

    /// Deletes this cache's files and stops it writing again. Sign-out.
    suspend fun removeAll() = locked {
        closed = true
        directory.deleteRecursively()
        totalBytes = 0
    }

    private suspend fun <T> locked(block: () -> T): T =
        withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun file(blobRef: String) = File(directory, sha256(blobRef))

    private fun entries(): List<File> =
        directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: emptyList()

    /// Sums the directory, dropping expired entries and leftover temp files on
    /// the way, so a cache that is only ever written still ages out.
    private fun scan(): Long {
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        val cutoff = now() - maxAgeMs
        var total = 0L
        for (entry in entries()) {
            // An expired entry that won't delete still takes up space.
            if (entry.lastModified() >= cutoff || !entry.delete()) total += entry.length()
        }
        return total
    }

    /// Drops the least recently used entries until the cache is at 90% of its
    /// limit, so the next few writes don't each trigger another pass.
    private fun evict() {
        val target = byteLimit / 10 * 9
        var total = 0L
        val byAge = entries().map { Triple(it, it.lastModified(), it.length()) }.sortedBy { it.second }
        byAge.forEach { total += it.third }
        for ((entry, _, size) in byAge) {
            if (total <= target) break
            if (entry.delete()) total -= size
        }
        totalBytes = total
    }

    companion object {
        /// How long an entry stays after it was last used.
        const val DEFAULT_MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

        /// Total size the cache is held under.
        const val DEFAULT_BYTE_LIMIT: Long = 250L * 1024 * 1024

        /// Larger blobs (long videos, big PDFs) are not cached.
        const val DEFAULT_MAX_ENTRY_BYTES: Long = 20L * 1024 * 1024

        /// Root of every account's media cache, under [cacheDir] (the app's
        /// private `Context.cacheDir`).
        fun rootDirectory(cacheDir: File): File = File(cacheDir, "media")

        /// The directory for one account, named by a hash.
        fun directory(root: File, accountID: String): File = File(root, sha256(accountID))

        /// Deletes every account's cache. Sign-out runs it after closing the
        /// live caches, to catch directories left by earlier launches.
        fun removeAllOnDisk(root: File) {
            root.deleteRecursively()
        }

        internal fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
