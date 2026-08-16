package chat.matron.android.features.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/// Hands a downloaded attachment to the system. Platform adaptation of the iOS
/// QuickLook preview (apple #143's `FilePreviewSheet`): Android has no
/// in-process universal previewer, so the file goes out through the app's
/// FileProvider as an ACTION_VIEW — video/audio land in the system player,
/// PDFs in the PDF viewer, office docs in whatever handles them — falling back
/// to an ACTION_SEND share sheet when no installed app can display the type,
/// so the tap is never a dead end. That two-step (previewable → view,
/// otherwise → share) is the same routing decision `FilePreviewSheet` makes
/// between QuickLook and its ShareLink fallback, with the OS resolver
/// deciding "previewable" instead of `QLPreviewController.canPreview`.
internal fun openAttachment(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mime = attachmentMimeType(file.extension)
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, file.name))
    }
}

/// MIME type for an attachment's file extension: the platform's MimeTypeMap
/// first, then a built-in table for the types apple #143 pins as previewable
/// (video/audio/PDF/office — some devices ship MimeTypeMap databases missing
/// entries, and a wrong `application/octet-stream` here routes a playable
/// video to the share sheet instead of the player). Unknown extensions fall
/// back to `application/octet-stream`, which is the share-sheet path — the
/// analogue of `FilePreviewSheet`'s share-only fallback. [systemResolver] is a
/// test seam: MimeTypeMap is an Android framework static, and the fallback
/// table's contract is pinned by plain JUnit.
internal fun attachmentMimeType(
    extension: String,
    systemResolver: (String) -> String? = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
): String {
    val ext = extension.lowercase()
    return systemResolver(ext) ?: WELL_KNOWN_MIME_TYPES[ext] ?: "application/octet-stream"
}

/// The QuickLook-previewable set apple #143's `FilePreviewSheetTests` pins
/// (video, audio, PDF, office docs), plus the common text/archive/image types
/// agents actually send.
private val WELL_KNOWN_MIME_TYPES: Map<String, String> = mapOf(
    // Video — the system player (QuickLook's video scrubber analogue).
    "mp4" to "video/mp4",
    "m4v" to "video/x-m4v",
    "mov" to "video/quicktime",
    "webm" to "video/webm",
    // Audio.
    "m4a" to "audio/mp4",
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "aac" to "audio/aac",
    "ogg" to "audio/ogg",
    "flac" to "audio/flac",
    // Documents.
    "pdf" to "application/pdf",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "txt" to "text/plain",
    "csv" to "text/csv",
    "json" to "application/json",
    "zip" to "application/zip",
    // Images (an image *file* attachment can arrive as a file event).
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
)
