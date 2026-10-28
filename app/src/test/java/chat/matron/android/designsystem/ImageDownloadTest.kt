package chat.matron.android.designsystem

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ImageDownloadTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun mimeType_readsTheLeadingBytes() {
        assertEquals("image/png", ImageDownload.mimeType(png))
        assertEquals("image/jpeg", ImageDownload.mimeType(jpeg))
        assertEquals("image/gif", ImageDownload.mimeType(ascii("GIF89a")))
        assertEquals("image/webp", ImageDownload.mimeType(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals("image/heic", ImageDownload.mimeType(ascii("\u0000\u0000\u0000\u0018ftypheic")))
        assertNull(ImageDownload.mimeType(ascii("hello")))
        assertNull(ImageDownload.mimeType(ByteArray(0)))
    }

    @Test
    fun request_keepsTheSendersName() {
        assertEquals(ImageDownload.Request("Cover proof.png", "image/png"), ImageDownload.request("Cover proof.png", png))
    }

    @Test
    fun request_addsTheExtensionToANameWithout() {
        assertEquals("screenshot.jpg", ImageDownload.request("screenshot", jpeg).filename)
    }

    @Test
    fun request_fallsBackToImageDotExtension() {
        assertEquals("image.png", ImageDownload.request(null, png).filename)
        assertEquals("image.jpg", ImageDownload.request("  ", jpeg).filename)
        assertEquals(ImageDownload.Request("image", "application/octet-stream"), ImageDownload.request(null, ascii("hello")))
    }

    @Test
    fun request_dropsAnyPathInTheName() {
        assertEquals("x.png", ImageDownload.request("../../x.png", png).filename)
        assertEquals("y.png", ImageDownload.request("sub\\y.png", png).filename)
        assertEquals("image.png", ImageDownload.request("..", png).filename)
    }

    @Test
    fun originalBytes_bytesAsTheyAre_andAUrlThroughLoad() = runTest {
        val context = RuntimeEnvironment.getApplication()
        assertArrayEquals(png, ImageDownload.originalBytes(png, context) { null })
        assertArrayEquals(jpeg, ImageDownload.originalBytes("https://example.com/media/a", context) { jpeg })
        assertNull(ImageDownload.originalBytes("https://example.com/media/a", context) { null })
        assertNull(ImageDownload.originalBytes(null, context) { png })
    }

    @Test
    fun originalBytes_readsALocalFile() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val file = kotlin.io.path.createTempFile().toFile().apply { writeBytes(png); deleteOnExit() }
        assertArrayEquals(png, ImageDownload.originalBytes(file, context) { null })
    }
}
