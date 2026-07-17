package chat.matron.android.viewmodels

import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.PairPreview
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// The devices/pairing slice of the journal API, extracted so view models test
/// against a fake. Ported from matron-apple's `DevicesProviding`.
interface DevicesProviding {
    suspend fun devices(): List<DeviceDTO>
    suspend fun revokeDevice(id: Long)
    suspend fun pairPreview(code: String): PairPreview
    suspend fun pairApprove(code: String, agentName: String)
}

/// Production adapter over [JournalApi] (which already exposes these calls).
class JournalDevicesService(private val api: JournalApi) : DevicesProviding {
    override suspend fun devices(): List<DeviceDTO> = api.devices()
    override suspend fun revokeDevice(id: Long) = api.revokeDevice(id)
    override suspend fun pairPreview(code: String): PairPreview = api.pairPreview(code)
    override suspend fun pairApprove(code: String, agentName: String) = api.pairApprove(code, agentName)
}

/// Devices-screen state: the signed-in user's device roster with per-device
/// revoke. Pull-based (callers [refresh] on screen enter and the model re-fetches
/// after every mutation). Ported from matron-apple's `DevicesViewModel`.
class DevicesViewModel(
    private val api: DevicesProviding,
    /// Fired after a successful self-revocation (the server treats it as a
    /// logout). The host drops local credentials and returns to sign-in.
    private val onSelfRevoked: () -> Unit,
) {
    private val _devices = MutableStateFlow<List<DeviceDTO>>(emptyList())
    val devices: StateFlow<List<DeviceDTO>> = _devices.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    suspend fun refresh() {
        _isLoading.value = true
        try {
            _devices.value = sorted(api.devices())
            _errorMessage.value = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _errorMessage.value = "Couldn't load devices — ${describe(error)}"
        } finally {
            _isLoading.value = false
        }
    }

    /// Revokes [device]. 404 means it was already revoked elsewhere — treated as
    /// success. Self-revocation fires [onSelfRevoked] instead of re-fetching (the
    /// roster call would just 401 on the dead token).
    suspend fun revoke(device: DeviceDTO) {
        try {
            try {
                api.revokeDevice(device.id)
            } catch (notFound: JournalApiError.NotFound) {
                // Already gone — fall through to the success path.
            }
            if (device.isSelf) {
                onSelfRevoked()
            } else {
                // Reflect the removal locally first — a failed refetch leaves
                // devices untouched and the dead row would linger.
                _devices.value = _devices.value.filterNot { it.id == device.id }
                refresh()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _errorMessage.value = "Couldn't revoke ${device.name} — ${describe(error)}"
        }
    }

    companion object {
        /// Clients first, then agents, each newest-first.
        fun sorted(devices: List<DeviceDTO>): List<DeviceDTO> =
            devices.sortedWith(
                compareByDescending<DeviceDTO> { it.kind == "client" }
                    .thenByDescending { it.createdAt },
            )

        fun describe(error: Throwable): String =
            if (error is JournalApiError.Transport) "check your connection and try again."
            else "the server said no ($error)."
    }
}

/// Display helpers shared by the device rows. Ported from the Swift `DeviceDTO`
/// extension; the SF Symbol names are kept verbatim (the UI stage maps them).
val DeviceDTO.isClient: Boolean get() = kind == "client"

val DeviceDTO.symbolName: String get() = if (isClient) "laptopcomputer" else "terminal"

/// `lag` is the user's head seq minus this device's cursor.
val DeviceDTO.lagText: String
    get() = if (lag <= 0) "Up to date" else "$lag event${if (lag == 1L) "" else "s"} behind"

/// Relative last-seen. `null` = never connected → "Never".
fun DeviceDTO.lastSeenText(now: Instant = Instant.now()): String {
    val lastSeen = lastSeenAt ?: return "Never"
    val seconds = java.time.Duration.between(Instant.ofEpochMilli(lastSeen), now).seconds
    return when {
        seconds < 60 -> "${seconds.coerceAtLeast(0)}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}
