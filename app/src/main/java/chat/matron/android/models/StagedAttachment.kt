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
///
/// Construction is factory-only ([stage]) so [deleteStagedCopy]'s
/// `parentFile?.deleteRecursively()` is always scoped to a UUID staging
/// directory this type created — never a caller-supplied path. `copy()` is
/// kept equally private ([ConsistentCopyVisibility]) so it can't be used to
/// route around the factory.
@ConsistentCopyVisibility
data class StagedAttachment private constructor(
    val id: String,
    /// Location of OUR copy, inside the staging directory. Deleted when the
    /// attachment is removed, sent, or discarded.
    val file: File,
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
    /// The batch tag this attachment's frame carried when a send failed, or
    /// null for an attachment that hasn't failed out of a batch. Freshly
    /// staged attachments never have one — the composer stamps it onto the
    /// unsent leftovers of a failed multi-attachment send so a retry can
    /// re-emit the frame under the SAME `batch_id`/`batch_index`/
    /// `batch_total`. The bridge gathers frames by batch id: a retried
    /// frame under the original id either deposits into the still-open
    /// gather (completing the message) or, if the batch already finalized,
    /// is routed down the per-frame path. A fresh id could do neither — the
    /// frame would sit waiting for siblings that already went out, and the
    /// user's one message would arrive fractured. Ported from matron-apple's
    /// `StagedAttachment.batchTag` (matron-apple#157).
    val batchTag: AttachmentBatchTag? = null,
) {
    /// Drives both the tray (thumbnail vs. file chip) and the send path
    /// (`sendImage` vs. `sendFile`), so the two can never disagree about what a
    /// given attachment is.
    val isImage: Boolean get() = mimeType.startsWith("image/")

    /// Human-readable size for the tray's file chips.
    val formattedSize: String get() = formatBytes(sizeBytes)

    /// The same staged file under a (possibly different) batch tag. The only
    /// sanctioned use of `copy()` — it can't reroute [file] out of the staging
    /// directory, so [deleteStagedCopy]'s scope guarantee holds.
    fun carrying(batchTag: AttachmentBatchTag?): StagedAttachment = copy(batchTag = batchTag)

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
        ///
        /// Video containers are pinned explicitly: the photo picker offers
        /// screen recordings (apple #160) and the JVM's `URLConnection` table
        /// doesn't know all of them, so they'd otherwise land as octet-stream
        /// and the bridge couldn't tell a recording from a blob.
        fun mimeType(forExtension: String): String {
            if (forExtension.isEmpty()) return "application/octet-stream"
            VIDEO_MIME_BY_EXTENSION[forExtension.lowercase(Locale.US)]?.let { return it }
            return URLConnection.guessContentTypeFromName("f.$forExtension")
                ?: "application/octet-stream"
        }

        private val VIDEO_MIME_BY_EXTENSION = mapOf(
            "mp4" to "video/mp4",
            "m4v" to "video/x-m4v",
            "mov" to "video/quicktime",
            "webm" to "video/webm",
            "3gp" to "video/3gpp",
            "mkv" to "video/x-matroska",
        )

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
