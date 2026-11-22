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
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.events.AgentSpawnCardState
import chat.matron.android.events.AgentSpawnRequest
import chat.matron.android.events.ItemSpawnConsent
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerItem
import chat.matron.android.models.TrackerLink
import java.io.File
import java.time.Instant
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A spawn consent item's page, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class ItemSpawnConsentScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "spawn-card").apply { mkdirs() }
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

    private val body = "**box-a** asks to start a session on **box-b** in `~/work/shop`.\n\n" +
        "Task: Run the checkout test suite and report any failures."

    private val item = TrackerItem(
        id = "it_1", num = 7, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER,
        title = "Start a session on box-b?", body = body, originConvoID = "c1",
        links = listOf(TrackerLink("matron://consent/spawn/sp_1")), actions = listOf("Approve", "Decline"),
        labels = listOf("consent"), createdAt = now.minusSeconds(120), updatedAt = now,
    )

    private val request = AgentSpawnRequest(
        requestId = "sp_1", fromDeviceId = 1, fromName = "box-a", fromConvoId = "c1", fromConvoTitle = "Checkout",
        targetDeviceId = 2, targetName = "box-b", workdir = "~/work/shop",
        task = "Run the checkout test suite and report any failures.", topic = "Checkout tests",
    )

    @Composable
    private fun page(consent: ItemSpawnConsent?) {
        ItemDetailView(
            model = ItemDetailModel(
                item = item, comments = emptyList(), pending = emptyList(),
                context = ItemContext(missionID = null, missionLabel = null, owner = null),
                availableResolutions = emptyList(), isBusy = false, loadedCommentCount = 0,
                spawnConsent = consent,
            ),
            draft = "", onDraftChange = {}, image = { null }, onOpenAttachment = {}, onOpenLink = {},
            onOpenConversation = {}, onSubmit = {}, onAttach = {}, onVoiceNote = {}, now = now,
            onAnswerSpawn = {},
        )
    }

    @Test
    fun drawnCardStandsInForTheBody() {
        for ((appearance, name) in listOf(MatronAppearance.Light to "card-light", MatronAppearance.Dark to "card-dark")) {
            show(appearance) { page(ItemSpawnConsent("sp_1", request, AgentSpawnCardState.Idle)) }.use {
                node(By.text("Agent spawn request"))
                node(By.text("Decline"))
                assertNull(device.findObject(By.textContains("asks to start a session")))
                screenshot(name)
            }
        }
    }

    @Test
    fun cardNotYetSyncedKeepsTheBody() {
        show(MatronAppearance.Light) { page(ItemSpawnConsent("sp_1", null, AgentSpawnCardState.Idle)) }.use {
            node(By.textContains("hasn't reached this device"))
            screenshot("placeholder-light")
        }
    }

    @Test
    fun beforeThisChangeTheBodyAlone() {
        show(MatronAppearance.Light) { page(null) }.use {
            node(By.text("Approve"))
            screenshot("before-light")
        }
    }
}
