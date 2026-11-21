package chat.matron.android.designsystem

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.features.chat.copyUriToTemp
import java.io.File
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentPasteTest {
    private val own = "chat.matron.android"
    private val image = Uri.parse("content://com.example.gallery/images/42")
    private val pdf = Uri.parse("content://com.example.files/docs/7")

    private fun clip(mimes: Array<String>, vararg items: ClipData.Item): ClipData {
        val data = ClipData(ClipDescription("copied", mimes), items.first())
        items.drop(1).forEach { data.addItem(it) }
        return data
    }

    @Test
    fun aCopiedImageOrFileAttaches() {
        assertEquals(listOf(image), AttachmentPaste.attachmentUris(clip(arrayOf("image/png"), ClipData.Item(image)), own))
        assertEquals(
            "a file wins over the text rendering riding along",
            listOf(pdf),
            AttachmentPaste.attachmentUris(clip(arrayOf("text/plain", "application/pdf"), ClipData.Item(pdf)), own),
        )
    }

    @Test
    fun everyFileInAMultiItemClipAttaches() {
        val both = clip(arrayOf("image/png", "application/pdf"), ClipData.Item(image), ClipData.Item(pdf))
        assertEquals(listOf(image, pdf), AttachmentPaste.attachmentUris(both, own))
    }

    @Test
    fun textStaysText() {
        assertTrue(AttachmentPaste.attachmentUris(clip(arrayOf("text/plain"), ClipData.Item("hello")), own).isEmpty())
        assertTrue(AttachmentPaste.attachmentUris(clip(arrayOf("text/html"), ClipData.Item("hi", "<b>hi</b>")), own).isEmpty())
        // A copied link is a raw URI clip: text, not a file.
        val link = clip(arrayOf("text/uri-list"), ClipData.Item(Uri.parse("https://example.com/a.png")))
        assertTrue(AttachmentPaste.attachmentUris(link, own).isEmpty())
        assertTrue(AttachmentPaste.attachmentUris(null, own).isEmpty())
    }

    @Test
    fun neverAttachesTheAppsOwnFiles() {
        val fileUri = clip(arrayOf("image/png"), ClipData.Item(Uri.parse("file:///data/data/$own/files/secret.png")))
        assertTrue(AttachmentPaste.attachmentUris(fileUri, own).isEmpty())
        val ownProvider = clip(arrayOf("image/png"), ClipData.Item(Uri.parse("content://$own.fileprovider/cache/x.png")))
        assertTrue(AttachmentPaste.attachmentUris(ownProvider, own).isEmpty())
    }

    @Test
    fun namelessPastesAreNamedForWhenTheyWerePasted() {
        val at = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        assertEquals("pasted-image-2026-01-02-030405", AttachmentPaste.fallbackName("image/png", at))
        assertEquals("pasted-file-2026-01-02-030405", AttachmentPaste.fallbackName("application/zip", at))
        assertEquals("pasted-file-2026-01-02-030405", AttachmentPaste.fallbackName(null, at))
    }

    private class FakeClipboard(var text: String?) : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) { text = annotatedString.text }
        override fun getText(): AnnotatedString? = text?.let { AnnotatedString(it) }
    }

    @Test
    fun theFieldsClipboardOffersPasteForAFileAndHandsItOver() {
        val pasted = mutableListOf<Uri>()
        var uris = listOf(image)
        val clipboard = AttachmentPasteClipboard(FakeClipboard(null), { uris }, { pasted += it })
        assertTrue("Paste is offered for a file", clipboard.hasText())
        assertEquals("nothing is inserted as text", "", clipboard.getText()?.text)
        assertEquals(listOf(image), pasted)

        uris = emptyList()
        assertFalse(clipboard.hasText())
    }

    @Test
    fun textStillPastesAsText() {
        val pasted = mutableListOf<Uri>()
        val clipboard = AttachmentPasteClipboard(FakeClipboard("hello"), { emptyList() }, { pasted += it })
        assertTrue(clipboard.hasText())
        assertEquals("hello", clipboard.getText()?.text)
        assertTrue(pasted.isEmpty())
    }

    @Test
    fun aCopyKeepsItsNameWithNoRandomPrefix() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val source = File(context.cacheDir, "src-${System.nanoTime()}").apply { writeText("x") }
        val first = copyUriToTemp(context, Uri.fromFile(source)) { "pasted-file-2026-01-02-030405" }!!
        val second = copyUriToTemp(context, Uri.fromFile(source)) { "pasted-file-2026-01-02-030405" }!!
        assertEquals("pasted-file-2026-01-02-030405", first.name)
        assertEquals(first.name, second.name)
        assertNotEquals("two copies of one name do not collide", first.parentFile, second.parentFile)
        assertEquals("x", second.readText())
    }

    @Test
    fun aProvidedNameCannotLeaveTheCopyDirectory() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val source = File(context.cacheDir, "src-${System.nanoTime()}").apply { writeText("x") }
        val copy = copyUriToTemp(context, Uri.fromFile(source)) { "../../escape.txt" }!!
        assertEquals("escape.txt", copy.name)
        assertTrue(copy.parentFile!!.parentFile!!.name == "picked")
    }
}
