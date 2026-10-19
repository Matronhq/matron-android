package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.TwoFactorProviding
import chat.matron.android.journal.TwoFactorStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Settings → Security: whether two-factor sign-in is on, from `GET /2fa`
/// when the screen opens. Read-only here — turning it on or off and making
/// new recovery codes happen in Matron Web. A journal predating two-factor
/// 404s the GET and the section stays hidden.
class TwoFactorSettingViewModel(private val api: TwoFactorProviding) {
    sealed interface State {
        data object Loading : State
        /// The journal has no `/2fa` route: the section is not offered.
        data object Unsupported : State
        data class Loaded(val status: TwoFactorStatus) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun load() {
        try {
            _state.value = State.Loaded(api.twoFactorStatus())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: JournalApiError.NotFound) {
            _state.value = State.Unsupported
        } catch (error: Throwable) {
            // Keep a status already showing: a flaky reload must not blank it.
            if (_state.value !is State.Loaded) {
                _state.value = State.Failed("Couldn't load two-factor status: ${error.message ?: error}")
            }
        }
    }

    companion object {
        const val TITLE = "Two-factor sign-in"
        const val MANAGE = "Manage two-factor in Matron Web"

        fun valueText(status: TwoFactorStatus): String = if (status.enabled) "On" else "Off"

        /// The line under the switch-like row.
        fun detailText(status: TwoFactorStatus): String {
            val lines = mutableListOf<String>()
            if (status.enabled) {
                status.recoveryCodesLeft?.let { left ->
                    lines += if (left == 1) "1 recovery code left." else "$left recovery codes left."
                }
            } else if (!status.available) {
                lines += "Not available on this server."
            } else {
                lines += "Sign-in asks only for your password."
            }
            if (status.required) lines += "Required for everyone on this server."
            return lines.joinToString(" ")
        }
    }
}
