package chat.matron.android.features.approvals

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import chat.matron.android.designsystem.AccountLockedBanner
import chat.matron.android.designsystem.AccountLockedScreen
import chat.matron.android.designsystem.AccountWarningBanner
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.viewmodels.AccountLockMonitor
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The account lock's banner, locked screen and warning, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class AccountLockScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), 5_000)) { "Missing $selector" }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "account-lock").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { MatronTheme(appearance) { Surface(Modifier.fillMaxSize()) { content() } } }
            }
        }

    private val since = 1_795_300_000_000L

    @Test
    fun lockedScreenForAFrozenDevice() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) {
                AccountLockedScreen(since = since, method = "fingerprint", isUnlocking = false, error = null, onUnlock = {}, onSignOut = {})
            }.use {
                node(By.text("Unlock with your fingerprint"))
                node(By.text("Can't unlock? Sign Out of This Device"))
                screenshot("01-locked-${appearance.rawValue}")
            }
        }
    }

    @Test
    fun lockedScreenAfterARefusal() {
        show(MatronAppearance.Light) {
            AccountLockedScreen(
                since = since, method = "fingerprint", isUnlocking = false,
                error = "This device's key can't unlock this lock: it was added too recently, or isn't attached to this device.",
                onUnlock = {}, onSignOut = {},
            )
        }.use {
            node(By.textContains("added too recently"))
            screenshot("02-locked-refused")
        }
    }

    @Test
    fun bannerForASignedOutLockAndTheUnsavedSignInWarning() {
        show(MatronAppearance.Light) {
            Column(Modifier.fillMaxSize()) {
                AccountLockedBanner()
                AccountWarningBanner(AccountLockMonitor.UNSAVED_TOKEN_WARNING, onDismiss = {}, clearStatusBar = false)
                Box(Modifier.weight(1f)) { Text("Chats", style = MaterialTheme.typography.headlineSmall) }
            }
        }.use {
            node(By.text("This account is locked"))
            node(By.text("OK"))
            screenshot("03-banners")
        }
    }
}
