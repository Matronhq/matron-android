package chat.matron.android.features.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import chat.matron.android.features.chat.ConvoNotifyMenu
import chat.matron.android.journal.NotifyChange
import chat.matron.android.journal.NotifyDeviceLevel
import chat.matron.android.journal.NotifySettings
import chat.matron.android.journal.NotifySettingsProviding
import chat.matron.android.journal.NotifyView
import chat.matron.android.viewmodels.NotifySettingsStore
import java.io.File
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings → Notifications and a chat's Notifications menu, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class NotificationSettingsScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "notifications").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private val now = System.currentTimeMillis()
    private val titles = mapOf("c-release" to "Release notes for 2.4", "c-inbox" to "Inbox triage")

    private fun store(view: NotifyView): NotifySettingsStore {
        val api = object : NotifySettingsProviding {
            var stored = view
            override suspend fun notifySettings() = stored
            override suspend fun updateNotify(change: NotifyChange): NotifyView =
                change.appliedTo(stored).also { stored = it }
        }
        return NotifySettingsStore(api, { emptyFlow() }, { emptyFlow() }).also { runBlocking { it.refresh() } }
    }

    private val overrides = NotifyView(
        NotifySettings(
            NotifySettings.Mode.COORDINATOR,
            NotifySettings.preset(NotifySettings.Mode.COORDINATOR)!!,
            hasCoordinator = true,
            convos = listOf(
                NotifySettings.ConvoOverride("c-release", level = NotifySettings.ConvoLevel.NONE),
                NotifySettings.ConvoOverride("c-inbox", muteUntil = now + 2 * 60 * 60 * 1000L),
            ),
        ),
        NotifyDeviceLevel.ALL,
    )

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding(),
                        ) { content() }
                    }
                }
            }
        }

    private fun settingsScreen(appearance: MatronAppearance, suffix: String) {
        val store = store(overrides)
        show(appearance) {
            NotificationSettingsScreen(store = store, title = { titles[it] ?: "Conversation" }, onBack = {})
        }.use {
            node(By.text("Coordinator mode"))
            node(By.text("Permission prompts & secret requests"))
            screenshot("settings-top-$suffix")
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 5, 30)
            node(By.text("Inbox triage"))
            node(By.text("None"))
            screenshot("settings-bottom-$suffix")
        }
    }

    @Test fun settingsLight() = settingsScreen(MatronAppearance.Light, "light")

    @Test fun settingsDark() = settingsScreen(MatronAppearance.Dark, "dark")

    @Test
    fun switchingAnEventMovesToCustom() {
        val store = store(overrides)
        show(MatronAppearance.Light) {
            NotificationSettingsScreen(store = store, title = { titles[it] ?: "Conversation" }, onBack = {})
        }.use {
            node(By.text("Other sessions finished a turn")).click()
            node(By.text("Your own choice of the events below"))
            device.wait(Until.gone(By.text("Changing a switch moves you to Custom.")), timeout)
            screenshot("settings-custom-light")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun chatMenu(appearance: MatronAppearance, suffix: String) {
        val store = store(overrides)
        show(appearance) {
            TopAppBar(
                title = { Text("Inbox triage") },
                actions = {
                    Box {
                        IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, contentDescription = "Chat options") }
                        ConvoNotifyMenu(store = store, convoID = "c-inbox", expanded = true, onDismiss = {})
                    }
                },
            )
        }.use {
            node(By.text("Default (follow mode)"))
            node(By.textStartsWith("Unmute (muted until"))
            screenshot("chat-menu-$suffix")
        }
    }

    @Test fun chatMenuLight() = chatMenu(MatronAppearance.Light, "light")

    @Test fun chatMenuDark() = chatMenu(MatronAppearance.Dark, "dark")
}
