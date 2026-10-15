package chat.matron.android.features.coordinator

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.designsystem.AgentChatRequestCard
import chat.matron.android.designsystem.AgentSpawnRequestCard
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.events.AgentChatCardState
import chat.matron.android.events.AgentChatRequest
import chat.matron.android.events.AgentSpawnCardState
import chat.matron.android.events.AgentSpawnRequest
import chat.matron.android.events.ConsentDecisionEvent
import chat.matron.android.events.CoordinatorConsentBadge
import chat.matron.android.events.SessionStopState
import chat.matron.android.events.SpawnOutcome
import chat.matron.android.features.settings.CoordinatorSection
import chat.matron.android.features.settings.CoordinatorSettingRowModel
import chat.matron.android.journal.CoordinatorConsentProviding
import chat.matron.android.viewmodels.CoordinatorConsentSettingViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Coordinator consent switch and badges, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class CoordinatorConsentScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    // Optional review artifacts, containing only the fixtures below.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "coordinator-consent").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun payload(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private val spawn = AgentSpawnRequest.parse(payload(
        """{"kind":"agent_spawn","request_id":"spawn-1","from_device_id":4,"from_name":"box-a",
           "from_convo_title":"Release checks","target_device_id":7,"target_name":"box-b",
           "workdir":"/srv/project","task":"Run the release checklist and report back.","topic":"release checks"}""",
    ))!!

    private val chat = AgentChatRequest.parse(payload(
        """{"kind":"agent_chat","request":"invite","room_id":"room-1","from_device_id":4,
           "target_device_id":7,"from_name":"box-a","to_name":"box-b",
           "topic":"Release checks","justification":"Compare test results"}""",
    ))!!

    private val approvedSpawn = ConsentDecisionEvent(
        ConsentDecisionEvent.Kind.SPAWN, ConsentDecisionEvent.Decision.APPROVE,
        reason = "Same project and task as the asking session", askID = "spawn-1",
    )
    private val approvedChat = ConsentDecisionEvent(
        ConsentDecisionEvent.Kind.CHAT, ConsentDecisionEvent.Decision.APPROVE,
        reason = "Both sessions work on the same release", askID = chat.askID,
    )

    private class Api : CoordinatorConsentProviding {
        var stored = true
        override suspend fun coordinatorConsent(): Boolean = stored
        override suspend fun setCoordinatorConsent(enabled: Boolean): Boolean { stored = enabled; return stored }
    }

    @Test
    fun switchAndBadgesInLightAndDark() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val consent = CoordinatorConsentSettingViewModel(Api(), scope)
            val started = SpawnOutcome("spawn-1", "started", roomId = "room-9", childConvoId = "child-1")
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.setContent {
                            MatronTheme(appearance) {
                                var stop by remember { mutableStateOf<SessionStopState>(SessionStopState.Idle) }
                                Column(
                                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()
                                        .verticalScroll(rememberScrollState()).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    CoordinatorSection(CoordinatorSettingRowModel(
                                        title = "Coordinator", onChoose = {}, onClear = {}, consent = consent,
                                    ))
                                    AgentSpawnRequestCard(
                                        request = spawn,
                                        state = AgentSpawnCardState.Resolved(started),
                                        onApprove = {}, onDeny = {}, onOpen = {},
                                        modifier = Modifier.widthIn(max = 360.dp),
                                        coordinatorBadge = CoordinatorConsentBadge(approvedSpawn, "child-1", stop),
                                        onStopSession = { stop = SessionStopState.Sent },
                                    )
                                    AgentChatRequestCard(
                                        request = chat,
                                        state = AgentChatCardState.Answered(true, byCoordinator = true),
                                        onApprove = {}, onDeny = {},
                                        modifier = Modifier.widthIn(max = 360.dp),
                                        coordinatorBadge = CoordinatorConsentBadge(approvedChat),
                                    )
                                }
                            }
                        }
                    }
                    node(By.text(CoordinatorConsentSettingViewModel.TITLE))
                    node(By.textStartsWith("Approved by the Coordinator — Same project"))
                    screenshot("${appearance.rawValue}-settings-and-spawn")

                    node(By.text("Stop session")).click()
                    node(By.text("Stop sent to the session"))
                    node(By.scrollable(true)).scroll(Direction.DOWN, 1f)
                    node(By.text("Approved by the Coordinator — Both sessions work on the same release"))
                    screenshot("${appearance.rawValue}-stop-sent-and-chat")
                }
            } finally {
                consent.stop()
                scope.cancel()
            }
        }
    }
}
