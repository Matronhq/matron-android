package chat.matron.android.features.settings

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
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.NewChatDefaults
import chat.matron.android.journal.NewChatDefaultsProviding
import chat.matron.android.viewmodels.NewChatDefaultsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings → New chats drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class NewChatDefaultsScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "new-chat-defaults").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private class FakeApi(var stored: NewChatDefaults, val notFound: Boolean = false) : NewChatDefaultsProviding {
        override suspend fun newChatDefaults(): NewChatDefaults {
            if (notFound) throw JournalApiError.NotFound
            return stored
        }
        override suspend fun setNewChatDefault(key: NewChatDefaults.Key, value: String?): NewChatDefaults {
            stored = stored.with(key, value); return stored
        }
    }

    private fun store(api: FakeApi) = NewChatDefaultsStore(api, { emptyFlow() }, CoroutineScope(SupervisorJob() + Dispatchers.Main))

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(16.dp)) {
                            content()
                        }
                    }
                }
            }
        }

    @Test
    fun sectionLightAndDark() {
        for ((appearance, name) in listOf(MatronAppearance.Light to "section-light", MatronAppearance.Dark to "section-dark")) {
            val s = store(FakeApi(NewChatDefaults("opus[1m]", null)))
            show(appearance) { NewChatDefaultsSection(s) }.use {
                node(By.text("Default model"))
                node(By.text("Opus (1M)"))
                screenshot(name)
            }
        }
    }

    @Test
    fun effortPickerOpen() {
        val s = store(FakeApi(NewChatDefaults("opus[1m]", "high")))
        show(MatronAppearance.Light) { NewChatDefaultsSection(s) }.use {
            node(By.text("High")).click()
            node(By.text("X-High"))
            screenshot("effort-picker")
        }
    }

    @Test
    fun olderJournal() {
        val s = store(FakeApi(NewChatDefaults(), notFound = true))
        show(MatronAppearance.Light) { NewChatDefaultsSection(s) }.use {
            node(By.text(NewChatDefaultsStore.UNSUPPORTED_TEXT))
            screenshot("unsupported")
        }
    }
}
