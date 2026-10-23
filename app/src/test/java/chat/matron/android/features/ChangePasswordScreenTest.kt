package chat.matron.android.features

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.features.settings.CHANGE_PASSWORD_ROW_TAG
import chat.matron.android.features.settings.CHANGE_PASSWORD_SUBMIT_TAG
import chat.matron.android.features.settings.DeviceSettingsScreen
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.PasswordChange
import chat.matron.android.journal.PasswordChanging
import chat.matron.android.journal.TwoFactorProviding
import chat.matron.android.journal.TwoFactorStatus
import chat.matron.android.models.UserSession
import chat.matron.android.viewmodels.ChangePasswordViewModel
import chat.matron.android.viewmodels.TwoFactorSettingViewModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// Settings → Security's Change password: the row opens the form, the
/// button waits for every field, a refusal shows under the form and a success
/// closes it with the count of other devices signed out.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChangePasswordScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private class Api : PasswordChanging {
        val answers = ArrayDeque<Result<PasswordChange>>()
        val calls = mutableListOf<Boolean>()
        override suspend fun changePassword(oldPassword: String, newPassword: String, signOutOthers: Boolean): PasswordChange {
            calls += signOutOthers
            return answers.removeFirst().getOrThrow()
        }
    }

    private object TwoFactorOn : TwoFactorProviding {
        override suspend fun twoFactorStatus() =
            TwoFactorStatus(enabled = true, recoveryCodesLeft = 8, required = false, available = true)
        override suspend fun twoFactorSetup(password: String, code: String?) = error("unused")
        override suspend fun twoFactorEnable(code: String, signOutOthers: Boolean) = error("unused")
        override suspend fun twoFactorDisable(password: String, code: String) = error("unused")
        override suspend fun twoFactorRecoveryCodes(password: String, code: String) = error("unused")
    }

    private fun show(api: Api) {
        compose.setContent {
            MatronTheme(appearance = MatronAppearance.Light) {
                DeviceSettingsScreen(
                    session = UserSession("alice", "1", "https://journal.example.com", "t"),
                    devicesApi = null, appearance = MatronAppearance.Light, onAppearanceChange = {},
                    onManageDevices = {},
                    twoFactor = TwoFactorSettingViewModel(TwoFactorOn),
                    changePassword = ChangePasswordViewModel(api),
                    onBack = {},
                )
            }
        }
    }

    private fun fill(current: String, new: String) {
        compose.onNodeWithText(ChangePasswordViewModel.CURRENT).performTextInput(current)
        compose.onNodeWithText(ChangePasswordViewModel.NEW).performTextInput(new)
        compose.onNodeWithText(ChangePasswordViewModel.CONFIRM).performTextInput(new)
    }

    @Test
    fun aWrongPasswordThenASuccess() {
        val api = Api()
        api.answers += Result.failure(JournalApiError.BadPassword)
        api.answers += Result.success(PasswordChange(signedOut = 2))
        show(api)

        compose.onNodeWithTag(CHANGE_PASSWORD_ROW_TAG).performClick()
        compose.onNodeWithTag(CHANGE_PASSWORD_SUBMIT_TAG).assertIsNotEnabled()
        compose.onNodeWithText(ChangePasswordViewModel.SIGN_OUT_OTHERS_HELP).assertExists()
        fill("old-secret", "new-secret")
        compose.onNodeWithTag(CHANGE_PASSWORD_SUBMIT_TAG).assertIsEnabled()
        shoot("build/reports/change-password-form.png")

        compose.onNodeWithTag(CHANGE_PASSWORD_SUBMIT_TAG).performClick()
        compose.waitUntil(3000) {
            compose.onAllNodesWithText("That password is incorrect.").fetchSemanticsNodes().isNotEmpty()
        }
        // Still signed in, still on the form; only the current password is gone.
        compose.onNodeWithTag(CHANGE_PASSWORD_SUBMIT_TAG).assertIsNotEnabled()
        shoot("build/reports/change-password-wrong-password.png")

        compose.onNodeWithText(ChangePasswordViewModel.CURRENT).performTextInput("right-secret")
        compose.onNodeWithTag(CHANGE_PASSWORD_SUBMIT_TAG).assertIsEnabled()
        compose.onNodeWithTag(CHANGE_PASSWORD_SUBMIT_TAG).performClick()
        compose.waitUntil(3000) {
            compose.onAllNodesWithText("Password changed. Signed out of 2 other devices.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(CHANGE_PASSWORD_ROW_TAG).assertExists()
        assertEquals(listOf(true, true), api.calls)
        shoot("build/reports/change-password-done.png")
    }

    @Test
    fun aMismatchNeverSends() {
        val api = Api()
        show(api)
        compose.onNodeWithTag(CHANGE_PASSWORD_ROW_TAG).performClick()
        compose.onNodeWithText(ChangePasswordViewModel.CURRENT).performTextInput("old-secret")
        compose.onNodeWithText(ChangePasswordViewModel.NEW).performTextInput("new-secret")
        compose.onNodeWithText(ChangePasswordViewModel.CONFIRM).performTextInput("new-secreT")
        compose.onNodeWithTag(CHANGE_PASSWORD_SUBMIT_TAG).performClick()
        compose.onNodeWithText("The new passwords don't match.").assertExists()
        assertEquals(emptyList<Boolean>(), api.calls)
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
