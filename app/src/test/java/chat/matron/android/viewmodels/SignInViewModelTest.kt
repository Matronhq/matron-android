package chat.matron.android.viewmodels

import chat.matron.android.auth.AuthError
import chat.matron.android.auth.FakeAuthService
import chat.matron.android.auth.ServerCapabilities
import chat.matron.android.models.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `SignInViewModelTests`.
class SignInViewModelTest {

    private fun session() = UserSession(
        userID = "@a:s", deviceID = "D", homeserverURL = "https://s", accessToken = "t",
    )

    @Test
    fun submit_setsBusyAndCallsLogin_onSuccess() = runBlocking {
        val fake = FakeAuthService()
        val session = session()
        fake.stubbedProbe = Result.success(ServerCapabilities(supportsPasswordLogin = true, supportsSSO = false))
        fake.stubbedLogin = Result.success(session)
        val vm = SignInViewModel(fake, "Matron Tests")
        vm.serverURL = "https://matrix.example.com"
        vm.username = "alice"
        vm.password = "hunter2"

        vm.submit()

        assertEquals(SignInViewModel.State.SignedIn(session), vm.state.value)
        assertEquals(listOf(session), fake.persistedSessions)
    }

    @Test
    fun submit_showsError_onInvalidCredentials() = runBlocking {
        val fake = FakeAuthService()
        fake.stubbedProbe = Result.success(ServerCapabilities(supportsPasswordLogin = true, supportsSSO = false))
        fake.stubbedLogin = Result.failure(AuthError.InvalidCredentials)
        val vm = SignInViewModel(fake, "Matron Tests")
        vm.serverURL = "https://matrix.example.com"
        vm.username = "alice"
        vm.password = "wrong"

        vm.submit()

        val state = vm.state.value
        assertTrue("expected Error, got $state", state is SignInViewModel.State.Error)
        val message = (state as SignInViewModel.State.Error).message.lowercase()
        assertTrue(message.contains("credentials") || message.contains("invalid"))
    }

    @Test
    fun submit_isNoOp_whenInputsEmpty() = runBlocking {
        val fake = FakeAuthService()
        val vm = SignInViewModel(fake, "Matron Tests")
        vm.submit()
        assertEquals(SignInViewModel.State.Idle, vm.state.value)
    }

    // App Review rejected iOS 1.0 (350) because the Server field shipped
    // pre-filled with a dead host: the reviewer saw a filled field, typed
    // only the demo credentials, and got "Couldn't reach that server."
    // The field must start empty so the user has to supply their server.
    @Test
    fun serverURL_defaultsToEmpty() {
        val vm = SignInViewModel(FakeAuthService(), "Matron Tests")
        assertEquals("", vm.serverURL)
    }
}
