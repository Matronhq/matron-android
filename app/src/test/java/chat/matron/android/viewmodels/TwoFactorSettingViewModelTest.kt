package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.TwoFactorEnabled
import chat.matron.android.journal.TwoFactorProviding
import chat.matron.android.journal.TwoFactorSetup
import chat.matron.android.journal.TwoFactorStatus
import chat.matron.android.viewmodels.TwoFactorSettingViewModel.ConfirmAction
import chat.matron.android.viewmodels.TwoFactorSettingViewModel.SetupPhase
import chat.matron.android.viewmodels.TwoFactorSettingViewModel.Sheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoFactorSettingViewModelTest {
    private class Api(var answer: Result<TwoFactorStatus>) : TwoFactorProviding {
        val calls = mutableListOf<String>()
        var setupAnswer: Result<TwoFactorSetup> = Result.success(SETUP)
        var enableAnswer: Result<TwoFactorEnabled> = Result.success(TwoFactorEnabled(CODES, signedOut = 0))
        var confirmFailure: Throwable? = null

        override suspend fun twoFactorStatus(): TwoFactorStatus = answer.getOrThrow()

        override suspend fun twoFactorSetup(password: String, code: String?): TwoFactorSetup {
            calls += "setup $password ${code ?: "-"}"
            return setupAnswer.getOrThrow()
        }

        override suspend fun twoFactorEnable(code: String, signOutOthers: Boolean): TwoFactorEnabled {
            calls += "enable $code $signOutOthers"
            return enableAnswer.getOrThrow()
        }

        override suspend fun twoFactorDisable(password: String, code: String) {
            calls += "disable $password $code"
            confirmFailure?.let { throw it }
        }

        override suspend fun twoFactorRecoveryCodes(password: String, code: String): List<String> {
            calls += "recovery $password $code"
            confirmFailure?.let { throw it }
            return CODES
        }
    }

    private companion object {
        val SETUP = TwoFactorSetup("JBSWY3DPEHPK3PXP", "otpauth://totp/Matron:alice@example.com", "Matron", "alice@example.com")
        val CODES = listOf("aaaa-bbbb-cccc-dddd", "eeee-ffff-gggg-hhhh")
    }

    private val on = TwoFactorStatus(enabled = true, recoveryCodesLeft = 8, required = false, available = true)
    private val off = TwoFactorStatus(enabled = false, recoveryCodesLeft = null, required = false, available = true)

    @Test
    fun loadsTheStatus() = runBlocking {
        val vm = TwoFactorSettingViewModel(Api(Result.success(on)))
        assertEquals(TwoFactorSettingViewModel.State.Loading, vm.state.value)
        vm.load()
        assertEquals(TwoFactorSettingViewModel.State.Loaded(on), vm.state.value)
    }

    @Test
    fun aJournalWithoutTwoFactorHidesTheSection() = runBlocking {
        val vm = TwoFactorSettingViewModel(Api(Result.failure(JournalApiError.NotFound)))
        vm.load()
        assertEquals(TwoFactorSettingViewModel.State.Unsupported, vm.state.value)
    }

    @Test
    fun aFailedReloadKeepsWhatShows() = runBlocking {
        val api = Api(Result.success(on))
        val vm = TwoFactorSettingViewModel(api)
        vm.load()
        api.answer = Result.failure(JournalApiError.Transport("offline"))
        vm.load()
        assertEquals(TwoFactorSettingViewModel.State.Loaded(on), vm.state.value)
    }

    @Test
    fun aFailedFirstLoadSaysSo() = runBlocking {
        val vm = TwoFactorSettingViewModel(Api(Result.failure(JournalApiError.Transport("offline"))))
        vm.load()
        assertEquals(
            TwoFactorSettingViewModel.State.Failed("Couldn't load two-factor settings: Couldn't reach the server — offline"),
            vm.state.value,
        )
    }

    @Test
    fun setUp_passwordThenCode_showsTheRecoveryCodes_andDoneReloads() = runBlocking {
        val api = Api(Result.success(off))
        api.enableAnswer = Result.success(TwoFactorEnabled(CODES, signedOut = 2))
        val vm = TwoFactorSettingViewModel(api)
        vm.load()
        vm.openSetUp()
        vm.submitPassword("pw")
        assertEquals(Sheet.Setup(moving = false, phase = SetupPhase.Scan, setup = SETUP), vm.sheet.value)

        vm.confirmSetup(" 123456 ", signOutOthers = true)
        assertEquals(
            Sheet.Setup(moving = false, phase = SetupPhase.Codes, recoveryCodes = CODES, signedOut = 2),
            vm.sheet.value,
        )
        assertEquals(listOf("setup pw -", "enable 123456 true"), api.calls)

        api.answer = Result.success(on)
        vm.closeSheet()
        assertNull(vm.sheet.value)
        assertEquals(TwoFactorSettingViewModel.State.Loaded(on), vm.state.value)
    }

    @Test
    fun setUp_aWrongPasswordStaysOnThePassword() = runBlocking {
        val api = Api(Result.success(off))
        api.setupAnswer = Result.failure(JournalApiError.BadPassword)
        val vm = TwoFactorSettingViewModel(api)
        vm.openSetUp()
        vm.submitPassword("nope")
        assertEquals(Sheet.Setup(moving = false, error = "That password is incorrect."), vm.sheet.value)
    }

    @Test
    fun setUp_aWrongCodeStaysOnTheScan() = runBlocking {
        val api = Api(Result.success(off))
        api.enableAnswer = Result.failure(JournalApiError.BadCode(ticketValid = true))
        val vm = TwoFactorSettingViewModel(api)
        vm.openSetUp()
        vm.submitPassword("pw")
        vm.confirmSetup("000000", signOutOthers = false)
        val sheet = vm.sheet.value as Sheet.Setup
        assertEquals(SetupPhase.Scan, sheet.phase)
        assertEquals("That code didn't work. Check your authenticator app and try again.", sheet.error)
    }

    @Test
    fun setUp_anExpiredSetupGoesBackToThePassword() = runBlocking {
        val api = Api(Result.success(off))
        api.enableAnswer = Result.failure(JournalApiError.TwoFactorConflict("no_pending_setup"))
        val vm = TwoFactorSettingViewModel(api)
        vm.openSetUp()
        vm.submitPassword("pw")
        vm.confirmSetup("123456", signOutOthers = false)
        assertEquals(Sheet.Setup(moving = false, error = "That setup expired. Start again."), vm.sheet.value)
    }

    @Test
    fun changeApp_needsTheCurrentCode_andNeverSignsOutOthers() = runBlocking {
        val api = Api(Result.success(on))
        val vm = TwoFactorSettingViewModel(api)
        vm.openChangeApp()
        vm.submitPassword("pw", currentCode = " ")
        assertTrue("no code, no request", api.calls.isEmpty())
        vm.submitPassword("pw", currentCode = "654321")
        vm.confirmSetup("123456", signOutOthers = true)
        assertEquals(listOf("setup pw 654321", "enable 123456 false"), api.calls)
        assertEquals(SetupPhase.Codes, (vm.sheet.value as Sheet.Setup).phase)
    }

    @Test
    fun newRecoveryCodes_showsTheNewSet() = runBlocking {
        val api = Api(Result.success(on))
        val vm = TwoFactorSettingViewModel(api)
        vm.openNewRecoveryCodes()
        vm.confirmAction("pw", "123456")
        assertEquals(Sheet.Confirm(ConfirmAction.RecoveryCodes, recoveryCodes = CODES), vm.sheet.value)
        assertEquals(listOf("recovery pw 123456"), api.calls)
    }

    @Test
    fun turnOff_closesTheSheetAndReloads() = runBlocking {
        val api = Api(Result.success(on))
        val vm = TwoFactorSettingViewModel(api)
        vm.load()
        vm.openTurnOff()
        api.answer = Result.success(off)
        vm.confirmAction("pw", "123456")
        assertNull(vm.sheet.value)
        assertEquals(TwoFactorSettingViewModel.State.Loaded(off), vm.state.value)
    }

    @Test
    fun turnOff_whileRequired_saysWhy() = runBlocking {
        val api = Api(Result.success(on))
        api.confirmFailure = JournalApiError.TwoFactorConflict("two_factor_required")
        val vm = TwoFactorSettingViewModel(api)
        vm.openTurnOff()
        vm.confirmAction("pw", "123456")
        assertEquals(
            Sheet.Confirm(
                ConfirmAction.TurnOff,
                error = "This server requires two-factor sign-in, so it can't be turned off.",
            ),
            vm.sheet.value,
        )
    }

    @Test
    fun anAnswerAfterCancelIsDropped() = runBlocking {
        val api = object : TwoFactorProviding by Api(Result.success(off)) {
            lateinit var vm: TwoFactorSettingViewModel
            override suspend fun twoFactorSetup(password: String, code: String?): TwoFactorSetup {
                vm.closeSheet()
                return SETUP
            }
        }
        val vm = TwoFactorSettingViewModel(api)
        api.vm = vm
        vm.openSetUp()
        vm.submitPassword("pw")
        assertNull(vm.sheet.value)
    }

    @Test
    fun anAnswerAfterReopeningIsDropped() = runBlocking {
        val api = object : TwoFactorProviding by Api(Result.success(off)) {
            lateinit var vm: TwoFactorSettingViewModel
            override suspend fun twoFactorSetup(password: String, code: String?): TwoFactorSetup {
                vm.closeSheet()
                vm.openSetUp()
                return SETUP
            }
        }
        val vm = TwoFactorSettingViewModel(api)
        api.vm = vm
        vm.openSetUp()
        vm.submitPassword("pw")
        // The fresh Set Up sheet stays on its password step.
        assertEquals(Sheet.Setup(moving = false), vm.sheet.value)
    }

    @Test
    fun copy() {
        assertEquals("On since 1 Oct 2026 · 8 recovery codes left", TwoFactorSettingViewModel.detailText(on.copy(enabledAtMillis = 1L)) { "1 Oct 2026" })
        assertEquals("On · 1 recovery code left", TwoFactorSettingViewModel.detailText(on.copy(recoveryCodesLeft = 1)))
        assertEquals("Off. Signing in takes only your password.", TwoFactorSettingViewModel.detailText(off))
        assertEquals(
            "This server isn't set up for two-factor sign-in.",
            TwoFactorSettingViewModel.detailText(off.copy(available = false)),
        )
        assertTrue(TwoFactorSettingViewModel.runningLow(on.copy(recoveryCodesLeft = 2)))
        assertFalse(TwoFactorSettingViewModel.runningLow(on.copy(recoveryCodesLeft = 3)))
        assertFalse(TwoFactorSettingViewModel.runningLow(off))
        assertEquals("Two-factor sign-in is on.", TwoFactorSettingViewModel.setupDone(moving = false, signedOut = 0))
        assertEquals(
            "Two-factor sign-in is on. Signed out 1 other device.",
            TwoFactorSettingViewModel.setupDone(moving = false, signedOut = 1),
        )
        assertEquals("Your new app is set up.", TwoFactorSettingViewModel.setupDone(moving = true, signedOut = 0))
    }
}
