package chat.matron.android.viewmodels

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `PastedAttachmentTests`. The `NSItemProvider`
/// currency is replaced by a [PastedItem] fake over Android's MIME-type +
/// file-URI model (see `PastedAttachment` doc for the adaptation). The
/// load-bearing classification rules and the staging round-trip are preserved.
class PastedAttachmentTest {

    private class FakePastedItem(
        override val typeIdentifiers: List<String>,
        override val hasFileReference: Boolean = false,
        override val suggestedName: String? = null,
        private val fileRef: PastedFile? = null,
        private val data: Map<String, ByteArray> = emptyMap(),
    ) : PastedItem {
        override suspend fun loadFileReference(): PastedFile? = fileRef
        override suspend fun loadData(typeIdentifier: String): ByteArray? = data[typeIdentifier]
    }

    private fun stagingDir(): File = Files.createTempDirectory("paste").toFile()

    // MARK: - classify

    @Test
    fun classify_plainText_isText() {
        val item = FakePastedItem(listOf("text/plain"))
        assertEquals(PastedAttachment.Kind.Text, PastedAttachment.classify(item))
    }

    @Test
    fun classify_image_isAttachment() {
        val item = FakePastedItem(listOf("image/png"))
        assertEquals(
            PastedAttachment.Kind.Attachment("image/png"),
            PastedAttachment.classify(item),
        )
    }

    @Test
    fun classify_fileReference_isFileReference() {
        val item = FakePastedItem(typeIdentifiers = emptyList(), hasFileReference = true)
        assertEquals(PastedAttachment.Kind.FileReference, PastedAttachment.classify(item))
    }

    @Test
    fun classify_fileReferenceWithTextRendering_prefersTheFile() {
        // A files-app copy advertises the file URI *and* a plain-text rendering.
        // "Paste the file" is what the user meant, so the URI wins.
        val item = FakePastedItem(listOf("text/plain"), hasFileReference = true)
        assertEquals(PastedAttachment.Kind.FileReference, PastedAttachment.classify(item))
    }

    @Test
    fun classify_styledText_isText() {
        // HTML alongside plain text pastes as text, never as a .html attachment.
        val item = FakePastedItem(listOf("text/html", "text/plain"))
        assertEquals(PastedAttachment.Kind.Text, PastedAttachment.classify(item))
    }

    @Test
    fun classify_opaqueBlobAlongsideText_isText() {
        // A web copy can carry an opaque non-text/non-image blob (the webarchive
        // analogue). The "any text flavour present wins" rule stops it being
        // attached as a mystery file.
        val item = FakePastedItem(listOf("application/x-webarchive", "text/plain"))
        assertEquals(PastedAttachment.Kind.Text, PastedAttachment.classify(item))
    }

    @Test
    fun classify_nonTextData_isAttachment() {
        val item = FakePastedItem(listOf("application/pdf"))
        assertEquals(
            PastedAttachment.Kind.Attachment("application/pdf"),
            PastedAttachment.classify(item),
        )
    }

    // MARK: - stage

    @Test
    fun stage_image_writesFileKeepingBytesAndExtension() = runBlocking {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val item = FakePastedItem(listOf("image/png"), data = mapOf("image/png" to bytes))

        val staged = PastedAttachment.stage(item, stagingDir())

        assertEquals("png", staged.extension)
        assertTrue(staged.readBytes().contentEquals(bytes))
    }

    @Test
    fun stage_fileReference_copiesBytesAndKeepsFilename() = runBlocking {
        val bytes = "%PDF-1.4".toByteArray()
        val item = FakePastedItem(
            typeIdentifiers = emptyList(),
            hasFileReference = true,
            fileRef = PastedFile("report.pdf", bytes),
        )

        val staged = PastedAttachment.stage(item, stagingDir())

        assertTrue(staged.name, staged.name.endsWith("report.pdf"))
        assertTrue(staged.readBytes().contentEquals(bytes))
    }

    @Test
    fun stage_text_throwsNotAnAttachment() = runBlocking {
        val item = FakePastedItem(listOf("text/plain"))
        val error = runCatching { PastedAttachment.stage(item, stagingDir()) }.exceptionOrNull()
        assertEquals(PastedAttachmentError.NotAnAttachment, error)
    }

    @Test
    fun stagingURL_isUniquePerCall() {
        val dir = stagingDir()
        val first = PastedAttachment.stagingURL("photo.png", dir)
        val second = PastedAttachment.stagingURL("photo.png", dir)
        assertNotEquals(first, second)
        assertTrue(first.name.endsWith("-photo.png"))
    }
}
