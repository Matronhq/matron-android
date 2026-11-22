package chat.matron.android.features.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.journal.SyncDayStats
import chat.matron.android.journal.SyncScope
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings › Sync on this device, drawn on its own; no server or account required. */
@RunWith(AndroidJUnit4::class)
class SyncScopeScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    // Optional review artifacts, containing only what is drawn below.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "sync-scope").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                .systemBarsPadding().padding(16.dp),
                        ) { content() }
                    }
                }
            }
        }

    // Sample counts, as Settings draws them under the choice.
    private val sampleDays = SyncDayStats("2026-11-22", events = 1204, catchUps = 3, catchUpSeconds = 1.2,
        conversationFetches = 2, backgroundWakes = 0) to
        SyncDayStats("2026-11-21", events = 8930, catchUps = 11, catchUpSeconds = 9.9, backgroundWakes = 14)

    @Test
    fun pickingForYouOnlyChangesTheExplanation() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) {
                var selected by remember { mutableStateOf(SyncScope.EVERYTHING) }
                SyncScopeRows(selected = selected, onSelect = { selected = it }, days = sampleDays)
            }.use {
                node(By.text(SYNC_SCOPE_SECTION_TITLE))
                node(By.textContains("Every conversation is kept on this device"))
                node(By.textContains("0 background wakes"))
                screenshot("settings-everything-${appearance.name.lowercase()}")
                node(By.text(SyncScope.FOR_YOU.title)).click()
                node(By.textContains("Other conversations are fetched when you open them"))
                screenshot("settings-for-you-${appearance.name.lowercase()}")
            }
        }
    }
}
