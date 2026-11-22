package chat.matron.android.designsystem

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Save for a file attachment: fetches the original bytes, then asks the
/// system "Save to" picker (`ACTION_CREATE_DOCUMENT`) where to put them,
/// under the file's own name. The file-chip counterpart of the image
/// viewer's Download ([ImageDownload]); no storage permission is needed.
/// Tapping the chip itself still opens the file as before.
@Stable
class AttachmentSaver internal constructor(
    private val start: (key: String, name: String, mime: String?, fetch: suspend () -> ByteArray?) -> Unit,
    private val busy: () -> String?,
) {
    /// Fetches the attachment identified by [key] with [fetch] and offers to
    /// save it as [name]. A second tap while one save is still fetching is
    /// ignored.
    fun save(key: String, name: String, mime: String?, fetch: suspend () -> ByteArray?) = start(key, name, mime, fetch)

    /// Whether the attachment [key] is being fetched for a save: its chip
    /// shows the downloading state meanwhile.
    fun isSaving(key: String): Boolean = busy() == key
}

/// The save request for a file attachment: the sender's [name] with any path
/// dropped (`../x.pdf` is suggested as `x.pdf`), `attachment` when there is
/// none left, and the attachment's own [mime] when it has one, else the type
/// its extension implies.
fun attachmentSaveRequest(name: String, mime: String?, typeForExtension: (String) -> String): ImageDownload.Request {
    val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
    val filename = if (base.isEmpty() || base == "." || base == "..") "attachment" else base
    val type = mime?.trim()?.takeIf { it.contains('/') } ?: typeForExtension(filename.substringAfterLast('.', ""))
    return ImageDownload.Request(filename, type)
}

@Composable
fun rememberAttachmentSaver(typeForExtension: (String) -> String): AttachmentSaver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The bytes waiting for the picker to name a destination, and which
    // attachment is being fetched for a save.
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var savingKey by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ImageDownload.SaveContract) { uri ->
        val bytes = pending
        pending = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) "File saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }
    val latestType = rememberUpdatedState(typeForExtension)
    return remember(launcher) {
        AttachmentSaver(
            start = { key, name, mime, fetch ->
                if (savingKey == null) {
                    savingKey = key
                    scope.launch {
                        val bytes = try {
                            fetch()
                        } catch (e: CancellationException) {
                            // The host left the screen: not a failed download.
                            throw e
                        } catch (e: Exception) {
                            null
                        } finally {
                            savingKey = null
                        }
                        if (bytes == null) {
                            Toast.makeText(context, "Couldn't download the file", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        pending = bytes
                        launcher.launch(attachmentSaveRequest(name, mime, latestType.value))
                    }
                }
            },
            busy = { savingKey },
        )
    }
}
