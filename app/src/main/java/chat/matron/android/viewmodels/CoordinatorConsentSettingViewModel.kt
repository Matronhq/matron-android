package chat.matron.android.viewmodels

import chat.matron.android.journal.CoordinatorConsentProviding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Settings → Coordinator's "Let the Coordinator approve chats and spawns"
/// switch: the journal's `consent` flag on `/coordinator`, on by default.
/// Read when the screen opens, written optimistically: the switch moves at
/// once, and a failed `PUT` puts it back and says why. Hidden until the
/// journal answers, and for a journal without the switch. Ported from
/// matron-apple's `CoordinatorConsentStore`.
class CoordinatorConsentSettingViewModel(
    private val api: CoordinatorConsentProviding,
    private val scope: CoroutineScope,
) {
    /// Null until the journal answers, and for a journal without the switch:
    /// the row hides rather than offering a switch that does nothing.
    private val _enabled = MutableStateFlow<Boolean?>(null)
    val enabled: StateFlow<Boolean?> = _enabled.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /// The last failed write, for the row to show. Cleared by the next one.
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    /// Bumped by every write, so a `GET` that started before one is dropped
    /// rather than undoing the user's tap.
    private var epoch = 0L

    fun start() {
        stop()
        job = scope.launch { load() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun load() {
        val started = epoch
        try {
            val value = api.coordinatorConsent()
            if (epoch == started) _enabled.value = value
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            // Keep whatever was showing: a flaky GET must not flip the switch,
            // and before the first answer there is nothing to show.
        }
    }

    /// Optimistic: the switch moves at once, the journal is told second, and
    /// a failure puts it back and says why. The switch is disabled while a
    /// write is in flight, so writes reach the journal in tap order.
    suspend fun setEnabled(on: Boolean) {
        val before = _enabled.value ?: return
        if (before == on || _isSaving.value) return
        val sent = ++epoch
        _enabled.value = on
        _isSaving.value = true
        _error.value = null
        try {
            val stored = api.setCoordinatorConsent(on) ?: on
            if (epoch == sent) _enabled.value = stored
        } catch (cancel: CancellationException) {
            if (epoch == sent) _enabled.value = before
            throw cancel
        } catch (error: Throwable) {
            if (epoch == sent) {
                _enabled.value = before
                _error.value = "Couldn't save the setting: ${error.message ?: error}"
            }
        } finally {
            _isSaving.value = false
        }
    }

    companion object {
        const val TITLE = "Let the Coordinator approve chats and spawns"
        const val HELP =
            "Your Coordinator can answer agent chat and spawn requests for you. Each card then says so, with its reason."
    }
}
