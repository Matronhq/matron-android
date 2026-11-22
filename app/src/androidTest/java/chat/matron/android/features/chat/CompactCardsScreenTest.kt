package chat.matron.android.features.chat

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import chat.matron.android.chat.TimelineItem
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.events.AgentChatCardState
import chat.matron.android.events.AgentChatRequest
import chat.matron.android.events.AgentSpawnCardState
import chat.matron.android.events.AgentSpawnRequest
import chat.matron.android.events.AskUserEvent
import chat.matron.android.events.SpawnOutcome
import java.io.File
import java.time.Instant
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reminder cards and settled consent cards folded to one line, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class CompactCardsScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "compact-cards").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private val base = Instant.parse("2026-09-21T09:40:00Z")

    private fun item(seq: Long, kind: TimelineItem.Kind) =
        TimelineItem(id = seq.toString(), sender = "box-a", timestamp = base.plusSeconds(seq * 60), kind = kind, isOwn = false)

    private val reminder = AskUserEvent(
        prompt = "\u23F0 The agent set itself reminder #12 \u2014 in 10m:\n\"Check the release build on box-b and report the result.\"",
        kind = AskUserEvent.InputKind.Choice(
            listOf(
                AskUserEvent.Option("timer-send-12", "\uD83D\uDCE4 Send now", "timer:send:12"),
                AskUserEvent.Option("timer-cancel-12", "\uD83D\uDEAB Cancel timer", "timer:cancel:12"),
            ),
            allowOther = false,
        ),
        expiresAt = null,
        replyChannel = AskUserEvent.ReplyChannel.CHOICE_REPLY,
    )

    private val spawn = AgentSpawnRequest(
        requestId = "r1", fromDeviceId = 1, fromName = "box-a", fromConvoId = "c1", fromConvoTitle = "Release prep",
        targetDeviceId = 2, targetName = "box-b", workdir = "\$HOME/Dev/checkout",
        task = "Run the release build\nand report back with the test summary.", topic = null,
    )

    private val chatAnswered = AgentChatRequest(
        ask = AgentChatRequest.Ask.INVITE, roomID = "!r1", fromDeviceID = 1, fromName = "box-a",
        targetDeviceID = 3, toName = "box-c", topic = "Payment step", justification = null,
    )

    private val chatPending = AgentChatRequest(
        ask = AgentChatRequest.Ask.INVITE, roomID = "!r2", fromDeviceID = 1, fromName = "box-a",
        targetDeviceID = 4, toName = "box-d", topic = "Store screenshots", justification = "It owns the screenshot simulators.",
    )

    private val items = listOf(
        item(1, TimelineItem.Kind.Text("I'll check the release build in ten minutes.", null)),
        item(2, TimelineItem.Kind.AskUser("2", reminder)),
        item(3, TimelineItem.Kind.AgentSpawnRequestCard("3", spawn)),
        item(4, TimelineItem.Kind.AgentChatRequestCard("4", chatAnswered)),
        item(5, TimelineItem.Kind.AgentChatRequestCard("5", chatPending)),
    )

    private fun show(appearance: MatronAppearance) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Box(Modifier.fillMaxSize().systemBarsPadding()) {
                            MatronTimelineBackground()
                            Column(
                                Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (item in items) {
                                    TimelineItemView(
                                        item = item,
                                        onOpenSpawnedRoom = {},
                                        agentSpawnState = { _, _ ->
                                            AgentSpawnCardState.Resolved(SpawnOutcome(requestId = "r1", outcome = "started", roomId = "!child"))
                                        },
                                        agentChatState = { id ->
                                            if (id == "4") AgentChatCardState.Answered(true, byCoordinator = true) else AgentChatCardState.Idle
                                        },
                                        onAnswerAgentChat = { _, _, _ -> },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun foldedThenOpened() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance).use {
                val reminderLine = node(By.descContains("The agent set itself reminder #12"))
                node(By.text("Send now"))
                node(By.text("Open"))
                node(By.descContains("Approved by the Coordinator"))
                // The pending request is still the full card, with its answers.
                node(By.text("Approve"))
                assertNull("the folded reminder hides its full text", device.findObject(By.text(reminder.prompt)))
                screenshot("cards-folded-${appearance.rawValue}")
                reminderLine.click()
                node(By.text(reminder.prompt))
                node(By.descContains("Spawn on box-b")).click()
                node(By.text("Agent spawn request"))
                screenshot("cards-opened-${appearance.rawValue}")
            }
        }
    }
}
