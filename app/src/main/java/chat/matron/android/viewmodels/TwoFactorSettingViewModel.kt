package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.TwoFactorProviding
import chat.matron.android.journal.TwoFactorSetup
import chat.matron.android.journal.TwoFactorStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date

/// Settings → Security, as in Matron Web: whether two-factor sign-in is on
/// (`GET /2fa` when the screen opens), and the sheets that change it — Set
/// Up…, Change App…, New Recovery Codes… and Turn Off…. A journal predating
/// two-factor 404s the GET and the section stays hidden.
///
/// The password and codes typed into a sheet are sent once and not kept;
/// the setup key and recovery codes live only in the open sheet.
class TwoFactorSettingViewModel(private val api: TwoFactorProviding) {
    sealed interface State {
        data object Loading : State
        /// The journal has no `/2fa` route: the section is not offered.
        data object Unsupported : State
        data class Loaded(val status: TwoFactorStatus) : State
        data class Failed(val message: String) : State
    }

    /// The open sheet, if any.
    sealed interface Sheet {
        val busy: Boolean
        val error: String?

        /// Set Up… ([moving] false) and Change App… ([moving] true): the
        /// password (moving: and a code from the current app), then the QR
        /// code and the new app's first code, then the recovery codes.
        data class Setup(
            val moving: Boolean,
            val phase: SetupPhase = SetupPhase.Password,
            val setup: TwoFactorSetup? = null,
            val recoveryCodes: List<String> = emptyList(),
            val signedOut: Int = 0,
            override val busy: Boolean = false,
            override val error: String? = null,
        ) : Sheet

        /// New Recovery Codes… and Turn Off…: the password and a current
        /// code. [recoveryCodes] is the new set once made.
        data class Confirm(
            val action: ConfirmAction,
            val recoveryCodes: List<String>? = null,
            override val busy: Boolean = false,
            override val error: String? = null,
        ) : Sheet
    }

