package chat.matron.android.features.share

import chat.matron.android.models.AttachmentBatchTag
import java.util.UUID

/// Where a send has got to, for the progress bar. Ported from matron-apple's
/// `ShareProgress`.
data class ShareProgress(
    val step: Step,
    /// The file being uploaded, 1-based, or null when no file is.
    val fileIndex: Int? = null,
    val fileCount: Int = 0,
    val filename: String? = null,
    /// 0…1 across the whole send.
    val fraction: Double = 0.0,
) {
    enum class Step { CONNECTING, UPLOADING, POSTING }

    /// The line above the progress bar.
    val statusText: String
        get() = when {
            step == Step.CONNECTING -> "Connecting…"
            step == Step.UPLOADING && filename != null && fileIndex != null ->
                if (fileCount > 1) "Uploading $filename ($fileIndex of $fileCount)" else "Uploading $filename"
            else -> "Sending…"
        }
}

/// The network half of a share, behind a seam so the sequencing can be
/// tested without a server.
interface ShareTransport {
    /// Brings the journal connection up, or throws when it can't be.
    suspend fun connect()

    suspend fun sendText(convoID: String, body: String)

    /// Uploads [file] and posts it into [convoID]. [progress] gets the
    /// uploaded fraction, 0…1.
    suspend fun sendFile(
        convoID: String,
        file: SharedFile,
        caption: String?,
        batch: AttachmentBatchTag?,
        progress: (Double) -> Unit,
    )

    /// Waits (briefly) for anything still queued to leave, once the share
    /// went through.
    suspend fun finish()

    /// Lets go of the connection, if the share was what brought it up.
    /// Called when the screen closes, however it closes.
    fun release() {}
}

/// Sends a share: each file in turn, the message riding as the first file's
/// caption, or the message alone when nothing is attached. The same shape
/// the composer sends, so the agent receives the lot as one prompt.
///
/// A send that fails part-way keeps what already went out. A retry sends
/// only the files that didn't, each under the batch tag it was first given,
/// so the bridge still gathers them into the message the first ones started
/// (the composer's rule, see `StagedAttachment.batchTag`); and the caption
/// is not sent twice.
class ShareSender(private val transport: ShareTransport, private val newBatchID: () -> String = { UUID.randomUUID().toString() }) {
    private val sent = mutableSetOf<String>()
    private val tags = mutableMapOf<String, AttachmentBatchTag>()
    private var messageSent = false

    /// True once anything has gone out: from then on the set of files is
    /// fixed, since the batch the first ones joined counts on the rest.
    val hasSentAnything: Boolean get() = sent.isNotEmpty() || messageSent

    suspend fun send(convoID: String, message: String, files: List<SharedFile>, progress: (ShareProgress) -> Unit) {
        val text = message.trim().takeUnless { messageSent }.orEmpty()
        val pending = files.filter { it.id !in sent }
        if (pending.isEmpty() && text.isEmpty()) return
        progress(ShareProgress(ShareProgress.Step.CONNECTING, fileCount = files.size))
        transport.connect()
        if (files.isEmpty()) {
            progress(ShareProgress(ShareProgress.Step.POSTING, fraction = 0.95))
            transport.sendText(convoID, text)
            messageSent = true
        } else {
            if (tags.isEmpty() && files.size > 1) {
                val batch = newBatchID()
                files.forEachIndexed { index, file ->
                    tags[file.id] = AttachmentBatchTag(id = batch, index = index + 1, total = files.size)
                }
            }
            val total = pending.sumOf { it.sizeBytes }.coerceAtLeast(1)
            var done = 0L
            for (file in pending) {
                val index = files.indexOf(file) + 1
                val before = done
                val report: (Double) -> Unit = { fraction ->
                    val bytes = before + fraction.coerceIn(0.0, 1.0) * file.sizeBytes
                    progress(
                        ShareProgress(
                            ShareProgress.Step.UPLOADING, fileIndex = index, fileCount = files.size,
                            filename = file.filename, fraction = 0.95 * (bytes / total).coerceAtMost(1.0),
                        ),
                    )
                }
                report(0.0)
                // The message rides on the first file that goes, so a retry
                // after a failed first file still carries it.
                val caption = text.takeIf { it.isNotEmpty() && !messageSent }
                transport.sendFile(convoID, file, caption, tags[file.id], report)
                sent += file.id
                if (caption != null) messageSent = true
                done += file.sizeBytes
                report(1.0)
            }
        }
        progress(ShareProgress(ShareProgress.Step.POSTING, fileCount = files.size, fraction = 0.97))
        transport.finish()
        progress(ShareProgress(ShareProgress.Step.POSTING, fileCount = files.size, fraction = 1.0))
    }
}
