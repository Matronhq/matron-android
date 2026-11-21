package chat.matron.android.designsystem

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An animated GIF plays inline (chat rows and item threads share [AttachmentImage])
 * and in the full-screen viewer. The test draws its own GIF, so no image file
 * is added to the repository; no server or account required.
 */
@RunWith(AndroidJUnit4::class)
class AnimatedImageScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private fun show(content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(MatronAppearance.Light) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                .systemBarsPadding().padding(16.dp),
                        ) { content() }
                    }
                }
            }
        }

    /// Samples the pixel at ([x], [y]) for about two seconds and returns the
    /// distinct colours seen, saving the first screenshot of each new colour
    /// when screenshots are on.
    private fun coloursAt(x: Int, y: Int, name: String): Set<Int> {
        val seen = linkedSetOf<Int>()
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            val shot: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: continue
            val colour = shot.getPixel(x, y)
            if (seen.add(colour)) save(shot, "$name-frame${seen.size}")
            shot.recycle()
            Thread.sleep(80)
        }
        return seen
    }

    private fun save(bitmap: Bitmap, name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "animated-image").apply { mkdirs() }
        FileOutputStream(File(directory, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun gifPlaysInline() {
        show { AttachmentImage(model = testGif(), caption = "A three-frame GIF") }.use {
            val image = checkNotNull(device.wait(Until.findObject(By.desc("A three-frame GIF")), 5_000))
            Thread.sleep(500)
            val b = image.visibleBounds
            val colours = coloursAt(b.centerX(), b.centerY(), "inline")
            assertTrue("expected the frames to change, saw ${colours.size} colour(s)", colours.size >= 2)
        }
    }

    @Test
    fun gifPlaysInTheViewer() {
        show { AttachmentFullscreenViewer(model = testGif(), onDismiss = {}) }.use {
            checkNotNull(device.wait(Until.findObject(By.desc("Image preview")), 5_000))
            Thread.sleep(500)
            val colours = coloursAt(device.displayWidth / 2, device.displayHeight / 2, "viewer")
            assertTrue("expected the frames to change, saw ${colours.size} colour(s)", colours.size >= 2)
        }
    }

    /// A 96×96 GIF: three solid frames (teal, orange, purple), 300 ms each,
    /// looping forever. Written by hand: four-colour palette, and the LZW
    /// stream resets its table every two pixels so every code stays 3 bits
    /// wide and no compression is needed.
    private fun testGif(side: Int = 96): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("GIF89a".toByteArray())
        u16(side); u16(side)
        out.write(0xF1) // global colour table, 4 entries
        out.write(0); out.write(0)
        listOf(0x0E7C86, 0xF28C28, 0x7A3FC2, 0xFFFFFF).forEach { rgb ->
            out.write(rgb shr 16 and 0xFF); out.write(rgb shr 8 and 0xFF); out.write(rgb and 0xFF)
        }
        // Loop forever.
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
        for (frame in 0 until 3) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00)); u16(30); out.write(0); out.write(0)
            out.write(0x2C); u16(0); u16(0); u16(side); u16(side); out.write(0)
            out.write(2) // LZW minimum code size
            val data = lzw(frame, side * side)
            var i = 0
            while (i < data.size) {
                val n = minOf(255, data.size - i)
                out.write(n); out.write(data, i, n); i += n
            }
            out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun lzw(index: Int, pixels: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        fun code(c: Int) {
            buffer = buffer or (c shl bits); bits += 3
            while (bits >= 8) { bytes.write(buffer and 0xFF); buffer = buffer ushr 8; bits -= 8 }
        }
        var p = 0
        while (p < pixels) {
            code(4) // clear
            code(index); p++
            if (p < pixels) { code(index); p++ }
        }
        code(5) // end
        if (bits > 0) bytes.write(buffer and 0xFF)
        return bytes.toByteArray()
    }
}
