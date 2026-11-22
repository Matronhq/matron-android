package chat.matron.android.features.projects

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
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
import chat.matron.android.designsystem.TimelineWeekLayout
import chat.matron.android.viewmodels.KeyValueStore
import chat.matron.android.viewmodels.TimelineViewModel
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Projects tab's week page, drawn from made-up fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class ProjectsScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    // Optional review artifacts, containing only the fixtures.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(800)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "projects").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun show(appearance: MatronAppearance, store: KeyValueStore = ProjectsFixtures.Store()) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        val scope = rememberCoroutineScope()
                        val vm = remember { TimelineViewModel(ProjectsFixtures.Timeline(), scope) }
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            ProjectsScreen(
                                viewModel = vm, media = ProjectsFixtures.Media(), serverURL = "https://journal.example.com/".toHttpUrl(),
                                layoutStore = store, onOpenMissions = {}, onOpenMemories = {}, onOpenProject = {},
                            ) { Text("Missions") }
                        }
                    }
                }
            }
        }

    @Test
    fun weekPageAsTiles() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance).use {
                node(By.text("This week"))
                node(By.desc("Docs site launch. The docs site went live, with a getting-started guide and the API reference. 1 waiting for you, 4 done, has a next step"))
                screenshot("week-tiles-${appearance.name.lowercase()}")
            }
        }
    }

    @Test
    fun weekPageAsAList() {
        val store = ProjectsFixtures.Store().apply { setString(TimelineWeekLayout.STORAGE_KEY, "list") }
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance, store).use {
                node(By.desc("Pricing page refresh. Moved this week"))
                screenshot("week-list-${appearance.name.lowercase()}")
            }
        }
    }

    @Test
    fun weekPickerAndLastWeek() {
        show(MatronAppearance.Light).use {
            node(By.text("This week")).click()
            node(By.text("Earlier weeks"))
            node(By.text("2 – 8 Nov"))
            screenshot("week-menu")
            node(By.text("Last week")).click()
            node(By.desc("Onboarding emails. The welcome email was rewritten and sent to bob for a read. 1 waiting for you"))
            screenshot("last-week")
        }
    }
}
