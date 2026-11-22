package chat.matron.android.features.items

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import chat.matron.android.designsystem.ItemReplyDeliveryActions
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemWorkingIndicator
import chat.matron.android.models.QueuedReplyState
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The working indicator on "For you only": a reply queued behind another turn
 * while the owner's stored state reads "waiting". Before, the stale state hid
 * the indicator; now the item's own copy stands. Drawn from fixtures; the
 * decision itself is unit-tested in ItemWorkingIndicatorTest.
 */
@RunWith(AndroidJUnit4::class)
class ForYouWorkingIndicatorScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L
    private val now = Instant.parse("2026-11-20T10:00:00Z")

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    // Optional review artifacts, containing only the fixtures below.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "for-you-working").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) { content() }
                    }
                }
            }
        }

    private val item = TrackerItem(
        id = "it_1", num = 12, kind = ItemKind.QUESTION, title = "Which colour for the sign-up button?",
        body = "Blue matches the logo; green tested better last spring.", originConvoID = "c1",
        createdAt = now.minusSeconds(3600), updatedAt = now,
    )
    private val comments = listOf(
        TrackerComment(
            id = "ic_0", itemID = "it_1", author = ItemAuthor.AGENT, body = "I can try both on the staging page if that helps.",
            createdAt = now.minusSeconds(1800),
        ),
        TrackerComment(id = "ic_1", itemID = "it_1", author = ItemAuthor.USER, body = "Use the blue one.", createdAt = now.minusSeconds(60)),
    )
    private val actions = ItemReplyDeliveryActions(sendNow = {}, cancel = {}, edit = {}, cancelEdit = {}, editAndResend = {})

    @Composable
    private fun thread(state: QueuedReplyState, working: ItemWorkingIndicator?, editing: Boolean = false, draft: String = "") {
        ItemDetailView(
            model = ItemDetailModel(
                item = item, comments = comments, pending = emptyList(),
                context = ItemContext(missionID = null, missionLabel = null, owner = null),
                availableResolutions = emptyList(), isBusy = false, loadedCommentCount = comments.size,
                working = working,
                queuedReplies = mapOf("ic_1" to state),
                editableQueuedReplies = setOf("ic_1"),
                editingQueuedReplyID = "ic_1".takeIf { editing },
            ),
            draft = draft, onDraftChange = {}, image = { null }, onOpenAttachment = {}, onOpenLink = {},
            onOpenConversation = {}, onSubmit = {}, onAttach = {}, onVoiceNote = {}, now = now,
            replyDelivery = actions, startsAtBottom = true,
        )
    }

    private val queued = QueuedReplyState.Queued("c1", 10, offersSendOne = true)

    @Test
    fun aQueuedReplyShowsTheAgentIsBusyOnForYouOnly() {
        show(MatronAppearance.Light) { thread(queued, working = null) }.use {
            node(By.text("Send now"))
            screenshot("before-no-indicator")
        }
        for ((appearance, name) in listOf(MatronAppearance.Light to "after-light", MatronAppearance.Dark to "after-dark")) {
            show(appearance) { thread(queued, working = ItemWorkingIndicator.BUSY_ELSEWHERE) }.use {
                node(By.text("Send now"))
                screenshot(name)
            }
        }
    }
}
