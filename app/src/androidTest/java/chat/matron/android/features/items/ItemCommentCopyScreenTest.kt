package chat.matron.android.features.items

import android.graphics.Point
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
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
import chat.matron.android.designsystem.ItemContext
import chat.matron.android.designsystem.ItemDetailModel
import chat.matron.android.designsystem.ItemDetailView
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Long-press beside a comment's text in an item thread to copy the whole comment, drawn from fixtures. */
@RunWith(AndroidJUnit4::class)
class ItemCommentCopyScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "comment-copy").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private val now = Instant.parse("2026-10-16T12:00:00Z")

    private fun show(appearance: MatronAppearance) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
                            val item = TrackerItem(
                                id = "it_1", num = 15, kind = ItemKind.TASK, title = "Draft the second email",
                                body = "Keep it under 120 words.", originConvoID = "", createdAt = now.minusSeconds(7200), updatedAt = now,
                            )
                            val comments = listOf(
                                TrackerComment(
                                    id = "cm_1", itemID = "it_1", author = ItemAuthor.AGENT,
                                    body = "Here is the draft.\n\n**Subject:** Getting started\n\nHi, thanks for joining. Your first project is ready.",
                                    createdAt = now.minusSeconds(1800),
                                ),
                                TrackerComment(
                                    id = "cm_2", itemID = "it_1", author = ItemAuthor.USER,
                                    body = "Looks good to me.", createdAt = now.minusSeconds(600),
                                ),
                            )
                            ItemDetailView(
                                model = ItemDetailModel(
                                    item = item, comments = comments, pending = emptyList(),
                                    context = ItemContext(missionID = null, missionLabel = null, owner = null),
                                    availableResolutions = emptyList(), isBusy = false, loadedCommentCount = comments.size,
                                ),
                                draft = "", onDraftChange = {}, image = { null }, onOpenAttachment = {}, onOpenLink = {},
                                onOpenConversation = {}, onSubmit = {}, onAttach = {}, onVoiceNote = {}, now = now,
                            )
                        }
                    }
                }
            }
        }

    private fun pressBesideTheTextAndCopy(appearance: MatronAppearance, suffix: String) {
        show(appearance).use {
            // The short first line of the comment: press in the blank space to its right.
            val line = node(By.text("Here is the draft.")).visibleBounds
            val beside = Point(device.displayWidth - line.left - 40, line.centerY())
            device.swipe(arrayOf(beside, beside), 120)
            node(By.text("Copy"))
            screenshot("menu-$suffix")
            node(By.text("Copy")).click()
            device.wait(Until.gone(By.text("Copy")), timeout)
            screenshot("copied-$suffix")
        }
    }

    @Test
    fun copyLight() = pressBesideTheTextAndCopy(MatronAppearance.Light, "light")

    @Test
    fun copyDark() = pressBesideTheTextAndCopy(MatronAppearance.Dark, "dark")
}
