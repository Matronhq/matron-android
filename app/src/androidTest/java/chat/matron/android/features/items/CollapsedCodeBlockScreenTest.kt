package chat.matron.android.features.items

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
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** An item thread whose comment holds a `collapsed` code block, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class CollapsedCodeBlockScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "collapsed-code").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private val now = Instant.parse("2026-10-16T12:00:00Z")

    private val ask = """
        The agent asks to run one command it is not normally allowed to:

        **List the files in the project folder**, to find the build script.

        ```json collapsed
        {
          "tool_name": "Bash",
          "tool_input": {
            "command": "ls -la ${'$'}HOME/project",
            "description": "List the project folder"
          }
        }
        ```
    """.trimIndent()

    private fun show(appearance: MatronAppearance) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
                            val item = TrackerItem(
                                id = "it_1", num = 21, kind = ItemKind.QUESTION, title = "Allow one command?",
                                body = ask, originConvoID = "", createdAt = now.minusSeconds(600), updatedAt = now,
                                awaiting = ItemAwaiting.USER,
                            )
                            val comments = listOf(
                                TrackerComment(
                                    id = "cm_1", itemID = "it_1", author = ItemAuthor.AGENT,
                                    body = "The script is probably in the tools folder.\n\n```sh collapsed\nls tools/\n```",
                                    createdAt = now.minusSeconds(300),
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

    private fun foldedThenOpened(appearance: MatronAppearance, suffix: String) {
        show(appearance).use {
            node(By.text("Show json"))
            node(By.text("Show sh"))
            screenshot("folded-$suffix")
            node(By.text("Show json")).click()
            node(By.text("Hide json"))
            node(By.textContains("\"tool_name\": \"Bash\""))
            screenshot("opened-$suffix")
        }
    }

    @Test
    fun foldedAndOpenedLight() = foldedThenOpened(MatronAppearance.Light, "light")

    @Test
    fun foldedAndOpenedDark() = foldedThenOpened(MatronAppearance.Dark, "dark")
}
