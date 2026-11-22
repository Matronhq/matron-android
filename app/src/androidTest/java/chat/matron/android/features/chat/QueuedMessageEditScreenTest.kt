package chat.matron.android.features.chat

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
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.designsystem.AskUserCard
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.QueuedEditControls
import chat.matron.android.events.AskUserEvent
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A queued message's card with Edit, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class QueuedMessageEditScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "queued-edit").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(16.dp),
                        ) { content() }
                    }
                }
            }
        }

    private val card = AskUserEvent(
        prompt = "Queued while the agent is busy: \"Also update the changelog\". Send it now, or cancel it?",
        kind = AskUserEvent.InputKind.Choice(
            listOf(AskUserEvent.Option("send", "⚡ Send now", "send"), AskUserEvent.Option("cancel", "✕ Cancel", "cancel")),
            allowOther = false,
        ),
        expiresAt = null,
        replyChannel = AskUserEvent.ReplyChannel.CHOICE_REPLY,
        queuedReleasePromptID = "pr_1",
        queuedEdit = AskUserEvent.QueuedEdit("Also update the changelog", 600),
    )

    @Composable
    private fun cardView(editing: Boolean, answered: Boolean = false, text: String = "Also update the changelog and the README") {
        AskUserCard(
            event = card, isAnswered = answered, answerSummary = "✎ Edited".takeIf { answered },
            textInput = "", onTextChange = {}, selectedChoiceIDs = emptySet(), onToggleChoice = {}, onPickChoice = {},
            onPickBoolean = {}, isSending = false, isExpired = false, onSend = {},
            edit = QueuedEditControls(
                text = text, onTextChange = {}, isEditing = editing, isBusy = false, canSave = true, error = null,
                holdMinutes = 10, onEdit = {}, onSave = {}, onCancel = {},
            ),
        )
    }

    @Test
    fun queuedCardOffersEdit() {
        for ((appearance, name) in listOf(MatronAppearance.Light to "card-light", MatronAppearance.Dark to "card-dark")) {
            show(appearance) { cardView(editing = false) }.use {
                node(By.text("Edit"))
                screenshot(name)
            }
        }
    }

    @Test
    fun editorHoldsTheMessage() {
        for ((appearance, name) in listOf(MatronAppearance.Light to "editor-light", MatronAppearance.Dark to "editor-dark")) {
            show(appearance) { cardView(editing = true) }.use {
                node(By.text("Save"))
                node(By.text("Cancel edit"))
                screenshot(name)
            }
        }
    }

    @Test
    fun savedEditRetiresTheCard() {
        show(MatronAppearance.Light) { cardView(editing = false, answered = true) }.use {
            node(By.text("You chose: ✎ Edited"))
            screenshot("edited-light")
        }
    }
}
