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
    suspend fun renameDevice(id: Long, name: String): DeviceDTO
    suspend fun pairPreview(code: String): PairPreview
    suspend fun pairApprove(code: String, agentName: String)

    /// Approves with a roster tag character so the box is born with the
    /// right letter (apple #158). Default delegates to the tagless form so
    /// fakes that don't care keep compiling.
    suspend fun pairApprove(code: String, agentName: String, tagChar: String?) = pairApprove(code, agentName)

    /// Sets or clears (null) a device's journal-held tag character.
    suspend fun setDeviceTag(id: Long, tagChar: String?) {}
}

/// Production adapter over [JournalApi] (which already exposes these calls).
class JournalDevicesService(private val api: JournalApi) : DevicesProviding {
    override suspend fun devices(): List<DeviceDTO> = api.devices()
    override suspend fun revokeDevice(id: Long) = api.revokeDevice(id)
    override suspend fun renameDevice(id: Long, name: String): DeviceDTO = api.renameDevice(id, name)
    override suspend fun pairPreview(code: String): PairPreview = api.pairPreview(code)
    override suspend fun pairApprove(code: String, agentName: String) = api.pairApprove(code, agentName)
    override suspend fun pairApprove(code: String, agentName: String, tagChar: String?) =
        api.pairApprove(code, agentName, tagChar)
    override suspend fun setDeviceTag(id: Long, tagChar: String?) = api.setDeviceTag(id, tagChar)
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

    /// Renames [device]. The rename echo's name (already server-sanitised —
    /// control characters flattened) is applied to the local roster first, so
    /// a follow-up refresh that fails to load can't leave the old name on
    /// screen (mirrors [revoke]'s remove-locally-then-refetch discipline);
    /// the re-fetch then supplies the full fresh row.
    suspend fun rename(device: DeviceDTO, to: String) {
        val trimmed = to.trim()
        val problem = validate(trimmed)
        if (problem != null) {
            _errorMessage.value = problem
            return
        }
        try {
            val renamed = api.renameDevice(device.id, trimmed)
            _devices.value = _devices.value.map {
                if (it.id == device.id) it.copy(name = renamed.name) else it
            }
            _errorMessage.value = null
            refresh()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _errorMessage.value = "Couldn't rename ${device.name} — ${describe(error)}"
        }
    }

    /// Sets (or clears, on a blank draft) [device]'s journal-held tag
    /// character (apple #158). The sieved value is applied locally first so
    /// a failed re-fetch can't leave the old letter on screen, then the
    /// roster is re-fetched: the server keeps only the first grapheme and is
    /// the authority on what it stored.
    suspend fun setTag(device: DeviceDTO, draft: String) {
        val tag = tagCharFromDraft(draft)
        try {
            api.setDeviceTag(device.id, tag)
            _devices.value = _devices.value.map { if (it.id == device.id) it.copy(tagChar = tag) else it }
            _errorMessage.value = null
            refresh()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _errorMessage.value = "Couldn't set the tag for ${device.name} — ${describe(error)}"
        }
    }

    /// Warns (never blocks) when [draft] would give [device] the same tag
    /// character another agent box already has — compared case-insensitively
    /// on the sieved value, so `q` and `Q` count as a clash.
    fun duplicateTagWarning(device: DeviceDTO, draft: String): String? {
        val tag = tagCharFromDraft(draft) ?: return null
        val clash = _devices.value.firstOrNull {
            it.id != device.id && it.kind == "agent" && it.tagChar?.equals(tag, ignoreCase = true) == true
        } ?: return null
        return "${clash.name} already uses “$tag”"
    }

    companion object {
        /// Longest grapheme cluster (in code points) accepted as a tag: a
        /// flag or ZWJ-emoji is a handful, anything longer is not a character.
        const val TAG_MAX_SCALARS = 16

        /// The tag a draft field maps to: trimmed, first grapheme cluster
        /// only (so a surrogate-pair emoji survives whole), rejected when
        /// that cluster is over [TAG_MAX_SCALARS] code points or made only of
        /// format/control/space characters (invisible on screen). Null means
        /// "automatic" — the same sieve the server applies, mirrored so the
        /// field can refuse before a round-trip (apple #158).
        fun tagCharFromDraft(draft: String): String? {
            val trimmed = draft.trim()
            if (trimmed.isEmpty()) return null
            val boundary = java.text.BreakIterator.getCharacterInstance()
            boundary.setText(trimmed)
            val end = boundary.next()
            val cluster = if (end > 0) trimmed.substring(0, end) else return null
            val points = cluster.codePoints().toArray()
            if (points.size > TAG_MAX_SCALARS) return null
            val visible = points.any { cp ->
                when (Character.getType(cp)) {
                    Character.FORMAT.toInt(), Character.CONTROL.toInt(), Character.SPACE_SEPARATOR.toInt(),
                    Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> false
                    else -> true
                }
            }
            return if (visible) cluster else null
        }

        /// Server-side cap on a device name, mirrored here so the field can
        /// refuse before a round-trip.
        const val NAME_CAP = 40

        /// Name rules, mirrored from the server: non-empty after trimming, at
        /// most [NAME_CAP] characters. Returns null when acceptable, else the
        /// reason to show.
        fun validate(name: String): String? {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return "Give the device a name."
            if (trimmed.length > NAME_CAP) return "Names are at most $NAME_CAP characters."
            return null
        }

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
/// extension (which also carries an SF Symbol name per device kind — the
/// Android UI has no equivalent, so that helper wasn't ported).
val DeviceDTO.isClient: Boolean get() = kind == "client"

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
