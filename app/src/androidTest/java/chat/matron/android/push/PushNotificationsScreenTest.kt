package chat.matron.android.push

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import chat.matron.android.features.settings.NotificationSettingsScreen
import chat.matron.android.journal.NotifyChange
import chat.matron.android.journal.NotifyDeviceLevel
import chat.matron.android.journal.NotifySettings
import chat.matron.android.journal.NotifySettingsProviding
import chat.matron.android.journal.NotifyView
import chat.matron.android.viewmodels.NotifySettingsStore
import chat.matron.android.viewmodels.NotifySettingsTitles
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Push on a device, with fixture data only: the notification permission
 * prompt, FCM messages handed to the same handler the messaging service
 * uses (boxes sealed by the journal's own code, see PushBoxTest) and the
 * shade showing them opened, and Settings → Notifications saying push works.
 * No Firebase project, server or account is involved.
 */
@RunWith(AndroidJUnit4::class)
class PushNotificationsScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private val timeout = 5_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(700)
        val directory = File(context.getExternalFilesDir(null), "push").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
        Thread.sleep(300)
    }

    private val recipient = MessageDigest.getInstance("SHA-256").digest("fixture recipient".toByteArray())

    // Sealed by matron-journal's sealBox to the fixture recipient (PushBoxTest).
    private val itemBox =
        "G4aKnRf2vZxh3BsOM5i4sssxih3f_a0As-OjTNPknmJPu9gBrj5b7QD3P1YAJT659tWiky9qnXoh6rqKW86qHL-ILobGFEVCuz5SzkCJqJa1OqTFFCMSuEeCPQaJWJcBLtSP5sp0qHOifVkhJhyymioYjjiWX6pKPZzGNNCbolTNXht58EP4jSG77-JHUt8pr_5K1fC1_evbMiBDmfhWaMGQw6zVgnGHm51nV93GfRuiFzKdB5q5JROGQ3_tetsnsYzaJvjRF0rAr9m5-ey0PiFvjti741GA8usQN65AG6Zc023j0k4SZs2z9av14mVksiZcv-_TwAJ7PpzCFeZJ7_NAU12_SMF0svE-TJrkpQu-C70LuC4jq1pjkS03OFOD-4yCQ6IAo_JvwC4HQPj6juVKB15HYu8YTf_4GVwdhSifxNdT-QrDduh-cDHppWvcqgU5bvOBz6VPd5-qMztYi_PKf_JwIFePugOvN5m38XEQZ0YVlcbTPndgRbNCeJErnZ3jga2_wAKzotI_Ms8O9FVQDQ-Al1AKYrcLKXKSpeuNl2YSshb56VvmtZSYx3iE5-JIUsvE5mHVBELS9oDsj5nrZqEsIS8zJl-FgvBTZTaFZ2qxgwXRcTvIB6pLnqFzdvVhYSHbucwNFGzXobans61FJmKFPgBvhPFFVwePXJ4GjUOhw0O3bSCTgM0CpWV02GNHJwapD5uh2WpIGVSBx0js78_VlIcyYlbK1x3fOow"
    private val textBox =
        "61PF9q6oQRpD8AwfeNGiSu4DHiIhtpAbarOIUTBoLHGgE-QCn7ADFGcc-4JN2ap8fSqpcZG7RnWyDwKmuCqjNHFADK3p85Hz-zVUG_p-kpfCSN2kafte3RMU2l93kSAlnfmzg8OI2edyy5CwtehYVLyV_9Bz2uV5lOZMRCKcZqMHs56hjaYrYWExOKTcjDglkprNoNx-4yQst_X5DbCXrhrtC8kM_xZEbBcXS-jV7fgD0ROFWM2_YtJ-VtIbcUWjUxUvxBc4s8QcnjT2rITRFb27d5RZysEaGddKjrUQX2GiG12wwwCbtC_1osYjAnPeTG5bfpsLCRrl-MUfOUH6JVuEBIfSs4hCdUDZ3A0qRFrcuqQXdlxS-LJ2bgWDYbPZz2xAccpapq7bdZ0DH2RUiQ"

    @After
    fun tidy() {
        PushNotifier(context).cancelAll()
        device.pressHome()
    }

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) { content() }
                    }
                }
            }
        }

    /// The system prompt the signed-in app raises (the same contract
    /// PushRegistrationEffect uses). Run it from a state where the app was
    /// never asked: revoking from inside the test would kill its process, so
    /// that is done first from the host (`pm revoke` and
    /// `pm clear-permission-flags … user-set user-fixed`).
    @Test
    fun permissionPrompt() {
        show(MatronAppearance.Light) {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }.use {
            node(By.textContains("send you notifications"))
            screenshot("permission-prompt")
            node(By.text("Allow")).click()
            device.wait(Until.gone(By.textContains("send you notifications")), timeout)
        }
    }

    /// Two journal messages through the handler: an item question with its
    /// buttons and a finished turn, both opened from their sealed boxes.
    @Test
    fun sealedNotificationsInTheShade() {
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        val handler = PushMessageHandler(PushNotifier(context), privateKey = { recipient }, signedIn = { true })
        assertEquals(
            PushMessageHandler.Outcome.SHOWN,
            handler.handle(mapOf("type" to "alert", "convo_id" to "c-2", "seq" to "9", "kind" to "done", "box" to textBox, "badge" to "2")),
        )
        assertEquals(
            PushMessageHandler.Outcome.SHOWN,
            handler.handle(mapOf("type" to "alert", "convo_id" to "c-9", "seq" to "314", "kind" to "attention", "item_id" to "it_77", "box" to itemBox, "badge" to "3")),
        )
        device.openNotification()
        node(By.text("Deploy to box-a? ✅"))
        node(By.text("Migrations"))
        // Expand the question to show its buttons, if the shade has not.
        // The system bundles the app's notifications; the chevron on the
        // question's own row is the one level with its title.
        if (!device.hasObject(By.text("Ask bob"))) {
            val titleY = node(By.text("Deploy to box-a? ✅")).visibleCenter.y
            device.findObjects(By.res("android:id/expand_button"))
                .minByOrNull { kotlin.math.abs(it.visibleCenter.y - titleY) }
                ?.click()
        }
        screenshot("shade-sealed")
        node(By.text("Ask bob"))
    }

    private fun settings(appearance: MatronAppearance, suffix: String) {
        val view = NotifyView(
            NotifySettings(NotifySettings.Mode.COORDINATOR, NotifySettings.preset(NotifySettings.Mode.COORDINATOR)!!, hasCoordinator = true, convos = emptyList()),
            NotifyDeviceLevel.ALL,
        )
        val api = object : NotifySettingsProviding {
            var stored = view
            override suspend fun notifySettings() = stored
            override suspend fun updateNotify(change: NotifyChange): NotifyView = change.appliedTo(stored).also { stored = it }
        }
        val store = NotifySettingsStore(api, { emptyFlow() }, { emptyFlow() }).also { runBlocking { it.refresh() } }
        show(appearance) {
            NotificationSettingsScreen(store = store, title = { "Conversation" }, onBack = {}, pushNote = NotifySettingsTitles.PUSH_WORKS)
        }.use {
            node(By.text("Coordinator mode"))
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 5, 30)
            node(By.text(NotifySettingsTitles.PUSH_WORKS))
            screenshot("settings-push-works-$suffix")
        }
    }

    @Test fun settingsLight() = settings(MatronAppearance.Light, "light")

    @Test fun settingsDark() = settings(MatronAppearance.Dark, "dark")
}
