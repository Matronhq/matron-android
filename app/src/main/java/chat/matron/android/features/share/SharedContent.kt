package chat.matron.android.features.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import chat.matron.android.features.chat.pickedFilename
import chat.matron.android.models.AttachmentLimits
import chat.matron.android.models.StagedAttachment
import chat.matron.android.viewmodels.ChatViewModel
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID

/// A file handed to the share screen, copied into storage this app owns.
/// Ported from matron-apple's `SharedFile`.
data class SharedFile(
    val id: String,
    /// Our copy, alone in a directory of its own under the share work
    /// directory, so removing it is removing that directory.
    val file: File,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    /// Decides the wire kind (`image` or `file`), the same rule the
    /// composer's tray uses.
    val isImage: Boolean get() = mimeType.startsWith("image/")

    val formattedSize: String get() = formatBytes(sizeBytes)

    /// Deletes our copy. Failures are ignored: the work directory is emptied
    /// when the screen closes, and stale ones are cleared on the next share.
    fun delete() {
        runCatching { file.parentFile?.deleteRecursively() }
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1000) return "$bytes bytes"
            val units = listOf("kB", "MB", "GB", "TB")
            var value = bytes / 1000.0
            var unit = 0
            while (value >= 1000.0 && unit < units.lastIndex) {
                value /= 1000.0
                unit++
            }
            return String.format(Locale.US, "%.1f %s", value, units[unit])
        }
    }
}

/// What another app handed over in a share intent: text (a note, a web
/// link) and the content URIs of any files, before anything is read.
data class ShareIntentContent(
    val text: String?,
    val uris: List<Uri>,
    /// The conversation a direct-share target named, when the user picked
    /// one of Matron's recent chats in the system share sheet.
    val conversationID: String?,
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && uris.isEmpty()

    companion object {
        /// Reads an `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent.
        ///
        /// Only `content:` URIs from other apps are taken. A `file:` URI or
        /// one served by this app's own provider ([ownPackage]) could name
        /// one of Matron's private files, and an app sharing into Matron
        /// must not be able to send those anywhere.
        fun from(intent: Intent?, ownPackage: String): ShareIntentContent {
            if (intent == null) return ShareIntentContent(null, emptyList(), null)
            val streams = mutableListOf<Uri>()
            val texts = mutableListOf<String>()
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(streams::add)
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(texts::add)
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?.let(streams::addAll)
                    intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.mapTo(texts) { it.toString() }
                        ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(texts::add)
                }
            }
            // Some apps put the files only in the clip (the system copies the
            // stream extras there too, with the read grant).
            if (streams.isEmpty()) {
                intent.clipData?.let { clip ->
                    (0 until clip.itemCount).mapNotNullTo(streams) { clip.getItemAt(it).uri }
                }
            }
            val text = texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty {
                // A shared web page sometimes carries only its title.
                intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { streams.isEmpty() && it.isNotEmpty() }
            }
            val shortcut = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
                ?: intent.getStringExtra(ShareShortcuts.EXTRA_CONVERSATION_ID)?.let(ShareShortcuts::shortcutID)
            return ShareIntentContent(
                text = text?.takeIf { it.isNotEmpty() },
                uris = streams.distinct().filter { isForeignContentUri(it, ownPackage) },
                conversationID = shortcut?.let(ShareShortcuts::conversationID),
            )
        }

        fun isForeignContentUri(uri: Uri, ownPackage: String): Boolean {
            if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return false
            val authority = uri.authority ?: return false
            return authority != ownPackage && !authority.startsWith("$ownPackage.")
        }
    }
}

sealed class ShareLoadError(message: String) : Exception(message) {
    class TooLarge(val name: String) : ShareLoadError(AttachmentLimits.oversizeMessage(name))

    object Unreadable : ShareLoadError("Couldn't read one of the shared items.")
}

/// The reads [ShareItemLoader] makes of a shared URI, behind a seam so the
/// copy can be tested without another app's provider.
interface SharedItemSource {
    fun displayName(uri: Uri): String?
    /// The size the provider declares, or null when it doesn't say.
    fun declaredSize(uri: Uri): Long?
    fun type(uri: Uri): String?
    fun open(uri: Uri): InputStream?
}

