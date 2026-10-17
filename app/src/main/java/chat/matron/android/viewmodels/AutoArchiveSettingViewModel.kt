package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.UserSettings
import chat.matron.android.journal.UserSettingsProviding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/// Settings → Archive: the journal's "Archive idle chats" window
/// (`auto_archive_days`, 0 = off). Same shape as [NoticesSettingViewModel]:
/// seeds from `GET /settings` when the screen opens, follows every
/// `hello_ok` / `settings` frame that carries the key, and writes through
/// `PATCH /settings`. Hidden on a journal whose settings have no
/// `auto_archive_days`.
///
/// Picks are sent one at a time, in the order chosen, so the journal ends on
/// the latest. A failed save goes back to the value the journal last
/// confirmed, and only while nothing newer (a later pick or a journal value)
/// has replaced the refused one.
class AutoArchiveSettingViewModel(
    private val api: UserSettingsProviding,
    private val updates: () -> Flow<UserSettings>,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Loading : State
        /// The journal does not offer auto-archive: the choice is not shown.
        data object Unsupported : State
        data class Loaded(val days: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var jobs = listOf<Job>()
    private val writes = Mutex()

    /// Bumped by every pick and every journal value: a GET, a PATCH answer
    /// or a revert only lands if nothing bumped it since it started.
    private var epoch = 0L
    /// The value the journal last confirmed (GET, frame, PATCH answer).
    private var confirmed: Int? = null
    /// Bumped by every journal value from a GET or frame, so a PATCH answer
    /// sent before one does not overwrite it as [confirmed].
    private var heard = 0L

    fun start() {
        stop()
        jobs = listOf(
            scope.launch { updates().collect { apply(it) } },
            scope.launch { load() },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    suspend fun load() {
        val started = epoch
        try {
            val settings = api.settings()
            if (epoch != started) return
            val days = settings.autoArchiveDays
            if (days == null) {
                _state.value = State.Unsupported
            } else {
                heard++
                confirmed = days
                _state.value = State.Loaded(days)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: JournalApiError.NotFound) {
            if (epoch == started) _state.value = State.Unsupported
        } catch (_: Throwable) {
            // Keep whatever was showing: a flaky GET must not hide the choice.
        }
    }

    /// Shows the pick at once and saves it. Returns once this pick's PATCH
    /// has been answered.
    suspend fun setDays(days: Int) {
        val current = _state.value as? State.Loaded ?: return
        if (current.days == days) return
        val sent = ++epoch
        _state.value = State.Loaded(days)
        _error.value = null
        writes.withLock {
            val heardAtSend = heard
            try {
                val answer = api.updateAutoArchiveDays(days)
                if (heard == heardAtSend) answer.autoArchiveDays?.let { confirmed = it }
                if (epoch == sent) answer.autoArchiveDays?.let { _state.value = State.Loaded(it) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                if (epoch == sent) {
                    _state.value = State.Loaded(confirmed ?: current.days)
                    _error.value = "Couldn't save the setting: ${error.message ?: error}"
                }
            }
        }
    }

    /// A journal value from a frame or a hello. One without the key (a
    /// notices-only payload) says nothing about this setting.
    fun apply(settings: UserSettings) {
        val days = settings.autoArchiveDays ?: return
        epoch++
        heard++
        confirmed = days
        _state.value = State.Loaded(days)
        _error.value = null
    }

    companion object {
        const val TITLE = "Archive idle chats"
        const val HELP =
            "Chats with no messages for this long move to Archived. Pinned chats, the Coordinator, running " +
                "sessions and chats with open missions or items stay."
        /// The choices offered, in days (0 = off).
        val CHOICES = listOf(0, 14, 30, 90)

        /// "Off", "After 30 days" … for a stored value, including one this
        /// build does not offer.
        fun label(days: Int): String = when (days) {
            0 -> "Off"
            1 -> "After 1 day"
            else -> "After $days days"
        }
    }
}
