package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.NotifyChange
import chat.matron.android.journal.NotifyDeviceLevel
import chat.matron.android.journal.NotifyMuteDuration
import chat.matron.android.journal.NotifySettings
import chat.matron.android.journal.NotifySettingsProviding
import chat.matron.android.journal.NotifyView
import chat.matron.android.models.SyncConnectionState
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// One conversation's notification state as the menus and headers show it.
data class ConvoNotifyState(
    /// `null` = follow the mode.
    val level: NotifySettings.ConvoLevel? = null,
    /// Only while the mute runs (epoch ms); a lapsed one reads `null`.
    val mutedUntil: Long? = null,
) {
    /// Nothing from this conversation pushes: the bell-off icon.
    val isSilenced: Boolean get() = level == NotifySettings.ConvoLevel.NONE || mutedUntil != null
}

/// The session's notification settings: what Settings → Notifications edits
/// and what each chat's Notifications menu reads. Reads `GET /notify` on
/// every reconnect (the live frame has no replay, so one missed while
/// offline is caught there) and when the screen opens, and applies live
/// `notify` frames as they land. Writes are optimistic: the change shows at
/// once and a failed `PUT` takes back exactly that change.
///
/// Shown state is the journal's newest answer with the writes still in
/// flight replayed on top, so overlapping writes never roll each other back
/// and a frame or `GET` landing mid-write keeps the user's pending change on
/// screen. "Newest" is by when the request went out (or the frame arrived),
/// not by when its answer lands: each request and frame takes a ticket, and
/// an answer older than what is shown is dropped. The synced settings and
/// this device's level are kept apart, since a live frame carries only the
/// former. Port of matron-apple's `NotifySettingsStore`.
class NotifySettingsStore(
    private val api: NotifySettingsProviding,
    private val updates: () -> Flow<NotifySettings>,
    private val connectionStates: () -> Flow<SyncConnectionState>,
    private val now: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lock = Any()

    private val _view = MutableStateFlow<NotifyView?>(null)
    /// `null` until the first `GET /notify` answers.
    val view: StateFlow<NotifyView?> = _view.asStateFlow()

    private val _isSupported = MutableStateFlow<Boolean?>(null)
    /// `null` until the journal answers; `false` once `GET /notify` 404s —
    /// a journal predating the route, where the screen says so.
    val isSupported: StateFlow<Boolean?> = _isSupported.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /// The last failed write, for the screen to show. Cleared by the next
    /// success.
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _clock = MutableStateFlow(now())
    /// Moved past each running mute's end when it lapses, so a menu's
    /// "muted until" goes away on time without anything else changing.
    val clock: StateFlow<Long> = _clock.asStateFlow()

    // Guarded by `lock`.
    private var confirmedSettings: NotifySettings? = null
    private var confirmedLevel: NotifyDeviceLevel = NotifyDeviceLevel.ALL
    /// The tickets of the answers [confirmedSettings] and [confirmedLevel]
    /// came from; `0` = none yet.
    private var settingsTicket = 0L
    private var levelTicket = 0L
    private var nextTicket = 1L
    /// Writes in flight, oldest first, each with the ticket it was sent with.
    private val pending = mutableListOf<Pending>()
    private var nextWriteID = 0
    /// The newest ticket of a `PUT` answer taken. The journal applies writes
    /// in the order they arrive, so that answer already holds every older
    /// write: those are no longer replayed on top of it.
    private var writeTicket = 0L
    /// Successful saves so far; a `GET` sent while a save was in flight
    /// compares it to tell whether that save answered before it did.
    private var savesDone = 0L

    private data class Pending(val id: Int, val ticket: Long, val change: NotifyChange)
    private var jobs = listOf<Job>()
    private var expiryJob: Job? = null

    fun start() {
        synchronized(lock) {
            if (jobs.isNotEmpty()) return
            jobs = listOf(
                scope.launch { updates().collect { applyLive(it) } },
                scope.launch {
                    connectionStates().collect { if (it is SyncConnectionState.Running) refresh() }
                },
            )
        }
    }

    fun stop() {
        synchronized(lock) {
            jobs.forEach { it.cancel() }
            jobs = emptyList()
            expiryJob?.cancel()
            expiryJob = null
            // Nothing still in flight may land after this.
            settingsTicket = Long.MAX_VALUE
            levelTicket = Long.MAX_VALUE
        }
    }

    /// One conversation's level and running mute, as of [clock].
    fun state(convoID: String, view: NotifyView? = _view.value, nowMs: Long = _clock.value): ConvoNotifyState {
        val row = view?.settings?.override(convoID) ?: return ConvoNotifyState()
        return ConvoNotifyState(level = row.level, mutedUntil = row.muteUntil?.takeIf { row.isMuted(nowMs) })
    }

    /// The conversations with a level or a running mute, latest change first.
    fun activeOverrides(view: NotifyView? = _view.value, nowMs: Long = _clock.value): List<NotifySettings.ConvoOverride> =
        view?.settings?.convos.orEmpty().filter { it.isActive(nowMs) }

    /// `GET /notify`. A transport failure keeps what is shown.
    suspend fun refresh() {
        val (ticket, savesAtSend) = synchronized(lock) {
            nextTicket++ to savesDone.takeIf { pending.isNotEmpty() }
        }
        try {
            val answer = api.notifySettings()
            val readAgain = synchronized(lock) {
                _isSupported.value = true
                // Sent while a save was in flight, and that save has answered
                // since: this snapshot may predate it, and the save's own
                // answer is the better word. Read again instead.
                if (savesAtSend != null && savesDone != savesAtSend) {
                    true
                } else {
                    acceptLocked(answer, ticket)
                    recomputeLocked()
                    false
                }
            }
            if (readAgain) refresh()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: JournalApiError.NotFound) {
            _isSupported.value = false
        } catch (_: Throwable) {
            // Keep what is shown; the next reconnect or screen open re-reads.
        }
    }

    suspend fun setMode(mode: NotifySettings.Mode) = apply(NotifyChange.SetMode(mode))

    suspend fun setEvent(event: NotifySettings.Event, on: Boolean) {
        if (event == NotifySettings.Event.PROMPTS) return
        apply(NotifyChange.SetEvent(event, on))
    }

    suspend fun setDeviceLevel(level: NotifyDeviceLevel) = apply(NotifyChange.SetDeviceLevel(level))

    suspend fun setLevel(level: NotifySettings.ConvoLevel?, convoID: String) =
        apply(NotifyChange.ConvoLevel(convoID, level))

    suspend fun mute(convoID: String, duration: NotifyMuteDuration, zone: ZoneId = ZoneId.systemDefault()) =
        apply(muteChange(convoID, duration, zone))

    suspend fun unmute(convoID: String) = apply(NotifyChange.ConvoMute(convoID, null))

    suspend fun clearOverride(convoID: String) = apply(NotifyChange.ClearConvo(convoID))

    /// [apply] on the store's own scope, for a tap: leaving the screen
    /// mid-write must not cancel the write (and take the change back).
    fun post(change: NotifyChange): Job = scope.launch { apply(change) }

    /// The change a mute choice makes, its end counted from now.
    fun muteChange(convoID: String, duration: NotifyMuteDuration, zone: ZoneId = ZoneId.systemDefault()): NotifyChange =
        NotifyChange.ConvoMute(convoID, duration.endMs(now(), zone))

    /// Shows [change] at once, then `PUT`s it. The answer becomes the new
    /// confirmed state; a failure drops just this change.
    suspend fun apply(change: NotifyChange) {
        val (id, ticket) = synchronized(lock) {
            val id = nextWriteID++
            val ticket = nextTicket++
            pending += Pending(id, ticket, change)
            recomputeLocked()
            id to ticket
        }
        try {
            val answer = api.updateNotify(change)
            val superseded = synchronized(lock) {
                val taken = acceptLocked(answer, ticket)
                // Only an answer that is now shown stands in for older writes.
                if (taken) writeTicket = maxOf(writeTicket, ticket)
                savesDone++
                _isSupported.value = true
                _error.value = null
                // Something sent after this write already set what is shown.
                // A later write includes this one, but a re-read may have
                // been answered before the journal applied it: read again,
                // so an answer newer than both decides.
                pending.removeAll { it.id == id }
                recomputeLocked()
                !taken
            }
            if (superseded) refresh()
        } catch (cancel: CancellationException) {
            synchronized(lock) { pending.removeAll { it.id == id }; recomputeLocked() }
            throw cancel
        } catch (error: Throwable) {
            synchronized(lock) {
                pending.removeAll { it.id == id }
                _error.value = "Couldn't save notification settings: ${error.message ?: error}"
                recomputeLocked()
            }
        }
    }

    /// A `notify` frame: the synced part only. This device's level stays as
    /// its own `GET`/`PUT` last left it.
    private fun applyLive(settings: NotifySettings) = synchronized(lock) {
        settingsTicket = maxOf(settingsTicket, nextTicket++)
        confirmedSettings = settings
        _isSupported.value = true
        recomputeLocked()
    }

    /// Takes each part of a `GET`/`PUT` answer unless something newer
    /// already set it. `false` when either part was newer already.
    private fun acceptLocked(answer: NotifyView, ticket: Long): Boolean {
        val whole = ticket > settingsTicket && ticket > levelTicket
        if (ticket > settingsTicket) {
            settingsTicket = ticket
            confirmedSettings = answer.settings
            _error.value = null
        }
        if (ticket > levelTicket) {
            levelTicket = ticket
            confirmedLevel = answer.deviceLevel
        }
        return whole
    }

    private fun recomputeLocked() {
        val t = now()
        _clock.value = t
        val shown = confirmedSettings?.let { NotifyView(it, confirmedLevel) }?.let { base -> pending.filter { it.ticket > writeTicket }.fold(base) { acc, p -> p.change.appliedTo(acc) } }
        _view.value = shown
        scheduleExpiryLocked(shown, t)
    }

    /// Wakes when the soonest running mute ends and moves [clock] past it.
    private fun scheduleExpiryLocked(shown: NotifyView?, t: Long) {
        expiryJob?.cancel()
        expiryJob = null
        val next = shown?.settings?.convos.orEmpty().mapNotNull { it.muteUntil }.filter { it > t }.minOrNull() ?: return
        expiryJob = scope.launch {
            delay(next - t)
            synchronized(lock) { recomputeLocked() }
        }
    }
}
