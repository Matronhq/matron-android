package chat.matron.android.features

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import chat.matron.android.auth.AuthError
import chat.matron.android.auth.FakeAuthService
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.features.onboarding.FORGOT_PASSWORD_LINK_TAG
import chat.matron.android.features.onboarding.FORGOT_PASSWORD_SUBMIT_TAG
import chat.matron.android.features.onboarding.SignInScreen
import chat.matron.android.features.settings.ACCOUNT_EMAIL_EDIT_TAG
import chat.matron.android.features.settings.ACCOUNT_EMAIL_SAVE_TAG
import chat.matron.android.features.settings.DeviceSettingsScreen
import chat.matron.android.journal.AccountEmail
import chat.matron.android.journal.AccountEmailProviding
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.PasswordChange
import chat.matron.android.journal.PasswordChanging
import chat.matron.android.journal.Rendezvous
import chat.matron.android.journal.RendezvousPollResult
import chat.matron.android.journal.RelayRendezvousing
import chat.matron.android.models.UserSession
import chat.matron.android.viewmodels.AccountEmailViewModel
import chat.matron.android.viewmodels.ChangePasswordViewModel
import chat.matron.android.viewmodels.LinkSignInViewModel
import chat.matron.android.viewmodels.RendezvousSignInViewModel
import chat.matron.android.viewmodels.SignInViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// "Forgot password?" on the sign-in screen, and Settings → Security's
/// "Email address".
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ForgotPasswordScreensTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private object NoRelay : RelayRendezvousing {
        override suspend fun createRendezvous(): Rendezvous = throw IllegalStateException("unused")
        override suspend fun pollRendezvous(rid: String, secret: String): RendezvousPollResult =
            throw IllegalStateException("unused")
        override suspend fun offerRendezvous(rid: String, box: String) = throw IllegalStateException("unused")
    }

    private fun showSignIn(fake: FakeAuthService) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val link = LinkSignInViewModel(fake, "Matron Tests", scope)
        compose.setContent {
            MatronTheme(appearance = MatronAppearance.Light) {
                SignInScreen(
                    viewModel = SignInViewModel(fake, "Matron Tests"),
                    linkViewModel = link,
                    rendezvousViewModel = RendezvousSignInViewModel(NoRelay, link, scope),
                    onSignedIn = {},
                )
            }
        }
    }

    private fun waitForText(text: String) =
        compose.waitUntil(5000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun forgotPasswordIsOfferedOnlyWhereTheJournalMailsLinks() {
        val fake = FakeAuthService()
        fake.stubbedResetAvailable["https://journal.example.com"] = true
        showSignIn(fake)

        compose.onNodeWithText("Server URL").performTextInput("https://other.example.com")
        compose.mainClock.advanceTimeBy(1000)
        compose.waitUntil(5000) { fake.resetAvailableChecks.contains("https://other.example.com") }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(FORGOT_PASSWORD_LINK_TAG).fetchSemanticsNodes().size)

        compose.onNodeWithText("Server URL").performTextReplacement("https://journal.example.com")
        compose.onNodeWithText("Username").performTextInput("alice")
        compose.mainClock.advanceTimeBy(1000)
        waitForText(SignInViewModel.FORGOT_LINK)
        shoot("build/reports/forgot-password-link.png")

        compose.onNodeWithTag(FORGOT_PASSWORD_LINK_TAG).performClick()
        compose.onNodeWithText(SignInViewModel.FORGOT_IDENTIFIER).assertExists()
        compose.onNodeWithTag(FORGOT_PASSWORD_SUBMIT_TAG).assertIsEnabled()
        shoot("build/reports/forgot-password-form.png")

        compose.onNodeWithTag(FORGOT_PASSWORD_SUBMIT_TAG).performClick()
        waitForText(SignInViewModel.FORGOT_SENT)
        assertEquals("alice", fake.resetRequests.single().second)
        shoot("build/reports/forgot-password-sent.png")

        compose.onNodeWithText(SignInViewModel.FORGOT_BACK).performClick()
        compose.onNodeWithText("Sign in").assertExists()
    }

    @Test
    fun tooManyRequests() {
        val fake = FakeAuthService()
        fake.stubbedResetAvailable["https://journal.example.com"] = true
        fake.stubbedResetRequest = Result.failure(AuthError.RateLimited)
        showSignIn(fake)
        compose.onNodeWithText("Server URL").performTextInput("https://journal.example.com")
        compose.mainClock.advanceTimeBy(1000)
        waitForText(SignInViewModel.FORGOT_LINK)
        compose.onNodeWithTag(FORGOT_PASSWORD_LINK_TAG).performClick()
        compose.onNodeWithTag(FORGOT_PASSWORD_SUBMIT_TAG).assertIsNotEnabled()
        compose.onNodeWithText(SignInViewModel.FORGOT_IDENTIFIER).performTextInput("alice@example.com")
        compose.onNodeWithTag(FORGOT_PASSWORD_SUBMIT_TAG).performClick()
        waitForText("Too many requests. Try again in a few minutes.")
        shoot("build/reports/forgot-password-rate-limited.png")
    }

    private class EmailApi(var account: AccountEmail) : AccountEmailProviding {
        val answers = ArrayDeque<Result<String?>>()
        override suspend fun accountEmail() = account
        override suspend fun setAccountEmail(password: String, email: String?): String? =
            answers.removeFirst().getOrThrow()
    }

    private object NoPasswordChange : PasswordChanging {
        override suspend fun changePassword(oldPassword: String, newPassword: String, signOutOthers: Boolean) =
            PasswordChange(0)
    }

    private fun showSettings(api: EmailApi) {
        compose.setContent {
            MatronTheme(appearance = MatronAppearance.Light) {
                DeviceSettingsScreen(
                    session = UserSession("alice", "1", "https://journal.example.com", "t"),
                    devicesApi = null, appearance = MatronAppearance.Light, onAppearanceChange = {},
                    onManageDevices = {},
                    changePassword = ChangePasswordViewModel(NoPasswordChange),
                    accountEmail = AccountEmailViewModel(api),
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun settingTheAddress() {
        val api = EmailApi(AccountEmail(null, resetByEmail = true))
        api.answers += Result.failure(JournalApiError.EmailTaken)
        api.answers += Result.success("alice@example.com")
        showSettings(api)
        waitForText(AccountEmailViewModel.NOT_SET)
        shoot("build/reports/account-email-not-set.png")

        compose.onNodeWithTag(ACCOUNT_EMAIL_EDIT_TAG).performClick()
        compose.onNodeWithTag(ACCOUNT_EMAIL_SAVE_TAG).assertIsNotEnabled()
        compose.onNodeWithText(AccountEmailViewModel.PASSWORD).performTextInput("secret-pw")
        // The field is the second node labelled "Email address" (the first is the row title).
        compose.onAllNodesWithText(AccountEmailViewModel.FIELD)[1].performTextInput("bob@example.com")
        compose.onNodeWithTag(ACCOUNT_EMAIL_SAVE_TAG).performClick()
        waitForText("Another account already uses that address.")
        shoot("build/reports/account-email-taken.png")

        compose.onNodeWithText(AccountEmailViewModel.PASSWORD).performTextInput("secret-pw")
        compose.onAllNodesWithText(AccountEmailViewModel.FIELD)[1].performTextReplacement("alice@example.com")
        compose.onNodeWithTag(ACCOUNT_EMAIL_SAVE_TAG).performClick()
        waitForText("alice@example.com")
        compose.onNodeWithTag(ACCOUNT_EMAIL_EDIT_TAG).assertExists()
        shoot("build/reports/account-email-set.png")
    }

    @Test
    fun aJournalThatCantSendMailSaysSo() {
        showSettings(EmailApi(AccountEmail("alice@example.com", resetByEmail = false)))
        waitForText("Used to reset your password if you forget it. This journal can't send email yet.")
        compose.onNodeWithTag(ACCOUNT_EMAIL_EDIT_TAG).performClick()
        compose.onNodeWithText(AccountEmailViewModel.REMOVE).assertExists()
        shoot("build/reports/account-email-edit-no-mail.png")
    }

    private fun shoot(path: String) {
        val screenshot = compose.runOnIdle {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val output = File(path)
        output.parentFile?.mkdirs()
        output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
