package chat.matron.android.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.material3.Surface
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerItem
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentNameLineTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private val long = TrackerAttachment("b1", "image/png", name = "divider_4867x3071_final_export_for_print_reviewed_v2.png", size = 38_900_000)
    private val short = TrackerAttachment("b2", "image/jpeg", name = "crop-left.png", size = 812_000)
    private val unnamed = TrackerAttachment("b3", "image/png", name = "", size = 2_300_000)

    @Test
    fun splitKeepsTheExtensionAndTheEndOfTheStem() {
        assertEquals("divider_4867x" to "3071.png", splitFilenameForMiddleTruncation("divider_4867x3071.png"))
        assertEquals("" to "a.png", splitFilenameForMiddleTruncation("a.png"))
        assertEquals("README_long_fi" to "name", splitFilenameForMiddleTruncation("README_long_finame"))
        // No usable extension: a dotfile, or a "suffix" too long to be one.
        assertEquals(".bash" to "_rc0", splitFilenameForMiddleTruncation(".bash_rc0"))
        assertEquals("notes.this-is-no" to "t-it", splitFilenameForMiddleTruncation("notes.this-is-not-it"))
    }

    @Test
    fun aNarrowImageKeepsItsSizeAndAMissingSizeIsLeftOut() {
        compose.setContent {
            MatronTheme(appearance = MatronAppearance.Light) {
                Surface {
                    androidx.compose.foundation.layout.Column {
                        AttachmentImage(model = photo(0), filename = long.name, sizeBytes = long.size, modifier = androidx.compose.ui.Modifier.width(60.dp))
                        AttachmentNameLine("no-size.png", null)
                    }
                }
            }
        }
        compose.waitForIdle()
        val size = compose.onNodeWithText(" · 38.9 MB", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val line = compose.onNodeWithContentDescription("${long.name} · 38.9 MB").getUnclippedBoundsInRoot()
        assertTrue("size squeezed to ${size.width}", size.width > 40.dp)
        assertTrue("size ${size.right} spills past line ${line.right}", size.right <= line.right)
        compose.onNodeWithContentDescription("no-size.png").assertExists()
    }

    @Test
    fun lightThread() = thread(MatronAppearance.Light, "light")

    @Test
    fun darkThread() = thread(MatronAppearance.Dark, "dark")

    private fun thread(appearance: MatronAppearance, name: String) {
        val bitmaps = listOf(long, short, unnamed).mapIndexed { i, a -> a.blobRef to photo(i) }.toMap()
        compose.setContent {
            MatronTheme(appearance = appearance) {
                Surface {
                    ItemDetailView(
                        model = ItemDetailModel(
                            item = TrackerItem(
                                "it_1", 1, ItemKind.TASK, title = "Check the divider export", body = "The divider and its crops.",
                                originConvoID = "c1", createdAt = now, attachments = listOf(long, short, unnamed),
                            ),
                            comments = emptyList(), pending = emptyList(),
                            context = ItemContext(null, null, null), availableResolutions = emptyList(), isBusy = false,
                            loadedCommentCount = 0, openableConvoIDs = emptySet(),
                        ),
                        draft = "", onDraftChange = {}, image = { bitmaps[it.blobRef] }, onOpenAttachment = {}, onOpenLink = {},
                        onOpenConversation = {}, onSubmit = {}, onAttach = {}, onVoiceNote = {}, now = now,
                    )
                }
            }
        }
        compose.waitForIdle()

        // Each image has its line; the long name never makes it wider than the image.
        val longLine = compose.onNodeWithContentDescription("${long.name} · 38.9 MB")
        val image = compose.onNodeWithContentDescription(long.name).getUnclippedBoundsInRoot()
        val line = longLine.getUnclippedBoundsInRoot()
        assertTrue("line ${line.width} wider than image ${image.width}", line.width <= image.width)
        compose.onNodeWithContentDescription("crop-left.png · 812.0 kB").assertExists()
        compose.onNodeWithContentDescription("2.3 MB").assertExists()

        shoot("build/reports/thread-image-names-$name.png")

        // A long-press shows the full name.
        longLine.performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("${long.name} · 38.9 MB").fetchSemanticsNodes().isNotEmpty())
    }

    private fun photo(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 420, Bitmap.Config.ARGB_8888)
        val hue = (seed * 97f) % 360f
        val from = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.85f))
        val to = android.graphics.Color.HSVToColor(floatArrayOf((hue + 60f) % 360f, 0.6f, 0.55f))
        Canvas(bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 640f, 420f, from, to, Shader.TileMode.CLAMP) })
        return bitmap
    }

    private fun shoot(path: String) {
        val screenshot = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val output = File(path)
        output.parentFile?.mkdirs()
        output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
