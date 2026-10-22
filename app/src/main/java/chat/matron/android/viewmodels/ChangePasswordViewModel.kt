package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.PasswordChanging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/// Settings → Security's "Change password", as in Matron Web: a row that
/// opens a small form (current, new, confirm, and "Sign out of my other
/// devices", on by default), `POST /password` on submit. The passwords live
/// only in [state] while the form is open and are never logged
/// ([State.toString] leaves them out). A success, Cancel or leaving the screen
/// clears them all; a refusal clears only the current password, so the new
/// one need not be typed twice again.
class ChangePasswordViewModel(private val api: PasswordChanging) {
    data class State(
        val isOpen: Boolean = false,
        val currentPassword: String = "",
        val newPassword: String = "",
        val confirmPassword: String = "",
        val signOutOthers: Boolean = true,
        val isSubmitting: Boolean = false,
        val error: String? = null,
        /// "Password changed." after a success, shown under the row once the
        /// form has closed.
        val confirmation: String? = null,
    ) {
        /// The button: off while sending and while any field is empty.
        val canSubmit: Boolean
            get() = !isSubmitting && currentPassword.isNotEmpty() && newPassword.isNotEmpty() &&
                confirmPassword.isNotEmpty()

        override fun toString(): String =
            "State(isOpen=$isOpen, signOutOthers=$signOutOthers, isSubmitting=$isSubmitting, " +
                "error=$error, confirmation=$confirmation)"
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun open() {
        _state.value = State(isOpen = true)
    }

    fun cancel() {
        _state.value = State()
    }

    fun setCurrentPassword(value: String) = _state.update { it.copy(currentPassword = value, error = null) }
    fun setNewPassword(value: String) = _state.update { it.copy(newPassword = value, error = null) }
    fun setConfirmPassword(value: String) = _state.update { it.copy(confirmPassword = value, error = null) }
    fun setSignOutOthers(value: Boolean) = _state.update { it.copy(signOutOthers = value) }

    suspend fun submit() {
        val form = _state.value
        if (!form.isOpen || !form.canSubmit) return
        validate(form.currentPassword, form.newPassword, form.confirmPassword)?.let { problem ->
            _state.update { it.copy(error = problem) }
            return
        }
        _state.update { it.copy(isSubmitting = true, error = null) }
        try {
            val answer = api.changePassword(form.currentPassword, form.newPassword, form.signOutOthers)
            _state.value = State(confirmation = confirmationText(answer.signedOut))
        } catch (cancel: CancellationException) {
            _state.update { it.copy(currentPassword = "", isSubmitting = false) }
            throw cancel
        } catch (error: Throwable) {
            _state.update { it.copy(currentPassword = "", isSubmitting = false, error = errorText(error)) }
        }
    }

    companion object {
        const val TITLE = "Change password"
        const val CURRENT = "Current password"
        const val NEW = "New password"
        const val CONFIRM = "Confirm new password"
        const val SIGN_OUT_OTHERS = "Sign out of my other devices"
        const val SIGN_OUT_OTHERS_HELP = "Your agent boxes stay connected."
        const val SUBMIT = "Change password"
        const val CANCEL = "Cancel"

        const val TOO_SHORT = "Use at least 8 characters."
        const val MISMATCH = "The new passwords don't match."
        const val SAME_AS_CURRENT = "Choose a password different from your current one."
        const val WRONG_PASSWORD = "That password is incorrect."
        const val MIN_LENGTH = 8

        /// The checks made before sending, in Matron Web's order; `null` when
        /// the form may go.
        fun validate(current: String, new: String, confirm: String): String? = when {
            new.length < MIN_LENGTH -> TOO_SHORT
            new != confirm -> MISMATCH
            new == current -> SAME_AS_CURRENT
            else -> null
        }

        fun errorText(error: Throwable): String = when (error) {
            JournalApiError.BadPassword -> WRONG_PASSWORD
            JournalApiError.WeakPassword -> TOO_SHORT
            is JournalApiError.LockedOut -> lockedOutText(error.retryAfterSeconds)
            else -> "Couldn't change your password: ${error.message ?: error}"
        }

        /// Whole minutes, rounded up, at least one.
        fun lockedOutText(retryAfterSeconds: Int): String {
            val minutes = maxOf(1, (retryAfterSeconds + 59) / 60)
            return "Too many attempts. Try again in $minutes ${if (minutes == 1) "minute" else "minutes"}."
        }

        fun confirmationText(signedOut: Int): String = when {
            signedOut <= 0 -> "Password changed."
            signedOut == 1 -> "Password changed. Signed out of 1 other device."
            else -> "Password changed. Signed out of $signedOut other devices."
        }
    }
}
