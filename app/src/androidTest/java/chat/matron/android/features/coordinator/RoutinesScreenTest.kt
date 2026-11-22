package chat.matron.android.features.coordinator

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
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.features.settings.CoordinatorSection
import chat.matron.android.features.settings.CoordinatorSettingRowModel
import chat.matron.android.journal.RoutineRunResult
import chat.matron.android.journal.RoutinesProviding
import chat.matron.android.models.Routine
import chat.matron.android.models.RoutineDraft
import chat.matron.android.models.RoutineTrigger
import chat.matron.android.viewmodels.RoutinesViewModel
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings → Coordinator → Routines drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class RoutinesScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "routines").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private val now = Instant.now()
    private fun todayAt(hour: Int, minute: Int): Instant =
        now.atZone(ZoneId.systemDefault()).toLocalDate().atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant()
            .let { if (it.isBefore(now)) it.plus(Duration.ofDays(1)) else it }

    private val fixtures = listOf(
        Routine(
            id = "rt_1", name = "daily-sweep", title = "Daily sweep", schedule = "5 7 * * *",
            prompt = "Follow the Daily sweep section of your playbook.", origin = "seed",
            nextAt = todayAt(7, 5), lastFiredAt = now.minus(Duration.ofHours(3)), lastOutcome = "applied now",
            createdAt = now, updatedAt = now,
        ),
        Routine(
            id = "rt_2", name = "deploy-window", title = "Evening deploy window", schedule = "30 18 * * 1-5",
            prompt = "Check what is ready to ship.", enabled = false,
            lastFiredAt = now.minus(Duration.ofDays(2)), lastOutcome = "applied deferred",
            createdAt = now, updatedAt = now,
        ),
        Routine(
            id = "rt_3", name = "disk-low", title = "Box disk under the threshold", trigger = RoutineTrigger.DiskUnder(20),
            prompt = "Find what is filling the disk on the box and tell me.",
            lastFiredAt = now.minus(Duration.ofMinutes(40)), lastOutcome = "failed agent_unreachable",
            createdAt = now, updatedAt = now,
        ),
        Routine(
            id = "rt_4", name = "stalled-session", title = "Session stalled on a usage limit", trigger = RoutineTrigger.Stalled(120),
            prompt = "Move the stalled session's work to a box with allowance left.",
            createdAt = now, updatedAt = now,
        ),
    )

    private class FakeApi(val list: List<Routine>) : RoutinesProviding {
        override suspend fun listRoutines() = list
        override suspend fun createRoutine(name: String, draft: RoutineDraft) = list[0]
        override suspend fun updateRoutine(id: String, draft: RoutineDraft) = list.first { it.id == id }
        override suspend fun setRoutineEnabled(id: String, enabled: Boolean) = list.first { it.id == id }.copy(enabled = enabled)
        override suspend fun deleteRoutine(id: String) = Unit
        override suspend fun runRoutine(id: String): RoutineRunResult = RoutineRunResult.Accepted
    }

    private fun viewModel() = RoutinesViewModel(FakeApi(fixtures), { emptyFlow() }, CoroutineScope(SupervisorJob() + Dispatchers.Main))

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

    @Test
    fun listLightAndDark() {
        for ((appearance, name) in listOf(MatronAppearance.Light to "list-light", MatronAppearance.Dark to "list-dark")) {
            val vm = viewModel()
            show(appearance) { RoutinesScreen(vm, onSelect = {}, onNew = {}, onBack = {}) }.use {
                node(By.text("Daily sweep"))
                node(By.text("Every day at 07:05 (Europe/London)"))
                node(By.text("Paused"))
                node(By.text("Failed: agent_unreachable"))
                screenshot(name)
            }
        }
    }

    @Test
    fun rowMenu() {
        val vm = viewModel()
        show(MatronAppearance.Light) { RoutinesScreen(vm, onSelect = {}, onNew = {}, onBack = {}) }.use {
            node(By.desc("More for Daily sweep")).click()
            node(By.text("Run now"))
            screenshot("row-menu")
        }
    }

    @Test
    fun editors() {
        val vm = viewModel()
        show(MatronAppearance.Light) {
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.load() }
            RoutineEditorScreen(vm, routineID = "rt_3", onBack = {})
        }.use {
            node(By.text("Low disk"))
            node(By.text("When a box drops under 20% free disk"))
            screenshot("editor-trigger")
        }
        show(MatronAppearance.Dark) {
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.load() }
            RoutineEditorScreen(vm, routineID = "rt_2", onBack = {})
        }.use {
            node(By.text("Monday to Friday at 18:30"))
            screenshot("editor-schedule-dark")
        }
        show(MatronAppearance.Light) { RoutineEditorScreen(vm, routineID = null, onBack = {}) }.use {
            node(By.text("On a schedule"))
            screenshot("editor-new")
        }
    }

    @Test
    fun settingsEntry() {
        show(MatronAppearance.Light) {
            Column(Modifier.padding(16.dp)) {
                CoordinatorSection(CoordinatorSettingRowModel(title = "Coordinator", onChoose = {}, onClear = {}, onRoutines = {}))
            }
        }.use {
            node(By.text("Routines"))
            screenshot("settings-entry")
        }
    }
}
