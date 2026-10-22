package chat.matron.android.viewmodels

import chat.matron.android.auth.AuthError
import chat.matron.android.auth.FakeAuthService
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

/// The sign-in form's "Forgot password?": offered only where the journal
/// mails reset links, and always the same answer once asked.
class SignInForgotPasswordViewModelTest {
    private val server = "https://journal.example.com"

    private fun vm(fake: FakeAuthService) = SignInViewModel(fake, "Matron Tests").apply { serverURL = server }

    private suspend fun offered(fake: FakeAuthService): SignInViewModel {
        fake.stubbedResetAvailable[server] = true
        return vm(fake).also { it.checkPasswordReset() }
    }

    @Test
    fun offeredOnlyWhenTheServerSaysSo() = runBlocking {
        val fake = FakeAuthService()
        val vm = vm(fake)
        vm.checkPasswordReset()
        assertFalse(vm.forgotPasswordAvailable.value)
        vm.openForgotPassword()
        assertNull(vm.forgotPassword.value)

        fake.stubbedResetAvailable[server] = true
        vm.checkPasswordReset()
        assertTrue(vm.forgotPasswordAvailable.value)
    }

    @Test
    fun anEmptyServerNeverAsks() = runBlocking {
        val fake = FakeAuthService()
        val vm = SignInViewModel(fake, "Matron Tests")
        vm.checkPasswordReset()
        assertFalse(vm.forgotPasswordAvailable.value)
        assertTrue(fake.resetAvailableChecks.isEmpty())
    }

    @Test
    fun anAnswerForAnEarlierServerIsDropped() = runBlocking {
        val fake = FakeAuthService()
        fake.stubbedResetAvailable[server] = true
        fake.resetAvailableGate = CompletableDeferred()
        val vm = vm(fake)
        val check = async(Dispatchers.Default) { vm.checkPasswordReset() }
        while (fake.resetAvailableChecks.isEmpty()) yield()
        vm.serverURL = "https://other.example.com"
        fake.resetAvailableGate!!.complete(Unit)
        check.await()
        assertFalse(vm.forgotPasswordAvailable.value)
    }

    @Test
    fun changingTheServerHidesItUntilCheckedAgain() = runBlocking {
        val vm = offered(FakeAuthService())
        assertTrue(vm.forgotPasswordAvailable.value)
        vm.serverURL = "https://other.example.com"
        assertFalse(vm.forgotPasswordAvailable.value)
        vm.openForgotPassword()
        assertNull(vm.forgotPassword.value)
        // Only whitespace changed: still the same server.
        vm.serverURL = server
        vm.checkPasswordReset()
        vm.serverURL = "$server "
        assertTrue(vm.forgotPasswordAvailable.value)
    }

    @Test
    fun theFormStartsWithTheTypedUsername() = runBlocking {
        val vm = offered(FakeAuthService())
        vm.username = "alice "
        vm.openForgotPassword()
        assertEquals(SignInViewModel.ForgotPassword(identifier = "alice"), vm.forgotPassword.value)
    }

    @Test
    fun sendingAlwaysSaysTheSameThing() = runBlocking {
        val fake = FakeAuthService()
        val vm = offered(fake)
        vm.openForgotPassword()
        assertFalse(vm.forgotPassword.value!!.canSubmit)
        vm.setForgotIdentifier(" alice@example.com ")
        vm.submitForgotPassword()
        assertEquals("alice@example.com", fake.resetRequests.single().second)
        assertEquals("journal.example.com", fake.resetRequests.single().first.host)
        val form = vm.forgotPassword.value!!
        assertTrue(form.sent)
        assertNull(form.error)
        assertFalse(form.canSubmit)
        vm.closeForgotPassword()
        assertNull(vm.forgotPassword.value)
        assertEquals(SignInViewModel.State.Idle, vm.state.value)
    }

    @Test
    fun tooManyRequests() = runBlocking {
        val fake = FakeAuthService()
        fake.stubbedResetRequest = Result.failure(AuthError.RateLimited)
        val vm = offered(fake)
        vm.openForgotPassword()
        vm.setForgotIdentifier("alice")
        vm.submitForgotPassword()
        val form = vm.forgotPassword.value!!
        assertFalse(form.sent)
        assertEquals("Too many requests. Try again in a few minutes.", form.error)
        vm.setForgotIdentifier("alice2")
        assertNull(vm.forgotPassword.value!!.error)
    }

    @Test
    fun aNetworkFailureSaysSo() = runBlocking {
        val fake = FakeAuthService()
        fake.stubbedResetRequest = Result.failure(AuthError.Unexpected("Couldn't reach the server."))
        val vm = offered(fake)
        vm.openForgotPassword()
        vm.setForgotIdentifier("alice")
        vm.submitForgotPassword()
        assertEquals("Couldn't reach the server.", vm.forgotPassword.value!!.error)
    }

    @Test
    fun copy() {
        assertEquals(
            "If that account has an email address, we've sent a link to reset its password. It works once, for 30 minutes.",
            SignInViewModel.FORGOT_SENT,
        )
    }
}
