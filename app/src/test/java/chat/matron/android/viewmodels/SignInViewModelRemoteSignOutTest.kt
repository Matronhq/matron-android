package chat.matron.android.viewmodels

import chat.matron.android.auth.AuthService
import chat.matron.android.models.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

class SignInViewModelRemoteSignOutTest {
    /** Never called: these tests only read the form's starting state. */
    private val auth = Proxy.newProxyInstance(
        AuthService::class.java.classLoader, arrayOf(AuthService::class.java),
    ) { _, method, _ -> error("unexpected call: ${method.name}") } as AuthService

    @Test
    fun anOrdinarySignIn_hasNoNotice() {
        val vm = SignInViewModel(auth, "Matron Android")
        assertNull(vm.notice)
        assertEquals("", vm.serverURL)
        assertEquals("", vm.username)
    }

    @Test
    fun afterARemoteSignOut_saysWhy_andFillsInTheAccount() {
        val revoked = UserSession(
            userID = "alice", deviceID = "3", homeserverURL = "https://journal.example.com/", accessToken = "t",
        )
        val vm = SignInViewModel(auth, "Matron Android", signedOutRemotely = revoked)
        assertEquals(SignInViewModel.SIGNED_OUT_REMOTELY_NOTICE, vm.notice)
        assertEquals("https://journal.example.com/", vm.serverURL)
        assertEquals("alice", vm.username)
        assertEquals("", vm.password)
        assertFalse(vm.forgotPasswordAvailable.value)
    }
}
