package chat.matron.android.features.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntentContentTest {
    private val own = "chat.matron.android"
    private val photo = Uri.parse("content://com.example.photos/media/1")
    private val doc = Uri.parse("content://com.example.docs/files/report.pdf")

    @Test
    fun sendTextBecomesTheMessage() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "  https://example.com/article  ")
            .putExtra(Intent.EXTRA_SUBJECT, "An article")
        val content = ShareIntentContent.from(intent, own)
        assertEquals("https://example.com/article", content.text)
        assertTrue(content.uris.isEmpty())
    }

    @Test
    fun aSubjectAloneIsTheMessage() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "A page title")
        assertEquals("A page title", ShareIntentContent.from(intent, own).text)
    }

    @Test
    fun sendStreamIsAFileWithItsCaption() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, photo)
            .putExtra(Intent.EXTRA_TEXT, "Look at this")
        val content = ShareIntentContent.from(intent, own)
        assertEquals(listOf(photo), content.uris)
        assertEquals("Look at this", content.text)
    }

    @Test
    fun sendMultipleTakesEveryStreamOnce() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, doc, photo))
        assertEquals(listOf(photo, doc), ShareIntentContent.from(intent, own).uris)
    }

    @Test
    fun streamsOnlyInTheClipAreTaken() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/png")
        intent.clipData = ClipData.newRawUri("", photo)
        assertEquals(listOf(photo), ShareIntentContent.from(intent, own).uris)
    }

    @Test
    fun fileUrisAndThisAppsOwnFilesAreRefused() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                arrayListOf(
                    Uri.parse("file:///data/data/chat.matron.android/files/secret.sqlite"),
                    Uri.parse("content://chat.matron.android.fileprovider/attachments/a.png"),
                    doc,
                ),
            )
        assertEquals(listOf(doc), ShareIntentContent.from(intent, own).uris)
    }

    @Test
    fun aDirectShareTargetNamesTheConversation() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "hi")
            .putExtra(Intent.EXTRA_SHORTCUT_ID, ShareShortcuts.shortcutID("c7"))
        assertEquals("c7", ShareIntentContent.from(intent, own).conversationID)
        val fromShortcutIntent = Intent(Intent.ACTION_SEND).putExtra(ShareShortcuts.EXTRA_CONVERSATION_ID, "c8")
        assertEquals("c8", ShareIntentContent.from(fromShortcutIntent, own).conversationID)
        assertNull(ShareIntentContent.from(Intent(Intent.ACTION_SEND), own).conversationID)
    }

    @Test
    fun anyOtherActionCarriesNothing() {
        val content = ShareIntentContent.from(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "x"), own)
        assertTrue(content.isEmpty)
    }
}
