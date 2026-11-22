package chat.matron.android.features.projects

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import chat.matron.android.designsystem.ChatMissionLine
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.designsystem.MissionDetailModel
import chat.matron.android.designsystem.MissionDetailView
import chat.matron.android.designsystem.missionProjectChip
import chat.matron.android.features.chat.ChatHeaderTitle
import chat.matron.android.models.Project
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The mission and project line under a chat's title, and moving a mission to a project, drawn from made-up fixtures. */
@RunWith(AndroidJUnit4::class)
class ChatMissionLineScreenTest {
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

    private val line = ChatMissionLine("ms_20", 20, "Docs search", others = 1, isCurrent = true, projectID = "pj_docs", projectTitle = "Docs site launch")

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun chatHeaderNamesItsMissionAndProject() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                ChatHeaderTitle(
                                    chatTitle = "Index the guides", boxName = "box-a", sessionShort = "dq", boxShort = "A",
                                    roomBoxNames = emptyList(), roomBoxShorts = emptyList(), workdir = "~/work/docs-site",
                                    missionID = "ms_20", onOpenMission = {}, missionLine = line, onOpenProject = {},
                                )
                            },
                            navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        MatronTimelineBackground()
                        Column(Modifier.padding(16.dp)) {
                            Text("Indexing the guides now; the API pages are next.", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }.use {
                node(By.desc("Project: Docs site launch. Opens the project"))
                node(By.desc("Mission 20, Docs search, and 1 more. Opens the mission"))
                screenshot("chat-mission-line-${appearance.name.lowercase()}")
            }
        }
    }

    @Test
    fun movingAMissionToAnotherProject() {
        val mission = ProjectsFixtures.docsMissions.first().copy(body = "Make every guide and API page searchable from the header.")
        val targets = listOf(
            Project(id = "pj_app", num = 16, title = "App store listing"),
            Project(id = "pj_docs", num = 12, title = "Docs site launch"),
            Project(id = "pj_promo", num = 14, title = "Promo launch"),
        )
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
                    moveTargets = targets, onMove = {},
                )
            }
        }.use {
            node(By.text("Move to project…")).click()
            node(By.text("Promo launch"))
            screenshot("mission-move-to-project")
        }
    }
}