    enum class SetupPhase { Password, Scan, Codes }
    enum class ConfirmAction { RecoveryCodes, TurnOff }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _sheet = MutableStateFlow<Sheet?>(null)
    val sheet: StateFlow<Sheet?> = _sheet.asStateFlow()

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
                _state.value = State.Failed("Couldn't load two-factor settings: ${error.message ?: error}")
            }
        }
    }

    fun openSetUp() { _sheet.value = Sheet.Setup(moving = false) }
    fun openChangeApp() { _sheet.value = Sheet.Setup(moving = true) }
    fun openNewRecoveryCodes() { _sheet.value = Sheet.Confirm(ConfirmAction.RecoveryCodes) }
    fun openTurnOff() { _sheet.value = Sheet.Confirm(ConfirmAction.TurnOff) }

    /// Cancel, Done, or system back: closes the sheet and reloads the status.
    suspend fun closeSheet() {
        _sheet.value = null
        load()
    }

    /// Set Up… / Change App…, step one: the password (and, moving, a code
    /// from the current app or a recovery code) for a new setup key.
    suspend fun submitPassword(password: String, currentCode: String = "") {
        val sheet = _sheet.value as? Sheet.Setup ?: return
        if (sheet.busy || sheet.phase != SetupPhase.Password || password.isEmpty()) return
        if (sheet.moving && currentCode.isBlank()) return
        val sent = sheet.copy(busy = true, error = null)
        _sheet.value = sent
        try {
            val setup = api.twoFactorSetup(password, if (sheet.moving) currentCode.trim() else null)
            update(sent) { it.copy(phase = SetupPhase.Scan, setup = setup, busy = false) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            update(sent) { it.copy(busy = false, error = messageOf(error)) }
        }
    }

    /// Step two: the new app's first code turns it on. [signOutOthers] signs
    /// out the user's other browsers and apps (never agent boxes).
    suspend fun confirmSetup(code: String, signOutOthers: Boolean) {
        val sheet = _sheet.value as? Sheet.Setup ?: return
        if (sheet.busy || sheet.phase != SetupPhase.Scan || code.isBlank()) return
        val sent = sheet.copy(busy = true, error = null)
        _sheet.value = sent
        try {
            val enabled = api.twoFactorEnable(code.trim(), signOutOthers && !sheet.moving)
            update(sent) {
                it.copy(
                    phase = SetupPhase.Codes,
                    setup = null,
                    recoveryCodes = enabled.recoveryCodes,
                    signedOut = enabled.signedOut,
                    busy = false,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            // The pending setup expired: back to the password for a new key.
            val expired = error is JournalApiError.TwoFactorConflict && error.code == "no_pending_setup"
            update(sent) {
                if (expired) {
                    it.copy(phase = SetupPhase.Password, setup = null, busy = false, error = messageOf(error))
                } else {
                    it.copy(busy = false, error = messageOf(error))
                }
            }
        }
    }

    /// New Recovery Codes… / Turn Off…: the password and a current code.
    /// Turning off closes the sheet; new codes are shown in it.
    suspend fun confirmAction(password: String, code: String) {
        val sheet = _sheet.value as? Sheet.Confirm ?: return
        if (sheet.busy || sheet.recoveryCodes != null || password.isEmpty() || code.isBlank()) return
        val sent = sheet.copy(busy = true, error = null)
        _sheet.value = sent
        try {
            when (sheet.action) {
                ConfirmAction.RecoveryCodes -> {
                    val codes = api.twoFactorRecoveryCodes(password, code.trim())
                    update(sent) { it.copy(recoveryCodes = codes, busy = false) }
                }
                ConfirmAction.TurnOff -> {
                    api.twoFactorDisable(password, code.trim())
                    closeSheet()
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            update(sent) { it.copy(busy = false, error = messageOf(error)) }
        }
    }

    /// Applies [change] only if the open sheet is still the very one that
    /// sent the request: the sheet may have been closed, or closed and opened
    /// again, while it was in flight, and a late answer must not land on a
    /// new attempt.
    private inline fun <T : Sheet> update(sent: T, change: (T) -> Sheet) {
        if (_sheet.value !== sent) return
        _sheet.value = change(sent)
    }

    companion object {
        const val TITLE = "Two-factor sign-in"
        const val SET_UP = "Set Up…"
        const val NEW_RECOVERY_CODES = "New Recovery Codes…"
        const val CHANGE_APP = "Change App…"
        const val TURN_OFF = "Turn Off…"
        const val SIGN_OUT_OTHERS = "Also sign out my other browsers and apps (agent boxes stay connected)"
        const val RUNNING_LOW = "You're running out of recovery codes. Make a new set."
        const val REQUIRED = "This server requires two-factor sign-in for everyone."

        /// Fewer recovery codes than this left, and Settings suggests a new set.
        const val RECOVERY_CODES_LOW = 3

        /// The line under "Two-factor sign-in".
        fun detailText(status: TwoFactorStatus, formatDate: (Long) -> String = ::mediumDate): String = when {
            status.enabled -> buildList {
                status.enabledAtMillis?.let { add("On since ${formatDate(it)}") } ?: add("On")
                status.recoveryCodesLeft?.let { left ->
                    add(if (left == 1) "1 recovery code left" else "$left recovery codes left")
                }
            }.joinToString(" · ")
            status.available -> "Off. Signing in takes only your password."
            else -> "This server isn't set up for two-factor sign-in."
        }

        fun runningLow(status: TwoFactorStatus): Boolean =
            status.enabled && (status.recoveryCodesLeft ?: RECOVERY_CODES_LOW) < RECOVERY_CODES_LOW

        fun setupTitle(moving: Boolean) = if (moving) "Change Authenticator App" else "Set Up Two-Factor"

        fun setupIntro(moving: Boolean) = if (moving) {
            "Enter your password and a code from your current app (or a recovery code). Your current app keeps " +
                "working until the new one is confirmed."
        } else {
            "You'll need an authenticator app such as Google Authenticator or 1Password. Enter your password to start."
        }

        fun setupDone(moving: Boolean, signedOut: Int): String {
            val head = if (moving) "Your new app is set up." else "Two-factor sign-in is on."
            return when (signedOut) {
                0 -> head
                1 -> "$head Signed out 1 other device."
                else -> "$head Signed out $signedOut other devices."
            }
        }

        fun confirmTitle(action: ConfirmAction) = when (action) {
            ConfirmAction.RecoveryCodes -> "New Recovery Codes"
            ConfirmAction.TurnOff -> "Turn Off Two-Factor"
        }

        fun confirmIntro(action: ConfirmAction) = when (action) {
            ConfirmAction.RecoveryCodes -> "Your old recovery codes stop working as soon as the new set is made."
            ConfirmAction.TurnOff -> "Signing in will take only your password again. Your recovery codes stop working."
        }

        fun confirmButton(action: ConfirmAction) = when (action) {
            ConfirmAction.RecoveryCodes -> "Make New Codes"
            ConfirmAction.TurnOff -> "Turn Off"
        }

        /// Matron Web's sentences for the journal's two-factor refusals.
        fun messageOf(error: Throwable): String = when (error) {
            is JournalApiError.BadPassword -> "That password is incorrect."
            is JournalApiError.BadCode -> "That code didn't work. Check your authenticator app and try again."
            is JournalApiError.TwoFactorConflict -> error.message ?: "Something went wrong."
            is JournalApiError.LockedOut -> "Too many failed attempts. Try again later."
            is JournalApiError.RateLimited -> "Too many attempts. Try again in a minute."
            is JournalApiError.Http ->
                if (error.code == "two_factor_unavailable") {
                    "This server isn't set up for two-factor sign-in."
                } else {
                    error.message?.takeIf { it.isNotEmpty() } ?: "Something went wrong."
                }
            else -> error.message ?: "Something went wrong."
        }

        private fun mediumDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
    }
}
