package chat.matron.android.viewmodels

import chat.matron.android.journal.AccountEmail
import chat.matron.android.journal.AccountEmailProviding
import chat.matron.android.journal.JournalApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/// Settings → Security's "Email address", as in Matron Web: the address a
/// password-reset link goes to, from `GET /account/email` when the screen
/// opens, and an Edit form (address and current password, Save, and Remove
/// when one is set). A journal predating account email 404s the GET and the
/// row stays hidden. The password lives only in [form] and is cleared once
/// the journal answers; [Form.toString] leaves it out.
class AccountEmailViewModel(private val api: AccountEmailProviding) {
    sealed interface State {
        data object Loading : State
        /// The journal has no `/account/email` route: the row is not offered.
        data object Unsupported : State
        data class Loaded(val account: AccountEmail) : State
        data class Failed(val message: String) : State
    }

    data class Form(
        val email: String = "",
        val password: String = "",
        val isSaving: Boolean = false,
        val error: String? = null,
    ) {
        val canSave: Boolean get() = !isSaving && email.trim().isNotEmpty() && password.isNotEmpty()
        val canRemove: Boolean get() = !isSaving && password.isNotEmpty()

        override fun toString(): String = "Form(email=$email, isSaving=$isSaving, error=$error)"
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _form = MutableStateFlow<Form?>(null)
    /// The open Edit form, or null.
    val form: StateFlow<Form?> = _form.asStateFlow()

    suspend fun load() {
        try {
            _state.value = State.Loaded(api.accountEmail())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: JournalApiError.NotFound) {
            _state.value = State.Unsupported
        } catch (error: Throwable) {
            if (_state.value !is State.Loaded) {
                _state.value = State.Failed("Couldn't load your email address: ${error.message ?: error}")
            }
        }
    }

    /// "Edit" — starts from the address on file.
    fun edit() {
        val loaded = _state.value as? State.Loaded ?: return
        _form.value = Form(email = loaded.account.email ?: "")
    }

    fun cancel() {
        _form.value = null
    }

    fun setEmail(value: String) = _form.update { it?.copy(email = value, error = null) }
    fun setPassword(value: String) = _form.update { it?.copy(password = value, error = null) }

    /// "Save".
    suspend fun save() {
        val form = _form.value ?: return
        if (!form.canSave) return
        send(form, form.email.trim())
    }

    /// "Remove" — only while an address is on file.
    suspend fun remove() {
        val form = _form.value ?: return
        val loaded = _state.value as? State.Loaded ?: return
        if (!form.canRemove || loaded.account.email == null) return
        send(form, null)
    }

    private suspend fun send(form: Form, email: String?) {
        _form.value = form.copy(isSaving = true, error = null)
        try {
            val stored = api.setAccountEmail(form.password, email)
            _state.update { s ->
                if (s is State.Loaded) s.copy(account = s.account.copy(email = stored)) else s
            }
            _form.value = null
        } catch (cancel: CancellationException) {
            _form.update { it?.copy(password = "", isSaving = false) }
            throw cancel
        } catch (error: Throwable) {
            _form.update { it?.copy(password = "", isSaving = false, error = errorText(error)) }
        }
    }

    companion object {
        const val TITLE = "Email address"
        const val NOT_SET = "Not set"
        const val EDIT = "Edit"
        const val FIELD = "Email address"
        const val PASSWORD = "Current password"
        const val SAVE = "Save"
        const val REMOVE = "Remove"
        const val CANCEL = "Cancel"

        /// The line under the row.
        fun helpText(account: AccountEmail): String =
            if (account.resetByEmail) {
                "Used to reset your password if you forget it."
            } else {
                "Used to reset your password if you forget it. This journal can't send email yet."
            }

        fun errorText(error: Throwable): String = when (error) {
            JournalApiError.BadEmail -> "Enter a valid email address."
            JournalApiError.EmailTaken -> "Another account already uses that address."
            JournalApiError.BadPassword -> ChangePasswordViewModel.WRONG_PASSWORD
            is JournalApiError.LockedOut -> ChangePasswordViewModel.lockedOutText(error.retryAfterSeconds)
            else -> "Couldn't save your email address: ${error.message ?: error}"
        }
    }
}
