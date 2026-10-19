package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.TwoFactorProviding
import chat.matron.android.journal.TwoFactorStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TwoFactorSettingViewModelTest {
    private class Api(var answer: Result<TwoFactorStatus>) : TwoFactorProviding {
        override suspend fun twoFactorStatus(): TwoFactorStatus = answer.getOrThrow()
    }

    private val on = TwoFactorStatus(enabled = true, recoveryCodesLeft = 8, required = false, available = true)

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
            TwoFactorSettingViewModel.State.Failed("Couldn't load two-factor status: Couldn't reach the server — offline"),
            vm.state.value,
        )
    }

    @Test
    fun copy() {
        assertEquals("Manage two-factor in Matron Web", TwoFactorSettingViewModel.MANAGE)
        assertEquals("On", TwoFactorSettingViewModel.valueText(on))
        assertEquals("8 recovery codes left.", TwoFactorSettingViewModel.detailText(on))
        val off = TwoFactorStatus(enabled = false, recoveryCodesLeft = null, required = false, available = true)
        assertEquals("Off", TwoFactorSettingViewModel.valueText(off))
        assertEquals("Sign-in asks only for your password.", TwoFactorSettingViewModel.detailText(off))
        assertEquals(
            "1 recovery code left. Required for everyone on this server.",
            TwoFactorSettingViewModel.detailText(on.copy(recoveryCodesLeft = 1, required = true)),
        )
        assertEquals(
            "Not available on this server.",
            TwoFactorSettingViewModel.detailText(off.copy(available = false)),
        )
    }
}
