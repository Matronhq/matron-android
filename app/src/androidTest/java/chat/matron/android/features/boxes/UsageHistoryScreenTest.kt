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
import chat.matron.android.models.UsageAccount
import chat.matron.android.models.UsageHistory
import chat.matron.android.models.UsageHistoryTable
import chat.matron.android.models.UsageMeter
import chat.matron.android.models.UsageMeterChoice
import chat.matron.android.models.UsageWeek
import chat.matron.android.models.UsageWeekInProgress
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Usage history drawn from made-up accounts; no server or account required. */
@RunWith(AndroidJUnit4::class)
class UsageHistoryScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    // Optional review artifacts, containing only the fixtures below.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "usage-history").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    /// Twelve finished weeks, oldest first, ending with last week's reset.
    private fun meter(id: String, label: String, primary: Boolean, unused: List<Int?>, current: Int?) = UsageMeter(
        id = id,
        label = label,
        isPrimary = primary,
        current = current?.let { UsageWeekInProgress(now + 3 * day, it, now - 3_600_000) },
        weeks = unused.mapIndexedNotNull { index, value ->
            value?.let {
                val resetsAt = now + 3 * day - (unused.size - index) * 7 * day
                UsageWeek(resetsAt, it, 100 - it, resetsAt - 3_600_000)
            }
        }.reversed(),
    )

    private val history = UsageHistory(
        listOf(
            UsageAccount(
                "claude:login:alice@example.com", "claude", "alice@example.com", listOf("box-a", "box-b"),
                listOf(
                    meter("week_all", "Week (all models)", true, listOf(62, 48, 55, 30, 41, 20, 12, 35, 44, 28, 51, 18), 64),
                    meter("week_example", "Week (Example)", false, listOf(90, 85, 92, 88, 80, 95, 91, 87, 93, 89, 84, 90), 96),
                ),
            ),
            UsageAccount(
                "claude:login:bob@example.com", "claude", "bob@example.com", listOf("box-c"),
                listOf(meter("week_all", "Week (all models)", true, listOf(null, null, null, 80, 72, 65, 70, 58, 77, 69, 74, 61), 40)),
            ),
            UsageAccount(
                "codex:device:4:1", "codex", null, listOf("box-d"),
                listOf(meter("codex:codex:secondary", "Codex · Weekly", true, listOf(null, null, null, null, null, null, 95, 88, 97, 90, 85, 93), 82)),
            ),
        ),
    )

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
    private fun Fixture(choice: UsageMeterChoice) {
        UsageHistoryContent(
            table = UsageHistoryTable.build(history, choice, nowMs = now),
            choices = UsageHistoryTable.choices(history),
            choiceID = choice.id,
            hasLoaded = true,
            hasHistory = true,
            errorMessage = null,
            isRefreshing = false,
            onRefresh = {},
            onChoose = {},
            onBack = {},
        )
    }

    @Test
    fun allModelsLight() {
        show(MatronAppearance.Light) { Fixture(UsageMeterChoice.primary) }.use {
            node(By.text("alice@example.com"))
            node(By.text("Claude · 2 boxes"))
            node(By.text("18%"))
            screenshot("usage-history-light")
        }
    }

    @Test
    fun allModelsDark() {
        show(MatronAppearance.Dark) { Fixture(UsageMeterChoice.primary) }.use {
            node(By.text("box-d"))
            screenshot("usage-history-dark")
        }
    }

    @Test
    fun modelMeter() {
        show(MatronAppearance.Light) { Fixture(UsageMeterChoice("Week (Example)", "Example")) }.use {
            node(By.text("90%"))
            screenshot("usage-history-model-meter")
        }
    }

    @Test
    fun oldJournal() {
        show(MatronAppearance.Light) {
            UsageHistoryContent(
                table = UsageHistoryTable.build(UsageHistory(emptyList()), UsageMeterChoice.primary, nowMs = now),
                choices = emptyList(),
                choiceID = UsageMeterChoice.PRIMARY_ID,
                hasLoaded = true,
                hasHistory = false,
                errorMessage = "This journal doesn't keep usage history yet. It needs an update.",
                isRefreshing = false,
                onRefresh = {},
                onChoose = {},
                onBack = {},
            )
        }.use {
            node(By.text("This journal doesn't keep usage history yet. It needs an update."))
            screenshot("usage-history-old-journal")
        }
    }
}
