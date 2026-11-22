package chat.matron.android.designsystem

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import chat.matron.android.viewmodels.PastedAttachment
import chat.matron.android.viewmodels.PastedFile
import chat.matron.android.viewmodels.PastedItem
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/// Pasting a copied file or image into a composer attaches it.
///
/// A Compose text field pastes only text: it offers Paste when the clipboard
/// has text and reads the text when Paste is chosen (the toolbar, or Ctrl+V on
/// an attached keyboard). [AttachmentPasteTarget] gives the field a clipboard
/// that also answers "yes" when the clip is a file, and on Paste hands the
/// file to the composer instead of returning text. Text clips paste exactly
/// as before.
///
/// Which clips count as files is [PastedAttachment.classify]: a content URI
/// wins (a file copied in a files app also carries a text rendering), then
/// images; plain or styled text stays text.
object AttachmentPaste {
    /// The content URIs in [clip] that should attach rather than paste as
    /// text, in clip order. Empty for a text clip.
    ///
    /// Only `content:` URIs from other apps are accepted. A `file:` URI or a
    /// URI served by this app's own provider ([ownPackage]) could name one of
    /// the app's private files: a clip another app planted must not be able
    /// to attach those.
    fun attachmentUris(clip: ClipData?, ownPackage: String): List<Uri> {
        clip ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { index ->
            val item = ClipDataPastedItem(clip.description, clip.getItemAt(index).uri)
            val uri = item.uri ?: return@mapNotNull null
            if (PastedAttachment.classify(item) == PastedAttachment.Kind.Text) return@mapNotNull null
            if (!uri.scheme.equals("content", ignoreCase = true)) return@mapNotNull null
            val authority = uri.authority ?: return@mapNotNull null
            if (authority == ownPackage || authority.startsWith("$ownPackage.")) return@mapNotNull null
            uri
        }
    }

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

    /// The name for a pasted item whose source gives none (a screenshot
    /// copied from a gallery app often has no display name): when it was
    /// pasted, so two pastes are told apart. The extension is added from the
    /// item's type by the copy.
    fun fallbackName(mimeType: String?, now: LocalDateTime = LocalDateTime.now()): String {
        val kind = if (mimeType?.startsWith("image/") == true) "pasted-image" else "pasted-file"
        return "$kind-${stamp.format(now)}"
    }
}

/// One clipboard item as [PastedAttachment] sees it. Android clip items carry
/// text, HTML, an intent or a URI, never inline bytes, so a file always
/// arrives by URI; the copy itself streams from the URI ([PastedAttachment]'s
/// in-memory staging is not used).
internal class ClipDataPastedItem(description: ClipDescription?, val uri: Uri?) : PastedItem {
    override val typeIdentifiers: List<String> =
        description?.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) } }.orEmpty()

    override val hasFileReference: Boolean =
        uri?.scheme?.lowercase().let { it == "content" || it == "file" }

    override val suggestedName: String? = null

    override suspend fun loadFileReference(): PastedFile? = null

    override suspend fun loadData(typeIdentifier: String): ByteArray? = null
}

/// The clipboard [AttachmentPasteTarget] installs: the platform one, except
/// that a file clip counts as pasteable and pasting it calls [onPaste]
/// instead of inserting text.
internal class AttachmentPasteClipboard(
    private val base: ClipboardManager,
    private val attachmentUris: () -> List<Uri>,
    private val onPaste: (List<Uri>) -> Unit,
) : ClipboardManager by base {
    override fun hasText(): Boolean = base.hasText() || attachmentUris().isNotEmpty()

    override fun getText(): AnnotatedString? {
        val uris = attachmentUris()
        if (uris.isEmpty()) return base.getText()
        onPaste(uris)
        // Nothing for the field to insert: the file went to the tray. Empty
        // text rather than null, so the field finishes the paste as usual
        // (closes its toolbar; a selection is replaced, as any paste does).
        return AnnotatedString("")
    }
}

/// Lets the text field in [content] paste copied files and images into the
/// composer's tray through [onPasteAttachments]. `null` leaves the field's
/// paste as it was.
@Composable
fun AttachmentPasteTarget(onPasteAttachments: ((List<Uri>) -> Unit)?, content: @Composable () -> Unit) {
    if (onPasteAttachments == null) {
        content()
        return
    }
    val base = LocalClipboardManager.current
    val packageName = LocalContext.current.packageName
    val latest by rememberUpdatedState(onPasteAttachments)
    val clipboard = remember(base, packageName) {
        AttachmentPasteClipboard(
            base = base,
            attachmentUris = {
                val clip = runCatching { base.nativeClipboard.primaryClip }.getOrNull()
                AttachmentPaste.attachmentUris(clip, packageName)
            },
            onPaste = { latest(it) },
        )
    }
    CompositionLocalProvider(LocalClipboardManager provides clipboard, content = content)
}
