package chat.matron.android.designsystem

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
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
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The prompt a tap on a non-web, non-email link shows, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class BlockedLinkScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "blocked-link").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun show(appearance: MatronAppearance) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        BlockedLinkHost {
                            Column(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                    .systemBarsPadding().padding(16.dp),
                            ) {
                                MarkdownText("Reception says to ring them first:")
                                MarkdownText("[Call the front desk](tel:+15550100)")
                            }
                        }
                    }
                }
            }
        }

    private fun tapBlockedLink(appearance: MatronAppearance, name: String) {
        show(appearance).use {
            node(By.text("Call the front desk")).click()
            node(By.text(BlockedLinkPrompt.TITLE))
            node(By.text("tel:+15550100"))
            screenshot(name)
            node(By.text(BlockedLinkPrompt.COPY)).click()
            assertTrue(device.wait(Until.gone(By.text(BlockedLinkPrompt.TITLE)), timeout))
        }
    }

    @Test
    fun telLinkShowsThePromptLight() = tapBlockedLink(MatronAppearance.Light, "blocked-link-light")

    @Test
    fun telLinkShowsThePromptDark() = tapBlockedLink(MatronAppearance.Dark, "blocked-link-dark")
}
