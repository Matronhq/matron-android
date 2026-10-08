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

/// Settings → For you: the journal-held "Send things I need to read to For
/// you" switch (`notices`). Seeds from `GET /settings` when the screen
/// opens, follows every `hello_ok` / `settings` control frame while it
/// shows ([updates]), and writes through `PATCH /settings`. A journal
/// predating the route 404s the GET and the switch stays hidden.
class NoticesSettingViewModel(
    private val api: UserSettingsProviding,
    private val updates: () -> Flow<UserSettings>,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        /// Not loaded yet, or the load failed for a reason other than a 404 —
        /// nothing to show a switch for.
        data object Loading : State
        /// The journal has no `/settings` route: the switch is not offered.
        data object Unsupported : State
        data class Loaded(val notices: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var jobs = listOf<Job>()

    /// Bumped by every value newer than a GET could know about: a live frame,
    /// a PATCH (sent or answered). A GET only lands if nothing bumped it while
    /// it was in flight, so its older answer cannot overwrite a fresher one.
    private var epoch = 0L

    fun start() {
        stop()
        jobs = listOf(
            // Subscribed before the GET goes out, so a change that lands
            // while it is in flight is not lost behind its answer.
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
            if (epoch == started) show(settings)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: JournalApiError.NotFound) {
            // A frame that already landed proves the journal has settings.
            if (epoch == started) _state.value = State.Unsupported
        } catch (error: Throwable) {
            // Keep whatever was showing: a flaky GET must not flip the switch.
            if (_state.value == State.Loading) _error.value = error.message ?: error.toString()
        }
    }

    /// Optimistic: the switch moves at once, the journal is told second, and
    /// a failure puts it back and says why. Like [load], the answer (or the
    /// revert) only lands if no live frame or later PATCH moved the switch
    /// while this one was in flight — that newer value stands.
    suspend fun setNotices(on: Boolean) {
        val before = _state.value as? State.Loaded ?: return
        if (before.notices == on) return
        val sent = ++epoch
        _state.value = State.Loaded(on)
        _isSaving.value = true
        _error.value = null
        try {
            val answer = api.updateSettings(on)
            if (epoch == sent) apply(answer)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (epoch == sent) {
                _state.value = before
                _error.value = "Couldn't save the setting: ${error.message ?: error}"
            }
        } finally {
            _isSaving.value = false
        }
    }

    /// A journal answer or a live frame: the journal's word is final.
    fun apply(settings: UserSettings) {
        epoch++
        show(settings)
    }

    private fun show(settings: UserSettings) {
        _state.value = State.Loaded(settings.notices)
        _error.value = null
    }

    companion object {
        const val TITLE = "Send things I need to read to For you"
        const val HELP =
            "Agents file things you should read as items with a Seen button, instead of leaving them in chat."
    }
}
