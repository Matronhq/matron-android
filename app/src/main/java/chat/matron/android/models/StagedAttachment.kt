package chat.matron.android.models

import java.io.File
import java.net.URLConnection
import java.util.Locale
import java.util.UUID

/// A file the user has attached but not yet sent.
///
/// Attachments are staged (copied into a scratch directory) at attach time so
/// they leave with the composer text that explains them, as one turn, and so an
/// unreadable source fails while the user is still looking at the picker.
data class StagedAttachment(
    val id: String,
    /// Location of OUR copy, inside the staging directory. Deleted when the
    /// attachment is removed, sent, or discarded.
    val file: File,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    /// Drives both the tray (thumbnail vs. file chip) and the send path
    /// (`sendImage` vs. `sendFile`), so the two can never disagree about what a
    /// given attachment is.
    val isImage: Boolean get() = mimeType.startsWith("image/")

    /// Human-readable size for the tray's file chips.
    val formattedSize: String get() = formatBytes(sizeBytes)

    /// Best-effort removal of this attachment's staged copy. Failures are
    /// ignored: a temp file we couldn't delete is litter the OS clears.
    fun deleteStagedCopy() {
        runCatching { file.parentFile?.deleteRecursively() }
    }

    companion object {
        /// Copies `source` into `stagingDirectory` and describes the result.
        ///
        /// The UUID *directory* (rather than a UUID filename prefix) keeps the
        /// user-visible filename intact — what the tray shows, the journal event
        /// carries, and claude sees — while still letting two attachments with
        /// the same name coexist.
        fun stage(source: File, stagingDirectory: File): StagedAttachment {
            val id = UUID.randomUUID().toString()
            val directory = File(stagingDirectory, id).apply { mkdirs() }
            val name = source.name.ifEmpty { "attachment" }
            val destination = File(directory, name)
            source.copyTo(destination, overwrite = true)
            return StagedAttachment(
                id = id,
                file = destination,
                filename = name,
                mimeType = mimeType(forExtension = source.extension),
                sizeBytes = destination.length(),
            )
        }

        /// The MIME type comes from the path extension; an unmappable extension
        /// falls back to a generic binary rather than guessing.
        fun mimeType(forExtension: String): String {
            if (forExtension.isEmpty()) return "application/octet-stream"
            return URLConnection.guessContentTypeFromName("f.$forExtension")
                ?: "application/octet-stream"
        }

        private fun formatBytes(bytes: Long): String {
            if (bytes < 1000) return "$bytes bytes"
            val units = listOf("kB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1000.0
            var unit = 0
            while (value >= 1000.0 && unit < units.lastIndex) {
                value /= 1000.0
                unit++
            }
            return String.format(Locale.US, "%.1f %s", value, units[unit])
        }
    }
}
