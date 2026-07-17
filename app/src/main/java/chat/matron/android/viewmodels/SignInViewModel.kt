package chat.matron.android.viewmodels

import chat.matron.android.auth.AuthError
import chat.matron.android.auth.AuthService
import chat.matron.android.auth.ServerURLValidator
import chat.matron.android.models.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl

/// Drives the sign-in form. Ported from matron-apple's `SignInViewModel`.
/// `deviceDisplayName` is platform-specific ("Matron Android" from the app), so
/// the view model itself stays target-agnostic.
class SignInViewModel(
    private val auth: AuthService,
    private val deviceDisplayName: String,
) {
    sealed interface State {
        data object Idle : State
        data object Busy : State
        data class Error(val message: String) : State
        data class SignedIn(val session: UserSession) : State
    }

    /// User-editable inputs (Swift `public var`, @Observable-tracked).
    var serverURL: String = "https://chat.example.com"
    var username: String = ""
    var password: String = ""

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun submit() {
        if (serverURL.trim().isEmpty() || username.isEmpty() || password.isEmpty()) return
        _state.value = State.Busy
        try {
            auth.probe(serverURL)
            val url = ServerURLValidator.normalize(serverURL).toHttpUrl()
            val session = auth.loginPassword(
                homeserverURL = url,
                username = username,
                password = password,
                initialDeviceDisplayName = deviceDisplayName,
            )
            auth.persist(session)
            _state.value = State.SignedIn(session)
        } catch (error: AuthError) {
            _state.value = State.Error(message(error))
        } catch (error: Throwable) {
            _state.value = State.Error("Unexpected error: ${error.message ?: error}")
        }
    }

    private fun message(error: AuthError): String = when (error) {
        is AuthError.InvalidServerURL -> "That doesn't look like a valid server URL."
        AuthError.ServerUnreachable -> "Couldn't reach that server."
        AuthError.SsoNotSupported -> "SSO is not supported by this server."
        AuthError.InvalidCredentials -> "Invalid credentials."
        is AuthError.Unexpected -> error.detail
    }
}
