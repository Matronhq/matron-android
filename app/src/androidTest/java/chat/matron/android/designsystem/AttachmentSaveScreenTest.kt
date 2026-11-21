package chat.matron.android.designsystem

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The file chip's Save button and the save picker it opens, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class AttachmentSaveScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "attachment-save").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun show(appearance: MatronAppearance) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        val saver = rememberAttachmentSaver { "application/pdf" }
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                .systemBarsPadding().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("Without a save action (as before)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground)
                            AttachmentFile(filename = "quarterly-plan.pdf", sizeBytes = 248_000, onTap = {})
                            Text("With Save", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground)
                            AttachmentFile(
                                filename = "quarterly-plan.pdf",
                                sizeBytes = 248_000,
                                isLoading = saver.isSaving("k1"),
                                onTap = {},
                                onSave = {
                                    saver.save("k1", "quarterly-plan.pdf", "application/pdf") { "%PDF-1.4 fixture".toByteArray() }
                                },
                            )
                            AttachmentFile(filename = "notes-from-alice.csv", sizeBytes = 1_200, onTap = {}, onSave = {})
                            AttachmentFile(filename = "old-export.zip", sizeBytes = 8_000_000, isExpired = true, onTap = {}, onSave = {})
                        }
                    }
                }
            }
        }

    @Test
    fun chipsLight() {
        show(MatronAppearance.Light).use {
            node(By.desc("Save quarterly-plan.pdf"))
            screenshot("chips-light")
        }
    }

    @Test
    fun chipsDark() {
        show(MatronAppearance.Dark).use {
            node(By.desc("Save quarterly-plan.pdf"))
            screenshot("chips-dark")
        }
    }

    @Test
    fun saveOpensThePickerWithTheFilesName() {
        show(MatronAppearance.Light).use {
            node(By.desc("Save quarterly-plan.pdf")).click()
            node(By.text("quarterly-plan.pdf").pkg(Pattern.compile(".*documentsui.*")))
            screenshot("save-picker")
            device.pressBack()
            device.pressBack()
        }
    }
}
