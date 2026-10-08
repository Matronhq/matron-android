package chat.matron.android.viewmodels

import chat.matron.android.journal.BoxDefaults
import chat.matron.android.journal.BoxDefaultsUpdate
import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.JournalSyncEngine
import chat.matron.android.journal.PairPreview
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
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

    /// `PUT /devices/:id/defaults` for any subset of an agent box's defaults
    /// for new sessions; see [JournalApi.putDeviceDefaults]. The default is
    /// what a journal predating box defaults answers, so fakes that never
    /// edit them keep compiling.
    suspend fun putDeviceDefaults(id: Long, changes: Map<BoxDefaults.Key, String?>): BoxDefaults =
        throw JournalApiError.NotFound

    /// Live `box_defaults` frames. Empty where there is no sync engine.
    fun boxDefaultsUpdates(): Flow<BoxDefaultsUpdate> = emptyFlow()
}

/// Production adapter over [JournalApi] (which already exposes these calls),
/// plus the session's [JournalSyncEngine] for the live `box_defaults` feed.
class JournalDevicesService(
    private val api: JournalApi,
    private val engine: JournalSyncEngine? = null,
) : DevicesProviding {
    override suspend fun devices(): List<DeviceDTO> = api.devices()
    override suspend fun revokeDevice(id: Long) = api.revokeDevice(id)
    override suspend fun renameDevice(id: Long, name: String): DeviceDTO = api.renameDevice(id, name)
    override suspend fun pairPreview(code: String): PairPreview = api.pairPreview(code)
    override suspend fun pairApprove(code: String, agentName: String) = api.pairApprove(code, agentName)
    override suspend fun pairApprove(code: String, agentName: String, tagChar: String?) =
        api.pairApprove(code, agentName, tagChar)
    override suspend fun setDeviceTag(id: Long, tagChar: String?) = api.setDeviceTag(id, tagChar)
    override suspend fun putDeviceDefaults(id: Long, changes: Map<BoxDefaults.Key, String?>): BoxDefaults =
        api.putDeviceDefaults(id, changes)
    override fun boxDefaultsUpdates(): Flow<BoxDefaultsUpdate> = engine?.boxDefaultsUpdates() ?: emptyFlow()
}

