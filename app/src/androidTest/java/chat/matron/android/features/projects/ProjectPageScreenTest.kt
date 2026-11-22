package chat.matron.android.features.projects

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import chat.matron.android.designsystem.MissionDetailModel
import chat.matron.android.designsystem.MissionDetailView
import chat.matron.android.designsystem.ProjectSection
import chat.matron.android.designsystem.missionProjectChip
import chat.matron.android.viewmodels.ProjectPageViewModel
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A project's page, its sections and the project chip on a mission page, drawn from made-up fixtures. */
@RunWith(AndroidJUnit4::class)
class ProjectPageScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L
    private val serverURL = "https://journal.example.com/".toHttpUrl()

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

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
                    }
                }
            }
        }

    private fun vm() = ProjectPageViewModel(ProjectsFixtures.docs.id, ProjectsFixtures.Timeline(), ProjectsFixtures.Projects())

    @Test
    fun projectPage() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) {
                val viewModel = remember { vm() }
                ProjectScreen(viewModel, ProjectsFixtures.Media(), serverURL, onBack = {}, onOpenItem = {}, onOpenSection = {})
            }.use {
                node(By.text("Docs site launch"))
                node(By.text("and 2 more"))
                node(By.text("2 open, 3 done"))
                screenshot("project-page-${appearance.name.lowercase()}")
            }
        }
    }

    @Test
    fun historyAndMissionsSections() {
        show(MatronAppearance.Light) {
            val viewModel = remember { vm() }
            ProjectSectionScreen(viewModel, ProjectSection.HISTORY, ProjectsFixtures.Media(), serverURL, onBack = {}, onOpenMission = {}, onOpenMilestone = {})
        }.use {
            node(By.text("Deployed the site to production"))
            screenshot("project-history")
        }
        show(MatronAppearance.Light) {
            val viewModel = remember { vm() }
            ProjectSectionScreen(viewModel, ProjectSection.MISSIONS, ProjectsFixtures.Media(), serverURL, onBack = {}, onOpenMission = {}, onOpenMilestone = {})
        }.use {
            node(By.text("Docs search"))
            node(By.text("Written, reviewed by bob, published."))
            screenshot("project-missions")
        }
    }

    @Test
    fun filesSection() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) {
                val viewModel = remember { vm() }
                ProjectSectionScreen(viewModel, ProjectSection.FILES, ProjectsFixtures.Media(), serverURL, onBack = {}, onOpenMission = {}, onOpenMilestone = {})
            }.use {
                node(By.desc("File style-guide.pdf, #33 · 5d"))
                screenshot("project-files-${appearance.name.lowercase()}")
                if (appearance == MatronAppearance.Light) {
                    node(By.descStartsWith("Image search-results.png")).click()
                    device.wait(Until.hasObject(By.textContains("2")), timeout)
                    screenshot("project-files-viewer")
                }
            }
        }
    }

    @Test
    fun missionPageNamesItsProject() {
        val mission = ProjectsFixtures.docsMissions.first().copy(body = "Make every guide and API page searchable from the header.")
        show(MatronAppearance.Light) {
          Box(Modifier.systemBarsPadding()) {
            MissionDetailView(
                model = MissionDetailModel.from(
                    mission = mission, milestones = emptyList(), sessionTags = emptyMap(), openItems = emptyList(),
                    conversations = emptyList(), showOnlyUserInput = false, closeSummary = "", isBusy = false,
                ),
                onToggleUserInputOnly = {}, onOpenMilestone = {}, onOpenItem = {}, onOpenConversation = {},
                onEditCloseSummary = {}, onClose = {}, onRefresh = {},
                projectChip = missionProjectChip(mission, ProjectsFixtures.docs.title), onOpenProject = {},
            )
          }
        }.use {
            node(By.desc("Project: Docs site launch. Opens the project"))
            screenshot("mission-project-chip")
        }
    }
}
