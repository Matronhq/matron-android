package chat.matron.android.features.items

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.designsystem.AttachmentFullscreenViewer
import chat.matron.android.designsystem.ImageGallery
import chat.matron.android.designsystem.ItemContext
import chat.matron.android.designsystem.ItemDetailModel
import chat.matron.android.designsystem.ItemDetailView
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.itemThreadImages
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tapping an image in an item thread opens a viewer that swipes through every image in it; fixtures only. */
@RunWith(AndroidJUnit4::class)
class ItemThreadGalleryScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    // Optional review artifacts, containing only the fixtures below.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "item-gallery").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private val now = Instant.parse("2026-10-16T12:00:00Z")

    private fun png(blob: String, name: String) = TrackerAttachment(blobRef = blob, mime = "image/png", name = name, size = 48_000)

    /// A plain coloured card with a label, standing in for a picture.
    private fun picture(label: String, color: Int): Bitmap =
        Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(color)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = android.graphics.Color.WHITE
                textSize = 72f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(label, 400f, 270f, paint)
        }

    private val layoutA = png("blob_a", "layout-a.png")
    private val layoutB = png("blob_b", "layout-b.png")
    private val layoutC = png("blob_c", "layout-c.png")
    private val pictures = mapOf(
        "blob_a" to picture("Layout A", 0xFF3F6E8C.toInt()),
        "blob_b" to picture("Layout B", 0xFF8C5A3F.toInt()),
        "blob_c" to picture("Layout C", 0xFF4F7F4A.toInt()),
    )

    private fun url(a: TrackerAttachment) = "https://example.com/media/${a.blobRef}"

    private fun show() =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(MatronAppearance.Light) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
                            val item = TrackerItem(
                                id = "it_1", num = 12, kind = ItemKind.QUESTION, title = "Which layout for the home page?",
                                body = "Two layouts to compare:", originConvoID = "", createdAt = now.minusSeconds(7200), updatedAt = now,
                                attachments = listOf(layoutA, layoutB),
                            )
                            val comments = listOf(
                                TrackerComment(
                                    id = "cm_1", itemID = "it_1", author = ItemAuthor.AGENT,
                                    body = "And a third that mixes the two.", createdAt = now.minusSeconds(1800),
                                    attachments = listOf(layoutC),
                                ),
                            )
                            var gallery by remember { mutableStateOf<ImageGallery?>(null) }
                            ItemDetailView(
                                model = ItemDetailModel(
                                    item = item, comments = comments, pending = emptyList(),
                                    context = ItemContext(missionID = null, missionLabel = null, owner = null),
                                    availableResolutions = emptyList(), isBusy = false, loadedCommentCount = comments.size,
                                ),
                                draft = "", onDraftChange = {}, image = { pictures[it.blobRef] },
                                onOpenAttachment = { a ->
                                    // The same gallery the item screen builds for a tap.
                                    gallery = itemThreadGallery(itemThreadImages(item, comments), a, pictures, ::url) { null }
                                },
                                onOpenLink = {}, onOpenConversation = {}, onSubmit = {}, onAttach = {}, onVoiceNote = {}, now = now,
                            )
                            gallery?.let { AttachmentFullscreenViewer(gallery = it, onDismiss = { gallery = null }) }
                        }
                    }
                }
            }
        }

    @Test
    fun tapAnImageThenSwipeThroughTheThread() {
        show().use {
            screenshot("1-thread")
            node(By.desc("layout-b.png")).click()
            node(By.text("2 of 3"))
            screenshot("2-viewer-2-of-3")
            val w = device.displayWidth
            val h = device.displayHeight
            device.swipe((w * 0.8).toInt(), h / 2, (w * 0.2).toInt(), h / 2, 20)
            node(By.text("3 of 3"))
            screenshot("3-viewer-3-of-3")
            device.swipe((w * 0.2).toInt(), h / 2, (w * 0.8).toInt(), h / 2, 20)
            device.swipe((w * 0.2).toInt(), h / 2, (w * 0.8).toInt(), h / 2, 20)
            node(By.text("1 of 3"))
            screenshot("4-viewer-1-of-3")
        }
    }
}
