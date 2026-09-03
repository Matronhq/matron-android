package chat.matron.android.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// The middle-truncation behind `UploadProgressBar`'s label (apple #156): a
/// UUID temp name must not widen the chat, and the extension must survive.
class UploadProgressBarTest {
    @Test
    fun shortLabelIsUntouched() {
        assertEquals("shot.png", uploadLabelDisplay("shot.png"))
    }

    @Test
    fun longLabelKeepsHeadAndExtension() {
        val name = "3F2504E0-4F89-11D3-9A0C-0305E82C3301-pasted-file.png"
        val shown = uploadLabelDisplay(name)
        assertTrue(shown.length <= UPLOAD_LABEL_MAX_CHARS)
        assertTrue(shown.startsWith("3F2504E0"))
        assertTrue(shown.endsWith("file.png"))
        assertTrue(shown.contains("\u2026"))
    }

    @Test
    fun exactlyMaxCharsIsUntouched() {
        val name = "a".repeat(UPLOAD_LABEL_MAX_CHARS)
        assertEquals(name, uploadLabelDisplay(name))
    }
}
