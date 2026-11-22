package chat.matron.android.features.share

import chat.matron.android.models.AttachmentBatchTag
import java.io.File
import java.nio.file.Files

/// Records what a share sent, and fails the sends it is told to.
class FakeShareTransport : ShareTransport {
    data class Sent(val convoID: String, val name: String?, val text: String?, val batch: AttachmentBatchTag?)

    val sent = mutableListOf<Sent>()
    var connects = 0
    var finishes = 0
    var releases = 0
    var connectError: Throwable? = null
    /// Names of files whose next send fails (once each).
    val failOnce = mutableSetOf<String>()

    override suspend fun connect() {
        connects++
        connectError?.let { throw it }
    }

    override suspend fun sendText(convoID: String, body: String) {
        sent += Sent(convoID, null, body, null)
    }

    override suspend fun sendFile(
        convoID: String, file: SharedFile, caption: String?, batch: AttachmentBatchTag?, progress: (Double) -> Unit,
    ) {
        if (failOnce.remove(file.filename)) throw IllegalStateException("Upload failed")
        progress(0.5)
        sent += Sent(convoID, file.filename, caption, batch)
    }

    override suspend fun finish() {
        finishes++
    }

    override fun release() {
        releases++
    }
}

fun sharedFile(name: String, size: Long = 100, mime: String = "image/png"): SharedFile {
    val dir = Files.createTempDirectory("shared").toFile()
    val file = File(dir, name).apply { writeBytes(ByteArray(size.toInt())) }
    return SharedFile(id = "id-$name", file = file, filename = name, mimeType = mime, sizeBytes = size)
}
