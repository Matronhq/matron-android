package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentSaveRequestTest {
    private val byExtension: (String) -> String = { ext ->
        when (ext.lowercase()) {
            "pdf" -> "application/pdf"
            "csv" -> "text/csv"
            else -> "application/octet-stream"
        }
    }

    @Test
    fun keepsTheFilesOwnNameAndMime() {
        assertEquals(
            ImageDownload.Request("report.pdf", "application/pdf"),
            attachmentSaveRequest("report.pdf", "application/pdf", byExtension),
        )
    }

    @Test
    fun fallsBackToTheExtensionWhenTheMimeIsMissingOrJunk() {
        assertEquals("text/csv", attachmentSaveRequest("data.csv", null, byExtension).mimeType)
        assertEquals("text/csv", attachmentSaveRequest("data.csv", " ", byExtension).mimeType)
        assertEquals("application/octet-stream", attachmentSaveRequest("notes", null, byExtension).mimeType)
    }

    @Test
    fun dropsAnyPathInTheName() {
        assertEquals("x.pdf", attachmentSaveRequest("../../x.pdf", null, byExtension).filename)
        assertEquals("x.pdf", attachmentSaveRequest("dir\\x.pdf", null, byExtension).filename)
    }

    @Test
    fun namesAnUnnamedFile() {
        assertEquals("attachment", attachmentSaveRequest("", "application/pdf", byExtension).filename)
        assertEquals("attachment", attachmentSaveRequest("a/..", null, byExtension).filename)
        assertEquals("attachment", attachmentSaveRequest("  ", null, byExtension).filename)
    }
}
