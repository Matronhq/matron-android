package chat.matron.android.viewmodels

import chat.matron.android.journal.approvals.ApprovalKeyDTO
import chat.matron.android.journal.approvals.ApprovalKeyFailure
import chat.matron.android.journal.approvals.ApprovalKeyRequestDTO
import chat.matron.android.journal.approvals.ApprovalKeysState
import chat.matron.android.viewmodels.ApprovalCenter.Status
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Settings ▸ Approval keys: this device's key and fingerprint, the
/// account's keys, the requests waiting to be approved, and the forms that
/// add and remove keys. The words live here so they are tested. Ported from
/// matron-apple's `ApprovalKeysScreenModel`.
class ApprovalKeysScreenModel(
    val center: ApprovalCenter,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
) {
    /// One key of the account, as a row.
    data class KeyRow(
        val key: ApprovalKeyDTO,
        val isThisDevice: Boolean,
        /// "Added with password and code · 12 Nov 2026".
        val detail: String,
        /// "Not attached to a signed-in device", "Removed 13 Nov 2026".
        val state: String?,
    ) {
        val isLive: Boolean get() = key.isLive
    }

    /// The form in front of the screen.
    sealed interface Form {
        /// Password and code, to set this device's key up.
        data object SetUp : Form
        /// Password and code, to remove a key this device cannot sign for.
        data class Remove(val key: ApprovalKeyDTO) : Form
    }

    private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)

    private val _form = MutableStateFlow<Form?>(null)
    val form: StateFlow<Form?> = _form.asStateFlow()
    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()
    private val _code = MutableStateFlow("")
    val code: StateFlow<String> = _code.asStateFlow()
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()
    /// What went wrong, under the section it belongs to (or in the form).
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setPassword(value: String) { _password.value = value }
    fun setCode(value: String) { _code.value = value.filter { it.isDigit() }.take(6) }

    // MARK: This device

    private val status: Status get() = center.status.value
    private val method: String get() = center.method ?: "a fingerprint"

    /// The headline for this device's state.
    val statusTitle: String
        get() = when (status) {
            Status.Unknown -> if (center.loadError.value == null) "Checking…" else "Couldn't read the account's keys"
            Status.Disabled -> "Not available on this server"
            is Status.Unavailable -> "This device can't hold a key"
            Status.NoAccountKey -> "No approval key yet"
            Status.NoDeviceKey -> "This device has no key"
            Status.WaitingForApproval -> "Waiting for another device to approve"
            Status.NeedsAttach -> "Key needs re-attaching"
            Status.Ready -> "This device can approve"
            Status.Revoked -> "This device's key was removed"
            Status.Invalidated -> "This device's key no longer works"
        }

    val statusDetail: String
        get() = when (val s = status) {
            Status.Unknown -> center.loadError.value ?: ""
            Status.Disabled -> "The server needs a public address set before approval keys can be used."
            is Status.Unavailable -> s.advice
            Status.NoAccountKey ->
                "Dangerous actions are approved with a tap for now. Set up a key and they will need your $method " +
                    "on a device that holds one."
            Status.NoDeviceKey ->
                "The account has an approval key on another device. Until this device has one too, it can't approve " +
                    "step-up questions or account changes."
            Status.WaitingForApproval ->
                "On a device that has a key, open Settings ▸ Approval keys, check it shows the fingerprint below for " +
                    "this device, and approve. The request lasts ten minutes."
            Status.NeedsAttach ->
                "This device signed in again since its key was added. Re-attach the key with your $method before " +
                    "approving anything."
            Status.Ready -> "Dangerous actions ask for your $method and are signed by this device's key."
            Status.Revoked -> "A removed key can't be added back. Set up a new one."
            Status.Invalidated ->
                "This device's fingerprints or face unlock changed since the key was made, which destroys the key. " +
                    "Set up a new one."
        }

    /// The fingerprint to show for this device: its key's, once it has one
    /// that is enrolled or waiting.
    val fingerprint: String?
        get() = when (status) {
            Status.Ready, Status.NeedsAttach, Status.WaitingForApproval -> center.deviceFingerprint
            else -> null
        }

    val canSetUp: Boolean
        get() = when (status) {
            Status.NoAccountKey, Status.NoDeviceKey, Status.Revoked, Status.Invalidated, Status.WaitingForApproval -> true
            else -> false
        }

    /// "Ask another device" is offered when the account already has a key
    /// some device could approve with.
    val canAskAnotherDevice: Boolean
        get() = when (status) {
            Status.NoDeviceKey, Status.Revoked, Status.Invalidated -> center.accountHasKey
            else -> false
        }

    val canAttach: Boolean get() = status == Status.NeedsAttach

    /// The one-line summary under the Settings row.
    val summary: String
        get() = when (status) {
            Status.Ready -> "This device can approve"
            Status.NeedsAttach -> "Needs re-attaching"
            Status.WaitingForApproval -> "Waiting for approval"
            Status.NoAccountKey -> "Not set up"
            Status.NoDeviceKey, is Status.Unavailable -> "Not on this device"
            Status.Revoked, Status.Invalidated -> "Needs a new key"
            Status.Disabled -> "Not available"
            Status.Unknown -> ""
        }

    /// The Settings row is marked when the user should act on it.
    val needsAttention: Boolean
        get() = when (status) {
            Status.NeedsAttach, Status.Revoked, Status.Invalidated, Status.NoDeviceKey -> true
            else -> requests.isNotEmpty() && center.canSign
        }

    // MARK: Lists

    val requests: List<ApprovalKeyRequestDTO>
        // This device's own request is shown as its status, not as something
        // to approve.
        get() = (center.state.value?.requests ?: emptyList()).filter { it.keyID != center.deviceKeyID.value }

    val rows: List<KeyRow> get() = rows(center.state.value)

    fun rows(state: ApprovalKeysState?): List<KeyRow> = (state?.keys ?: emptyList()).map { key ->
        val how = when (key.enrolledVia) {
            ApprovalKeyDTO.EnrolledVia.PASSWORD -> "Added with password and code"
            ApprovalKeyDTO.EnrolledVia.DEVICE -> "Approved from another device"
            null -> "Added"
        }
        val state = when {
            key.revokedAt != null -> "Removed ${dateFormatter.format(key.revokedAt)}"
            key.deviceID == null -> "Not attached to a signed-in device"
            else -> null
        }
        KeyRow(key, key.keyID == center.deviceKeyID.value, "$how · ${dateFormatter.format(key.createdAt)}", state)
    }

    // MARK: Actions

    suspend fun load() = center.refresh()

    /// Opens the password-and-code form to set this device's key up.
    fun beginSetUp() {
        clearSecrets()
        _error.value = null
        _form.value = Form.SetUp
    }

    /// Removes a key: signed by this device when it can, else by password and
    /// code (its form opens).
    suspend fun remove(key: ApprovalKeyDTO) {
        if (!center.canSign) {
            clearSecrets()
            _error.value = null
            _form.value = Form.Remove(key)
            return
        }
        perform { center.revoke(key) }
    }

    val canSubmitForm: Boolean get() = !_isBusy.value && _password.value.isNotEmpty() && _code.value.length >= 6

    /// The form's button. True when it is done and the form should close.
    suspend fun submitForm(): Boolean {
        val form = _form.value ?: return false
        if (!canSubmitForm) return false
        val password = _password.value
        val code = _code.value.trim()
        val done = perform {
            when (form) {
                Form.SetUp -> {
                    if (status == Status.Revoked || status == Status.Invalidated) center.discardDeviceKey()
                    center.enrol(password, code)
                }
                is Form.Remove -> center.revoke(form.key, password, code)
            }
        }
        // Whatever happened, the password is typed again.
        clearSecrets()
        if (done) _form.value = null
        return done
    }

    fun closeForm() {
        _form.value = null
        clearSecrets()
        _error.value = null
    }

    suspend fun askAnotherDevice() {
        perform {
            if (status == Status.Revoked || status == Status.Invalidated) center.discardDeviceKey()
            center.requestFromAnotherDevice()
        }
    }

    suspend fun approve(request: ApprovalKeyRequestDTO) {
        perform { center.approve(request) }
    }

    suspend fun attach() {
        perform { center.attach() }
    }

    private suspend fun perform(work: suspend () -> Unit): Boolean {
        if (_isBusy.value) return false
        _isBusy.value = true
        _error.value = null
        try {
            work()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApprovalKeyFailure.Cancelled) {
            return false
        } catch (e: Throwable) {
            _error.value = e.message ?: e.toString()
            return false
        } finally {
            _isBusy.value = false
        }
    }

    private fun clearSecrets() {
        _password.value = ""
        _code.value = ""
    }

    companion object {
        const val TITLE = "Approval keys"
        const val INTRO = "An approval key lives in this device's secure hardware and signs dangerous actions after a " +
            "fingerprint or face check. A stolen password or session can't approve them."
        const val PINNING_NOTE = "A protected box trusts only the keys pinned on it. Pin a key from the fingerprint its own " +
            "device shows here, never from this list alone."

        /// "7734f909" → "7734 f909", so two devices' are easy to compare by eye.
        fun grouped(fingerprint: String): String =
            if (fingerprint.length == 8) fingerprint.take(4) + " " + fingerprint.takeLast(4) else fingerprint
    }
}
