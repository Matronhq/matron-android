package chat.matron.android.viewmodels

import java.io.File
import java.util.UUID

/// Turns a pasted clipboard item into a temporary file the composer can attach.
/// Ported from matron-apple's `PastedAttachment`.
///
/// Platform adaptation: iOS/macOS classify an `NSItemProvider` by `UTType`
/// conformance; Android clipboard items carry MIME types plus an optional
/// content/file URI. The port therefore classifies over a MIME-type + file-URI
/// model exposed by [PastedItem]. The classification *order and intent* are
/// preserved faithfully; the real `ClipData` → [PastedItem] adapter is wired in
/// the UI stage. The pure, load-bearing logic (classify, filename, staging) is
/// JVM-testable through an injectable [PastedItem].
object PastedAttachment {
    /// What a pasted item should become.
    sealed interface Kind {
        /// A file to attach, carried inline under this MIME type identifier.
        data class Attachment(val typeIdentifier: String) : Kind

        /// A file to attach, carried as a content/file URI reference.
        data object FileReference : Kind

        /// Not ours — the text field should paste it itself.
        data object Text : Kind
    }

    /// Decides whether a pasted item is an attachment or text. The order matters
    /// (mirrors the Swift original):
    ///
    /// 1. A file/content URI wins outright — a file copied out of a files app
    ///    also advertises a plain-text rendering of its contents; pasting the
    ///    contents when the user copied the *file* would be wrong.
    /// 2. Then images, so a photo attaches even when an HTML flavour rides along.
    /// 3. Then any text flavour wins — styled text (`text/html`, `text/rtf`)
    ///    pastes as text rather than arriving as a mystery `.html` attachment.
    /// 4. Only then does other data — a PDF, a zip — count as a file.
    fun classify(item: PastedItem): Kind {
        val identifiers = item.typeIdentifiers
        if (item.hasFileReference) return Kind.FileReference
        val image = identifiers.firstOrNull { conformsToImage(it) }
        if (image != null) return Kind.Attachment(image)
        if (identifiers.any { conformsToText(it) }) return Kind.Text
        val file = identifiers.firstOrNull { conformsToData(it) }
        return file?.let { Kind.Attachment(it) } ?: Kind.Text
    }

    /// Materialises a pasted attachment into a file inside [stagingDirectory].
    /// Throws [PastedAttachmentError.NotAnAttachment] for text items — callers
    /// are expected to have consulted [classify] first; this is a backstop.
    /// Throws [PastedAttachmentError.UnreadableItem] when the item delivers no
    /// bytes.
    suspend fun stage(item: PastedItem, stagingDirectory: File): File {
        return when (val kind = classify(item)) {
            Kind.Text -> throw PastedAttachmentError.NotAnAttachment
            Kind.FileReference -> {
                val ref = item.loadFileReference() ?: throw PastedAttachmentError.UnreadableItem
                val destination = stagingURL(ref.filename, stagingDirectory)
                destination.writeBytes(ref.bytes)
                destination
            }
            is Kind.Attachment -> {
                val bytes = item.loadData(kind.typeIdentifier)
                    ?: throw PastedAttachmentError.UnreadableItem
                val name = filename(item.suggestedName, kind.typeIdentifier)
                val destination = stagingURL(name, stagingDirectory)
                destination.writeBytes(bytes)
                destination
            }
        }
    }

    /// Builds a unique staging file for a pasted item. The `UUID` prefix is what
    /// keeps two pastes of the same filename from clobbering each other before
    /// the composer has read the first.
    fun stagingURL(forName: String, directory: File): File =
        File(directory, "${UUID.randomUUID()}-$forName")

    /// Names a staged inline item. The extension is load-bearing — the composer
    /// derives the MIME type from it, which decides `sendImage` vs `sendFile` —
    /// so it always comes from the type identifier itself. A pasted photo carries
    /// no suggested name, hence the fallback.
    fun filename(suggestedName: String?, typeIdentifier: String): String {
        val base = suggestedName?.let { File(it).nameWithoutExtension.ifEmpty { it } } ?: "pasted-file"
        val ext = preferredExtension(typeIdentifier) ?: return base
        return "$base.$ext"
    }

    private fun conformsToImage(mime: String): Boolean = mime.startsWith("image/")

    /// `text/*` covers plain, HTML and RTF flavours — all of which the Swift
    /// original folds under `public.text` so they paste as text.
    private fun conformsToText(mime: String): Boolean = mime.startsWith("text/")

    /// Every remaining MIME type is "data" on Android (there is no separate
    /// `public.url` MIME — plain URLs arrive as `text/*` and are handled by the
    /// text rule; file URIs are handled by [PastedItem.hasFileReference]).
    private fun conformsToData(mime: String): Boolean = mime.isNotEmpty()

    /// Best-effort MIME → filename-extension mapping without an Android runtime
    /// (`MimeTypeMap` returns null off-device). Handles the common `type/subtype`
    /// shapes; the subtype (minus any `x-`/`vnd.` prefix and parameters) is the
    /// extension for the cases the composer cares about (png, jpeg, pdf, …).
    private fun preferredExtension(mime: String): String? {
        val subtype = mime.substringAfter('/', "").substringBefore(';').trim()
        if (subtype.isEmpty()) return null
        val cleaned = subtype.removePrefix("x-").substringAfterLast('.')
        return cleaned.ifEmpty { null }
    }
}

/// The slice of a pasted clipboard item [PastedAttachment] needs. Injectable so
/// classification is JVM-testable without a real Android clipboard; the UI stage
/// provides a `ClipData.Item`-backed implementation.
interface PastedItem {
    /// MIME types the item advertises (Android `ClipDescription` MIME types), the
    /// analogue of `NSItemProvider.registeredTypeIdentifiers`.
    val typeIdentifiers: List<String>

    /// True when the item carries a content/file URI reference — the analogue of
    /// a `public.file-url` representation.
    val hasFileReference: Boolean

    /// Suggested filename, if the platform provided one. `null` for e.g. a pasted
    /// photo.
    val suggestedName: String?

    /// Resolves the file-reference bytes (only called when [hasFileReference]).
    /// `null` when the reference can't be read.
    suspend fun loadFileReference(): PastedFile?

    /// Loads the inline bytes for [typeIdentifier]. `null` when unreadable.
    suspend fun loadData(typeIdentifier: String): ByteArray?
}

/// Bytes plus the source's filename, resolved from a file-URI paste.
data class PastedFile(val filename: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PastedFile) return false
        return filename == other.filename && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * filename.hashCode() + bytes.contentHashCode()
}

/// Paste-staging failures, surfaced through the composer's `sendError` banner.
sealed class PastedAttachmentError(override val message: String) : Exception(message) {
    /// The item is text — the text field pastes those itself.
    data object NotAnAttachment :
        PastedAttachmentError("That doesn't look like a file we can attach.")

    /// The provider delivered neither a file nor readable bytes.
    data object UnreadableItem : PastedAttachmentError("Couldn't read the pasted item.")
}
