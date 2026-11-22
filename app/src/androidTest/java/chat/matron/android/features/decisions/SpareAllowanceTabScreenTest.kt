package chat.matron.android.features.decisions

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
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
import chat.matron.android.designsystem.DecisionsListModel
import chat.matron.android.designsystem.DecisionsListView
import chat.matron.android.designsystem.DecisionsRow
import chat.matron.android.designsystem.ForYouCopy
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.MatronTimelineBackground
import chat.matron.android.models.ForYouTab
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerItem
import java.io.File
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** For you's Spare allowance tab, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class SpareAllowanceTabScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "spare-allowance").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private val now = Instant.parse("2026-09-21T12:00:00Z")

    private fun task(num: Int, title: String, convo: String, vararg extra: String) = TrackerItem(
        id = "t$num", num = num, kind = ItemKind.TASK, rank = num.toDouble(), title = title, originConvoID = convo,
        labels = listOf(TrackerItem.SPARE_ALLOWANCE_LABEL) + extra, createdAt = now, updatedAt = now,
    )

    private val backlog = listOf(
        DecisionsRow(task(41, "Re-run the full end-to-end suite on every device size", "c1", "dispatched"), "Test coverage"),
        DecisionsRow(task(38, "Regenerate the onboarding screenshots in every locale", "c2"), "Store listing"),
        DecisionsRow(task(45, "Profile cold start and write up the three slowest steps", "c3"), "Performance pass"),
    )

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Box(Modifier.fillMaxSize().systemBarsPadding()) {
                            MatronTimelineBackground()
                            content()
                        }
                    }
                }
            }
        }

    private fun list(spare: List<DecisionsRow>) = DecisionsListModel(
        rows = emptyList(), isSupported = true, isRefreshing = false, tab = ForYouTab.SPARE, spare = spare,
    )

    @Test
    fun backlogInOrder() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) {
                DecisionsListView(model = list(backlog), onSelect = {}, onOpenConversation = {}, onRefresh = {}, now = now)
            }.use {
                node(By.text("Spare allowance"))
                node(By.text("Re-run the full end-to-end suite on every device size"))
                screenshot("spare-backlog-${appearance.rawValue}")
            }
        }
    }

    @Test
    fun emptyBacklog() {
        show(MatronAppearance.Light) {
            DecisionsListView(model = list(emptyList()), onSelect = {}, onOpenConversation = {}, onRefresh = {}, now = now)
        }.use {
            node(By.text(ForYouCopy.SPARE_EMPTY_TITLE))
            screenshot("spare-empty-light")
        }
    }
}
