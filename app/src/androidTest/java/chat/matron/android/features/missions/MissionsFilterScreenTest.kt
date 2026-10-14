package chat.matron.android.features.missions

import androidx.activity.compose.setContent
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
import chat.matron.android.journal.MissionsRefreshOutcome
import chat.matron.android.journal.MissionsStoreReading
import chat.matron.android.journal.MissionsSyncing
import chat.matron.android.models.Milestone
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionConversation
import chat.matron.android.models.MissionState
import chat.matron.android.models.SessionTagInputs
import chat.matron.android.models.TrackerItem
import chat.matron.android.viewmodels.MissionsListViewModel
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual screen with fixture missions; no server or account required. */
@RunWith(AndroidJUnit4::class)
class MissionsFilterScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L

    private val missions = listOf(
        Mission("ms_1", 21, title = "Café launch", name = "Website", originConvoID = "c1"),
        Mission("ms_2", 34, title = "Upload retry", name = "Photo tools", originConvoID = "c1"),
        Mission("ms_3", 56, state = MissionState.CLOSED, title = "Old export", name = "Cafe archive", originConvoID = "c1"),
    )

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    private fun absent(selector: BySelector) {
        assertTrue("Unexpected $selector", device.wait(Until.gone(selector), timeout))
    }

    // Optional review artifacts, containing only the fixtures above.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "missions-filter").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    @Test
    fun filterClearClosedResultsAndSelectionInLightAndDark() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val selected = AtomicReference<String?>()
            val viewModel = MissionsListViewModel(FixtureStore(), FixtureSync(), scope)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        viewModel.start()
                        activity.setContent {
                            MatronTheme(appearance) {
                                MissionsScreen(viewModel, onSelect = { selected.set(it) })
                            }
                        }
                    }
                    val field = By.clazz("android.widget.EditText")
                    val launch = By.descContains("Mission 21,")
                    val upload = By.descContains("Mission 34,")
                    val archived = By.descContains("Mission 56,")
                    node(launch)
                    node(upload)
                    absent(archived)
                    screenshot("${appearance.rawValue}-all")

                    node(field).click()
                    node(field).text = "WEBSITE cafe"
                    node(launch)
                    absent(upload)
                    absent(archived)
                    device.pressEnter()
                    node(By.clazz("android.widget.EditText").focused(false))

                    node(field).text = "café"
                    node(launch)
                    node(archived)
                    absent(upload)
                    screenshot("${appearance.rawValue}-filtered")

                    node(field).text = "zebra"
                    node(By.text("No matching missions"))
                    absent(launch)
                    absent(archived)
                    screenshot("${appearance.rawValue}-no-matches")

                    node(By.desc("Clear filter")).click()
                    node(launch)
                    node(upload)
                    absent(archived)
                    absent(By.text("No matching missions"))

                    node(By.text("Closed (1)")).click()
                    node(archived)
                    node(field).text = "#34"
                    node(upload)
                    absent(launch)
                    absent(archived)
                    node(By.desc("Clear filter")).click()
                    node(archived) // Clearing preserves the expanded fold too.

                    node(field).text = "#56"
                    node(archived).click()
                    device.waitForIdle()
                    assertEquals("ms_3", selected.get())
                }
            } finally {
                viewModel.stop()
                scope.cancel()
            }
        }
    }

    private inner class FixtureStore : MissionsStoreReading {
        override fun missionsFlow(state: MissionState?) = flowOf(missions)
        override fun missionFlow(id: String) = flowOf(missions.find { it.id == id })
        override fun milestonesFlow(missionID: String) = flowOf(emptyList<Milestone>())
        override fun missionItemsFlow(missionID: String) = flowOf(emptyList<TrackerItem>())
        override fun missionConversationsFlow(missionID: String) = flowOf(emptyList<MissionConversation>())
        override suspend fun sessionTags(convoIDs: Set<String>) = emptyMap<String, SessionTagInputs>()
    }

    private class FixtureSync : MissionsSyncing {
        override val isSupported = MutableStateFlow<Boolean?>(true)
        override suspend fun refresh() = MissionsRefreshOutcome.Succeeded
        override suspend fun refreshMission(id: String) = MissionsRefreshOutcome.Succeeded
        override suspend fun closeMission(id: String, summary: String): Mission = error("Not used by the list")
    }
}