/// Devices-screen state: the signed-in user's device roster with per-device
/// revoke. Pull-based (callers [refresh] on screen enter and the model re-fetches
/// after every mutation). The one live part is each agent box's defaults for
/// new sessions (`box_defaults` frames, while [listenForBoxDefaults] runs).
/// Ported from matron-apple's `DevicesViewModel`.
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

    /// `false` once `PUT /devices/:id/defaults` has 404ed — a journal
    /// without box defaults — so the editor hides for the rest of the visit.
    private val _boxDefaultsSupported = MutableStateFlow(true)
    val boxDefaultsSupported: StateFlow<Boolean> = _boxDefaultsSupported.asStateFlow()

    /// The save in progress per box (one at a time): the journal's last
    /// known state for it, and the picks queued for the next `PUT`, in the
    /// order made.
    private class BoxSave(var confirmed: BoxDefaults) {
        var inFlight = false
        /// The latest live frame that landed while this save ran — not
        /// shown, since it may be the echo of an earlier `PUT` of ours, older
        /// than the queued picks and the answer still to come.
        var frameDuringSave: BoxDefaults? = null
        val pending = LinkedHashMap<BoxDefaults.Key, String?>()

        fun withPending(base: BoxDefaults): BoxDefaults =
            pending.entries.fold(base) { acc, (key, value) -> acc.applying(key, value) }
    }

    private val boxSaves = mutableMapOf<Long, BoxSave>()

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

    // Box defaults (journal "Box defaults")

    /// Whether [device] gets the New sessions editor: an agent box the
    /// journal reported defaults for (an older journal sends none), on a
    /// journal that has not 404ed the route.
    fun showsBoxDefaults(device: DeviceDTO): Boolean =
        _boxDefaultsSupported.value && device.kind == "agent" && device.defaults != null

    /// Picks [value] (null = Box default) for one of [device]'s defaults:
    /// shown at once — a new agent clears the model, as the journal does —
    /// then saved. One save at a time per box: a pick made while one is on
    /// the wire waits, coalesced with any others, for the next `PUT`, so an
    /// agent change and the model picked right after it travel together and
    /// the journal's "new agent clears the model" can't wipe the model.
    /// Each answer becomes the box's confirmed state, with the still-queued
    /// picks shown on top; a refusal puts back the confirmed state (plus the
    /// queue) and says why. Live frames for the box are held back while
    /// the save runs — one may be the echo of our own earlier `PUT`,
    /// arriving after the follow-up left — so the answer to the newest
    /// request we sent wins; a refusal falls back to the latest held frame.
    /// A 404 hides the editor and drops the queue.
    suspend fun setBoxDefault(device: DeviceDTO, key: BoxDefaults.Key, value: String?) {
        val shown = _devices.value.firstOrNull { it.id == device.id }?.defaults ?: return
        val picked = shown.applying(key, value)
        if (picked == shown) return
        val save = boxSaves.getOrPut(device.id) { BoxSave(confirmed = shown) }
        // A new agent clears the model, so a model queued for the old agent
        // goes with it; one picked after this re-queues (and is sent along).
        if (key == BoxDefaults.Key.AGENT) save.pending.remove(BoxDefaults.Key.MODEL)
        save.pending[key] = value
        setDefaults(picked, device.id)
        if (save.inFlight) return // the running save sends it next
        save.inFlight = true
        try {
            while (save.pending.isNotEmpty()) {
                val changes = LinkedHashMap(save.pending)
                save.pending.clear()
                try {
                    val stored = api.putDeviceDefaults(device.id, changes)
                    // The answer to our newest request: it already holds
                    // whatever any frame received meanwhile reported.
                    save.confirmed = stored
                    save.frameDuringSave = null
                    setDefaults(save.withPending(stored), device.id)
                    _errorMessage.value = null
                } catch (cancel: CancellationException) {
                    save.pending.clear()
                    throw cancel
                } catch (error: Throwable) {
                    // Nothing was written; the latest frame held back
                    // (another device's change, or an echo) is the newest
                    // journal state we know.
                    save.frameDuringSave?.let { save.confirmed = it }
                    save.frameDuringSave = null
                    setDefaults(save.withPending(save.confirmed), device.id)
                    if (error is JournalApiError.NotFound) {
                        save.pending.clear()
                        setDefaults(save.confirmed, device.id)
                        _boxDefaultsSupported.value = false
                        _errorMessage.value = "This journal can't set defaults for ${device.name}."
                    } else {
                        val what = changes.keys.singleOrNull()?.let { "the default ${it.errorName}" } ?: "the defaults"
                        _errorMessage.value = "Couldn't save $what for ${device.name} — ${describeBoxDefaults(error)}"
                    }
                }
            }
        } finally {
            save.inFlight = false
            boxSaves.remove(device.id)
        }
    }

    /// Applies live `box_defaults` frames until the flow ends or the caller
    /// is cancelled — run it in the screen's `LaunchedEffect`. A frame is
    /// the box's full new state, this device's own echo included.
    suspend fun listenForBoxDefaults() {
        api.boxDefaultsUpdates().collect { apply(it) }
    }

    /// A frame is the journal's state, shown as is — except while a save of
    /// ours runs on that box, when it is held back for [setBoxDefault]'s
    /// answer (which supersedes it) or its revert.
    internal fun apply(update: BoxDefaultsUpdate) {
        val save = boxSaves[update.deviceID]
        if (save != null) {
            save.frameDuringSave = update.defaults
            return
        }
        setDefaults(update.defaults, update.deviceID)
    }

    /// Patches one row; a box not on the roster (yet) is left to the next
    /// [refresh].
    private fun setDefaults(defaults: BoxDefaults, id: Long) {
        _devices.value = _devices.value.map { if (it.id == id) it.copy(defaults = defaults) else it }
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

        /// The journal's 400 codes in words; anything else as [describe].
        fun describeBoxDefaults(error: Throwable): String {
            if (error is JournalApiError.Http && error.status == 400) {
                when (error.serverMessage) {
                    "bad_agent" -> return "the journal doesn't know that agent."
                    "bad_model" -> return "that isn't a model name the journal accepts."
                    "bad_effort" -> return "the journal doesn't know that effort level."
                    "not_agent_device" -> return "only agent boxes have defaults."
                }
            }
            return describe(error)
        }

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
