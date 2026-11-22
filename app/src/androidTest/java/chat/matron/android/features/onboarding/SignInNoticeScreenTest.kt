package chat.matron.android.features.onboarding

import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.MatronApplication
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.journal.RelayApi
import chat.matron.android.models.UserSession
import chat.matron.android.viewmodels.LinkSignInViewModel
import chat.matron.android.viewmodels.RendezvousSignInViewModel
import chat.matron.android.viewmodels.SignInViewModel
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The sign-in screen after the server signed this device out, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class SignInNoticeScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    // Optional review artifacts, containing only the fixtures below.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "signin-notice").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private val revoked = UserSession(
        userID = "alice", deviceID = "7", homeserverURL = "https://journal.example.com/", accessToken = "t",
    )

    private fun show(appearance: MatronAppearance, signedOutRemotely: UserSession?) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                val deps = (activity.application as MatronApplication).dependencies
                activity.setContent {
                    val scope = rememberCoroutineScope()
                    val link = remember {
                        LinkSignInViewModel(auth = deps.auth, deviceDisplayName = "Matron Android", scope = scope, haptics = deps.haptics)
                    }
                    val vm = remember { SignInViewModel(deps.auth, "Matron Android", signedOutRemotely = signedOutRemotely) }
                    val rendezvous = remember {
                        RendezvousSignInViewModel(
                            relay = RelayApi(client = deps.sharedClient), link = link, scope = scope, haptics = deps.haptics,
                        )
                    }
                    MatronTheme(appearance) {
                        SignInScreen(
                            viewModel = vm,
                            linkViewModel = link,
                            rendezvousViewModel = rendezvous,
                            onSignedIn = {},
                        )
                    }
                }
            }
        }

    @Test
    fun signedOutByTheServer_saysWhy_andFillsInTheAccount() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance, revoked).use {
                assertNotNull(device.wait(Until.findObject(By.text(SignInViewModel.SIGNED_OUT_REMOTELY_NOTICE)), 5_000))
                assertNotNull(device.findObject(By.text("alice")))
                assertNotNull(device.findObject(By.text("https://journal.example.com/")))
                screenshot("signed-out-remotely-${appearance.name.lowercase()}")
            }
        }
    }

    @Test
    fun anOrdinarySignIn_hasNoNotice() {
        show(MatronAppearance.Light, signedOutRemotely = null).use {
            assertNotNull(device.wait(Until.findObject(By.text("Sign in to Matron")), 5_000))
            assertTrue(device.findObject(By.text(SignInViewModel.SIGNED_OUT_REMOTELY_NOTICE)) == null)
            screenshot("ordinary-sign-in")
        }
    }
}