class ContentResolverItemSource(private val resolver: ContentResolver) : SharedItemSource {
    override fun displayName(uri: Uri): String? = column(uri, OpenableColumns.DISPLAY_NAME) { getString(it) }

    override fun declaredSize(uri: Uri): Long? =
        column(uri, OpenableColumns.SIZE) { if (isNull(it)) null else getLong(it) }?.takeIf { it >= 0 }

    override fun type(uri: Uri): String? = runCatching { resolver.getType(uri) }.getOrNull()

    override fun open(uri: Uri): InputStream? = runCatching { resolver.openInputStream(uri) }.getOrNull()

    private fun <T> column(uri: Uri, name: String, read: android.database.Cursor.(Int) -> T?): T? = runCatching {
        resolver.query(uri, arrayOf(name), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(name)
            if (cursor.moveToFirst() && index >= 0) cursor.read(index) else null
        }
    }.getOrNull()
}

/// Copies shared files into the share work directory. Ported from
/// matron-apple's `ShareItemLoader`.
///
/// The copy is made as soon as the screen opens, before the app lock is
/// lifted: the read grant another app gives lasts only as long as this
/// screen, and a copy in app-private storage shows nothing to anyone.
/// Bytes are streamed, never held in memory, and the copy stops at the
/// upload limit rather than filling the disk with a file that can't be sent.
object ShareItemLoader {
    /// The server's upload limit, the composer's own.
    const val MAX_BYTES: Long = AttachmentLimits.MAX_BYTES

    fun load(uri: Uri, source: SharedItemSource, workDirectory: File, limit: Long = MAX_BYTES): SharedFile {
        val declaredType = source.type(uri)?.lowercase(Locale.US)
        val extension = declaredType?.let { extension(forMimeType = it) }
        val name = ChatViewModel.sanitisedAttachmentFilename(
            pickedFilename(source.displayName(uri), extension) { fallbackName(declaredType) },
        )
        source.declaredSize(uri)?.let { if (it > limit) throw ShareLoadError.TooLarge(name) }
        val id = UUID.randomUUID().toString()
        val directory = File(workDirectory, id).apply { mkdirs() }
        val destination = File(directory, name)
        try {
            val input = source.open(uri) ?: throw ShareLoadError.Unreadable
            val size = input.use { stream -> destination.outputStream().use { copyLimited(stream, it, limit, name) } }
            return SharedFile(
                id = id,
                file = destination,
                filename = name,
                mimeType = mimeType(declaredType, name),
                sizeBytes = size,
            )
        } catch (error: ShareLoadError) {
            directory.deleteRecursively()
            throw error
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw ShareLoadError.Unreadable
        }
    }

    /// Copies [input] to [output], throwing [ShareLoadError.TooLarge] once
    /// more than [limit] bytes have come through. Returns the bytes copied.
    fun copyLimited(input: InputStream, output: OutputStream, limit: Long, name: String): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > limit) throw ShareLoadError.TooLarge(name)
            output.write(buffer, 0, read)
        }
    }

    /// The provider's type when it names one, else the one the name's
    /// extension implies (the composer's rule).
    fun mimeType(declared: String?, filename: String): String {
        val concrete = declared?.takeIf { it.contains('/') && !it.contains('*') && it != "application/octet-stream" }
        return concrete ?: StagedAttachment.mimeType(forExtension = File(filename).extension)
    }

    private fun extension(forMimeType: String): String? =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(forMimeType)
            ?: forMimeType.substringAfter('/', "").substringBefore(';').removePrefix("x-")
                .takeIf { it.isNotEmpty() && it.all(Char::isLetterOrDigit) }

    private fun fallbackName(mimeType: String?): String =
        if (mimeType?.startsWith("image/") == true) "shared-image" else "shared-file"

    /// How old a work directory must be before it counts as left behind.
    /// Two shares can be on screen at once (from two apps, in two tasks), so
    /// a newer directory may belong to one still open.
    const val STALE_AFTER_MILLIS: Long = 24 * 60 * 60 * 1000

    /// Empties work directories a share left behind (the process was ended
    /// mid-share): those untouched for [STALE_AFTER_MILLIS], never [keep].
    fun clearStale(root: File, keep: File?, now: Long = System.currentTimeMillis()) {
        root.listFiles()?.forEach {
            if (it != keep && now - it.lastModified() > STALE_AFTER_MILLIS) it.deleteRecursively()
        }
    }
}
