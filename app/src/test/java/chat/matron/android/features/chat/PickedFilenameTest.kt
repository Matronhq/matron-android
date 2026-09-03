package chat.matron.android.features.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// `pickedFilename` (apple #160): a picked item keeps its own extension, and
/// one that arrives without any gets the extension its declared MIME type
/// implies — never left untyped or relabelled as an image.
class PickedFilenameTest {
    @Test
    fun displayNameWithExtensionWins() {
        assertEquals("Screen Recording.mov", pickedFilename("Screen Recording.mov", "mp4"))
    }

    @Test
    fun missingExtensionComesFromDeclaredType() {
        assertEquals("Screen Recording.mp4", pickedFilename("Screen Recording", "mp4"))
    }

    @Test
    fun missingNameIsGeneratedWithDeclaredType() {
        val name = pickedFilename(null, "png")
        assertTrue(name.startsWith("picked-"))
        assertTrue(name.endsWith(".png"))
    }

    @Test
    fun nothingKnownStaysBare() {
        assertEquals("Screen Recording", pickedFilename("Screen Recording", null))
    }
}
