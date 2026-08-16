package chat.matron.android.features.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/// Pins the MIME resolution behind the system-viewer open — the Android
/// analogue of apple #143's `FilePreviewSheetTests`, which pin
/// `QuickLookPreview.canPreview` for video/audio/PDF/office and the
/// share-only fallback for unknown binaries. Here "previewable" means "gets a
/// real MIME type" (ACTION_VIEW can route it to a player/viewer) and the
/// fallback is `application/octet-stream` (the share-sheet path). The system
/// resolver is stubbed to `null` so the built-in table's contract is what's
/// under test.
class AttachmentOpenerTest {
    private fun mime(extension: String): String =
        attachmentMimeType(extension, systemResolver = { null })

    /// Ports apple #143 `testVideoIsPreviewable`.
    @Test
    fun videoResolvesToVideoMime() {
        assertEquals("video/mp4", mime("mp4"))
        assertEquals("video/quicktime", mime("mov"))
    }

    /// Ports apple #143 `testAudioIsPreviewable`.
    @Test
    fun audioResolvesToAudioMime() {
        assertEquals("audio/mp4", mime("m4a"))
        assertEquals("audio/mpeg", mime("mp3"))
    }

    /// Ports apple #143 `testPDFIsPreviewable`.
    @Test
    fun pdfResolvesToPdfMime() {
        assertEquals("application/pdf", mime("pdf"))
    }

    /// Ports apple #143 `testOfficeDocumentsArePreviewable`.
    @Test
    fun officeDocumentsResolveToOfficeMimes() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            mime("docx"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            mime("xlsx"),
        )
    }

    /// Ports apple #143 `testUnknownBinaryFallsBackToShare`.
    @Test
    fun unknownBinaryFallsBackToOctetStream() {
        assertEquals("application/octet-stream", mime("matrondat"))
    }

    /// Case-insensitive like the previous inline `.lowercase()` call.
    @Test
    fun extensionCaseIsIgnored() {
        assertEquals("application/pdf", mime("PDF"))
    }

    /// A system resolver hit wins over the table.
    @Test
    fun systemResolverWins() {
        assertEquals(
            "video/whatever",
            attachmentMimeType("mp4", systemResolver = { "video/whatever" }),
        )
    }
}
