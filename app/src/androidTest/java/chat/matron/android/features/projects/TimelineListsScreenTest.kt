package chat.matron.android.features.projects

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.TimelineWeekLayout
import chat.matron.android.models.TimelinePlace
import chat.matron.android.viewmodels.TimelineViewModel
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The lists below the weeks (all projects, went quiet, open a long time, finished), drawn from made-up fixtures. */
@RunWith(AndroidJUnit4::class)
class TimelineListsScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(800)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "projects").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun show(appearance: MatronAppearance = MatronAppearance.Light, content: @Composable (TimelineViewModel) -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        val scope = rememberCoroutineScope()
                        val vm = remember { TimelineViewModel(ProjectsFixtures.Timeline(), scope, askCoordinator = {}) }
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(vm) }
                    }
                }
            }
        }

    @Test
    fun weekPageFootRows() {
        val store = ProjectsFixtures.Store().apply { setString(TimelineWeekLayout.STORAGE_KEY, "list") }
        show { vm ->
            ProjectsScreen(
                viewModel = vm, now = { ProjectsFixtures.now }, media = ProjectsFixtures.Media(), serverURL = "https://journal.example.com/".toHttpUrl(),
                layoutStore = store, onOpenMissions = {}, onOpenMemories = {}, onOpenProject = {}, onOpenList = {},
            ) { Text("Missions") }
        }.use {
            node(By.desc("Pricing page refresh. Moved this week"))
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 1f)
            node(By.text("WORTH A LOOK"))
            node(By.text("Open a long time"))
            screenshot("week-foot-rows")
        }
    }

    @Test
    fun theFourLists() {
        for ((place, name) in listOf(TimelinePlace.Projects to "all-projects", TimelinePlace.Finished to "finished")) {
            show { vm -> TimelineListScreen(vm, place, onBack = {}, onOpenProject = {}) }.use {
                node(By.text(if (place == TimelinePlace.Projects) "Docs site launch" else "Status page"))
                screenshot("list-$name")
            }
        }
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) { vm ->
                LaunchedEffect(vm) { vm.ask(ProjectsFixtures.quiet.rows[1]) }
                TimelineListScreen(vm, TimelinePlace.Quiet, onBack = {}, onOpenProject = {})
            }.use {
                node(By.text("Waiting on new plan names from alice."))
                node(By.text("Asked the Coordinator"))
                screenshot("list-went-quiet-${appearance.name.lowercase()}")
            }
        }
        show { vm -> TimelineListScreen(vm, TimelinePlace.Long, onBack = {}, onOpenProject = {}) }.use {
            node(By.text("Archiving old articles a few at a time."))
            screenshot("list-open-long")
        }
    }
}
