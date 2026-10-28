package chat.matron.android.designsystem

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import java.io.File

/// Download from the fullscreen image viewer: saves the original bytes (not
/// the decoded, screen-sized bitmap) where the user picks, through the system
/// "Save to" picker. No storage permission is needed, and it is the Android
/// counterpart of the web viewer's Download link and the Mac Save panel.
object ImageDownload {
    /// What the save picker is asked for: the suggested file name and the
    /// MIME type it files the document under.
    data class Request(val filename: String, val mimeType: String)

    /// The image's MIME type from its leading bytes. Attachments arrive as
    /// raw blobs (the journal serves them untyped), so the bytes are the only
    /// reliable answer; `null` for anything unrecognised.
    fun mimeType(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        fun ascii(from: Int, text: String) =
            text.indices.all { at(from + it) == text[it].code }
        return when {
            at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "BM") -> "image/bmp"
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1")) -> "image/heic"
            ascii(4, "ftyp") && ascii(8, "avif") -> "image/avif"
            else -> null
        }
    }

    private val extensions = mapOf(
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "image/heic" to "heic",
        "image/avif" to "avif",
    )

    /// The save request for [bytes]: the sender's [name] when there is one
    /// (given the right extension if it has none), else `image.<ext>`. A path
    /// in the name is dropped, so `../x.png` is suggested as `x.png`.
    fun request(name: String?, bytes: ByteArray): Request {
        val mime = mimeType(bytes)
        val ext = mime?.let(extensions::get)
        val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        val filename = when {
            base.isEmpty() || base == "." || base == ".." -> if (ext != null) "image.$ext" else "image"
            ext != null && !base.contains('.') -> "$base.$ext"
            else -> base
        }
        return Request(filename, mime ?: "application/octet-stream")
    }

    /// The original bytes behind a viewer model: fetched bytes as they are,
    /// a local `File`/`Uri` read back, and a URL string fetched through
    /// [load] (a call site whose inline load hasn't landed yet). `null` when
    /// there is nothing to save.
    suspend fun originalBytes(model: Any?, context: Context, load: suspend (String) -> Any?): ByteArray? =
        when (model) {
            is ByteArray -> model
            is File -> runCatching { model.readBytes() }.getOrNull()
            is Uri -> runCatching { context.contentResolver.openInputStream(model)?.use { it.readBytes() } }.getOrNull()
            is String -> originalBytes(load(model).takeUnless { it is String }, context, load)
            else -> null
        }

    /// `ACTION_CREATE_DOCUMENT` with a per-launch MIME type and suggested
    /// name (the stock `CreateDocument` contract fixes the type up front).
    object SaveContract : ActivityResultContract<Request, Uri?>() {
        override fun createIntent(context: Context, input: Request): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.mimeType)
                .putExtra(Intent.EXTRA_TITLE, input.filename)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
    }
}
