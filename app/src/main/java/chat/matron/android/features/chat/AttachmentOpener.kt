package chat.matron.android.features.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/// Hands a downloaded attachment to the system. Platform adaptation of the iOS
/// QuickLook preview (`onPreview(.file(url, filename:))` in ChatView.swift):
/// Android has no in-process universal previewer, so the file goes out through
/// the app's FileProvider as an ACTION_VIEW — falling back to an ACTION_SEND
/// share sheet when no installed app can display the type, so the tap is never
/// a dead end.
internal fun openAttachment(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase())
        ?: "application/octet-stream"
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
