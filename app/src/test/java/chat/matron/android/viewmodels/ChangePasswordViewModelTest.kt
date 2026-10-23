package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.PasswordChange
import chat.matron.android.journal.PasswordChanging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangePasswordViewModelTest {
    private class Api(var answer: Result<PasswordChange>) : PasswordChanging {
        data class Call(val old: String, val new: String, val signOutOthers: Boolean)
        val calls = mutableListOf<Call>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun changePassword(oldPassword: String, newPassword: String, signOutOthers: Boolean): PasswordChange {
            calls += Call(oldPassword, newPassword, signOutOthers)
            gate?.await()
            return answer.getOrThrow()
        }
    }

    private fun filled(api: Api, current: String = "old-secret", new: String = "new-secret", confirm: String = new) =
        ChangePasswordViewModel(api).apply {
            open()
            setCurrentPassword(current)
            setNewPassword(new)
            setConfirmPassword(confirm)
        }

    @Test
    fun opensEmptyWithSignOutOn() {
        val vm = ChangePasswordViewModel(Api(Result.success(PasswordChange(0))))
        assertFalse(vm.state.value.isOpen)
        vm.open()
        val s = vm.state.value
        assertTrue(s.isOpen)
        assertTrue(s.signOutOthers)
        assertEquals("", s.currentPassword)
        assertFalse(s.canSubmit)
    }

    @Test
    fun theButtonWaitsForEveryField() {
        val vm = ChangePasswordViewModel(Api(Result.success(PasswordChange(0))))
        vm.open()
        vm.setCurrentPassword("old-secret")
        vm.setNewPassword("new-secret")
        assertFalse(vm.state.value.canSubmit)
        vm.setConfirmPassword("new-secret")
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun clientSideChecksNeverSend() = runBlocking {
        val api = Api(Result.success(PasswordChange(0)))

        val short = filled(api, new = "short")
        short.submit()
        assertEquals("Use at least 8 characters.", short.state.value.error)

        val mismatch = filled(api, confirm = "new-secreT")
        mismatch.submit()
        assertEquals("The new passwords don't match.", mismatch.state.value.error)

        val same = filled(api, current = "same-secret", new = "same-secret")
        same.submit()
        assertEquals("Choose a password different from your current one.", same.state.value.error)
        // The form keeps what was typed so it can be fixed.
        assertEquals("same-secret", same.state.value.newPassword)

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun editingClearsTheError() = runBlocking {
        val vm = filled(Api(Result.success(PasswordChange(0))), new = "short")
        vm.submit()
        vm.setNewPassword("short-but-longer")
        assertNull(vm.state.value.error)
    }

    @Test
    fun successClosesTheFormAndForgetsThePasswords() = runBlocking {
        val api = Api(Result.success(PasswordChange(signedOut = 2)))
        val vm = filled(api)
        vm.submit()
        assertEquals(listOf(Api.Call("old-secret", "new-secret", true)), api.calls)
        assertEquals(
            ChangePasswordViewModel.State(confirmation = "Password changed. Signed out of 2 other devices."),
            vm.state.value,
        )
    }

    @Test
    fun theSwitchGoesWithTheRequest() = runBlocking {
        val api = Api(Result.success(PasswordChange(signedOut = 0)))
        val vm = filled(api)
        vm.setSignOutOthers(false)
        vm.submit()
        assertEquals(false, api.calls.single().signOutOthers)
        assertEquals("Password changed.", vm.state.value.confirmation)
    }

    @Test
    fun submittingDisablesTheButton() = runBlocking {
        val api = Api(Result.success(PasswordChange(0)))
        api.gate = CompletableDeferred()
        val vm = filled(api)
        val job = async(Dispatchers.Default) { vm.submit() }
        while (!vm.state.value.isSubmitting) yield()
        assertFalse(vm.state.value.canSubmit)
        api.gate!!.complete(Unit)
        job.await()
        assertFalse(vm.state.value.isSubmitting)
    }

    @Test
    fun aRefusalClearsOnlyTheCurrentPassword() = runBlocking {
        val vm = filled(Api(Result.failure(JournalApiError.BadPassword)))
        vm.submit()
        val s = vm.state.value
        assertTrue(s.isOpen)
        assertEquals("That password is incorrect.", s.error)
        assertEquals("", s.currentPassword)
        assertEquals("new-secret", s.newPassword)
        assertEquals("new-secret", s.confirmPassword)
        assertFalse(s.isSubmitting)
        assertFalse(s.canSubmit)
        vm.setCurrentPassword("right-secret")
        assertTrue(vm.state.value.canSubmit)
    }

    @Test
    fun aNetworkErrorAlsoKeepsTheNewPassword() = runBlocking {
        val vm = filled(Api(Result.failure(JournalApiError.Transport("offline"))))
        vm.submit()
        assertEquals("", vm.state.value.currentPassword)
        assertEquals("new-secret", vm.state.value.newPassword)
        assertEquals("new-secret", vm.state.value.confirmPassword)
    }

    @Test
    fun serverErrors() {
        assertEquals("That password is incorrect.", ChangePasswordViewModel.errorText(JournalApiError.BadPassword))
        assertEquals("Use at least 8 characters.", ChangePasswordViewModel.errorText(JournalApiError.WeakPassword))
        assertEquals(
            "Too many attempts. Try again in 5 minutes.",
            ChangePasswordViewModel.errorText(JournalApiError.LockedOut(300)),
        )
        assertEquals(
            "Couldn't change your password: Couldn't reach the server — offline",
            ChangePasswordViewModel.errorText(JournalApiError.Transport("offline")),
        )
    }

    @Test
    fun lockoutRoundsUpToWholeMinutes() {
        assertEquals("Too many attempts. Try again in 1 minute.", ChangePasswordViewModel.lockedOutText(0))
        assertEquals("Too many attempts. Try again in 1 minute.", ChangePasswordViewModel.lockedOutText(45))
        assertEquals("Too many attempts. Try again in 1 minute.", ChangePasswordViewModel.lockedOutText(60))
        assertEquals("Too many attempts. Try again in 2 minutes.", ChangePasswordViewModel.lockedOutText(61))
    }

    @Test
    fun confirmationCountsOtherDevices() {
        assertEquals("Password changed.", ChangePasswordViewModel.confirmationText(0))
        assertEquals("Password changed. Signed out of 1 other device.", ChangePasswordViewModel.confirmationText(1))
        assertEquals("Password changed. Signed out of 3 other devices.", ChangePasswordViewModel.confirmationText(3))
    }

    @Test
    fun cancelForgetsEverything() {
        val vm = filled(Api(Result.success(PasswordChange(0))))
        vm.cancel()
        assertEquals(ChangePasswordViewModel.State(), vm.state.value)
    }

    @Test
    fun theStateNeverPrintsAPassword() {
        val vm = filled(Api(Result.success(PasswordChange(0))))
        val printed = vm.state.value.toString()
        assertFalse(printed.contains("old-secret"))
        assertFalse(printed.contains("new-secret"))
    }
}
