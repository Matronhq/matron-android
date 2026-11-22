package chat.matron.android.features.boxes

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.RPCReply
import chat.matron.android.journal.RosterSession
import chat.matron.android.journal.RosterSnapshot
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.viewmodels.BoxAllowances
import chat.matron.android.viewmodels.BoxCapacity
import chat.matron.android.viewmodels.BoxCapacityCaching
import chat.matron.android.viewmodels.BoxDisk
import chat.matron.android.viewmodels.BoxResolvedDefaults
import chat.matron.android.viewmodels.BoxStatus
import chat.matron.android.viewmodels.BoxesProviding
import chat.matron.android.viewmodels.BoxesViewModel
import chat.matron.android.viewmodels.CachedBoxCapacity
import chat.matron.android.viewmodels.LimitLine
import java.io.File
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Boxes screen and a box's sessions, drawn from made-up boxes; no server or account required. */
@RunWith(AndroidJUnit4::class)
class BoxesScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L
    private val now = System.currentTimeMillis()

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    // Optional review artifacts, containing only the fixtures below.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "boxes").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun box(
        id: Long, name: String, connected: Boolean, live: Int?, email: String?, all: Int?, model: Int?,
        resetsInDays: Double, agent: String = "claude", modelName: String = "opus", effort: String? = null,
        freeGB: Long, totalGB: Long, ageMinutes: Long,
    ): DeviceDTO {
        val gib = 1024L * 1024 * 1024
        val resetsAt = now + (resetsInDays * 86_400_000).toLong()
        val lines = listOfNotNull(
            all?.let { LimitLine(BoxAllowances.ALL_MODELS_LINE_ID, "Week (all models)", it, resetsAt) },
            model?.let { LimitLine("week_example", "Week (Example)", it, resetsAt) },
        )
        val readAt = now - ageMinutes * 60_000
        val capacity = BoxCapacity(
            liveSessions = live, limitLines = lines, accountEmail = email,
            disk = BoxDisk(freeGB * gib, totalGB * gib),
            limitsAsOfMs = if (lines.isEmpty()) null else readAt,
            defaults = BoxResolvedDefaults(agent, modelName, effort),
        )
        return DeviceDTO(
            id = id, kind = "agent", name = name, createdAt = 0, cursor = 0, lag = 0, lastSeenAt = null,
            isSelf = false, connected = connected, status = BoxStatus(readAt, capacity),
        )
    }

    private val fixtures = object : BoxesProviding {
        override suspend fun devices(): List<DeviceDTO> = listOf(
            box(1, "box-a", true, 4, "alice@example.com", 21, 3, 2.7, freeGB = 47, totalGB = 145, ageMinutes = 4),
            box(2, "box-b", true, 2, "bob@example.com", 23, 6, 1.8, freeGB = 45, totalGB = 145, ageMinutes = 2),
            box(3, "box-c", true, 1, "carol@example.com", 94, 4, 4.6, freeGB = 327, totalGB = 1800, ageMinutes = 4),
            box(
                4, "box-d", true, 3, "dave@example.com", 15, null, 6.4, agent = "codex", modelName = "gpt-example",
                effort = "high", freeGB = 30, totalGB = 145, ageMinutes = 6,
            ),
            box(5, "box-e", false, null, "erin@example.com", 48, 11, 3.8, freeGB = 51, totalGB = 145, ageMinutes = 120),
            box(6, "box-f", false, null, null, null, null, 0.0, freeGB = 12, totalGB = 64, ageMinutes = 600),
        )

        override suspend fun roster(): RosterSnapshot = RosterSnapshot(
            wakeableBoxes = setOf(5),
            sessionsByBox = mapOf(
                2L to listOf(
                    RosterSession("demo-1", "Fix the failing import test", true, now - 60_000),
                    RosterSession("demo-2", "Write the release notes", false, now - 9 * 60_000),
                ),
                3L to listOf(RosterSession("demo-3", "Review the settings page", true, now - 120_000)),
            ),
        )

        override suspend fun agentRequest(agentDeviceID: Long, method: String, paramsJson: String): RPCReply =
            RPCReply.Failure("agent_unreachable", null)
        override fun boxStatusUpdates(): Flow<Pair<Long, BoxStatus>> = emptyFlow()
        override fun connectionState(): StateFlow<SyncConnectionState> = MutableStateFlow(SyncConnectionState.Running)
    }

    private val noCache = object : BoxCapacityCaching {
        override fun loadAll(): Map<Long, CachedBoxCapacity> = emptyMap()
        override fun save(capacity: BoxCapacity, agentID: Long, capturedAtMs: Long) = Unit
        override fun prune(keeping: Set<Long>) = Unit
    }

    private fun loadedViewModel() = BoxesViewModel(fixtures, noCache, pollSleep = { awaitCancellation() })
        .also { runBlocking { it.load() } }

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
                            content()
                        }
                    }
                }
            }
        }

    @Composable
    private fun BoxesFixture(viewModel: BoxesViewModel) {
        val rows = viewModel.rows(nowMs = now)
        BoxesContent(
            rows = rows,
            summary = viewModel.summary(rows, now),
            hasLoaded = true,
            errorMessage = null,
            isRefreshing = false,
            onRefresh = {},
            onWake = {},
            onStartSession = {},
            onOpenSessions = {},
            onBack = {},
            fixedNowMs = now,
            onOpenUsageHistory = {},
        )
    }

    @Test
    fun boxesLight() {
        val viewModel = loadedViewModel()
        show(MatronAppearance.Light) { BoxesFixture(viewModel) }.use {
            node(By.text("6 boxes · 4 online · 1 asleep · 1 offline"))
            node(By.text("Most spare per day"))
            node(By.text("Near limit"))
            node(By.text("2 live ›"))
            node(By.text("Usage history"))
            screenshot("boxes-light")
        }
    }

    @Test
    fun boxesDarkScrolledToTheSleepingBoxes() {
        val viewModel = loadedViewModel()
        show(MatronAppearance.Dark) { BoxesFixture(viewModel) }.use {
            node(By.text("Most spare per day"))
            screenshot("boxes-dark")
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 5, 30)
            node(By.text("Wake"))
            node(By.text("box-f"))
            screenshot("boxes-dark-asleep-offline")
        }
    }

    @Test
    fun aBoxsSessions() {
        val viewModel = loadedViewModel()
        val row = viewModel.rows(nowMs = now).first { it.device.name == "box-b" }
        show(MatronAppearance.Light) {
            BoxSessionsScreen(name = row.device.name, sessions = row.sessions, onOpenChat = {}, onBack = {})
        }.use {
            node(By.text("Fix the failing import test"))
            node(By.text("Waiting"))
            screenshot("box-sessions")
        }
    }
}
