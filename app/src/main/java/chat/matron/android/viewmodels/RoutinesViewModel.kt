package chat.matron.android.viewmodels

import chat.matron.android.events.RoutineMarkerEvent
import chat.matron.android.journal.RoutinesError
import chat.matron.android.journal.RoutinesProviding
import chat.matron.android.models.Routine
import chat.matron.android.models.RoutineDraft
import chat.matron.android.models.RoutineRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Backs Settings → Coordinator → Routines: the user's Coordinator
/// routines, sorted by name, with pause/resume, run now, create, edit and
/// delete. Ported from matron-apple's `RoutinesViewModel`.
///
/// Like the Memories screen: nothing is cached locally and nothing loads
/// until the screen calls [start], so a journal that predates `/routines`
/// answers its 404 on this screen alone (as `isSupported == false`).
///
/// Live: while started, every `routine` marker (create, edit, delete, fire)
/// schedules one refetch after a short delay, so a burst costs a single
/// `GET /routines`.
class RoutinesViewModel(
    private val api: RoutinesProviding,
    private val markers: () -> Flow<RoutineMarkerEvent>,
    private val scope: CoroutineScope,
    private val refetchDelayMs: Long = 250,
) {
    private val _routines = MutableStateFlow<List<Routine>?>(null)
    /// Sorted by name. `null` until the first load lands.
    val routines: StateFlow<List<Routine>?> = _routines.asStateFlow()

    private val _isSupported = MutableStateFlow<Boolean?>(null)
    /// `false` once the journal has 404'd `GET /routines`; `null` until the
    /// first answer; `true` once a load has succeeded.
    val isSupported: StateFlow<Boolean?> = _isSupported.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    /// The last load's failure. Loads never clear [routines] on failure.
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    /// The last list action's failure or note (pause, run now), shown once.
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _busyIDs = MutableStateFlow<Set<String>>(emptySet())
    /// Routines with a pause/resume or run request in flight.
    val busyIDs: StateFlow<Set<String>> = _busyIDs.asStateFlow()

    private var markerJob: Job? = null
    private var refetchJob: Job? = null
    /// Bumped by every load and every local write: an older answer that
    /// lands after a newer load started must never overwrite it.
    private var loadGeneration = 0

    val isStarted: Boolean get() = markerJob != null

    /// The screen appeared: follow markers (once) and load.
    fun start() {
        if (markerJob == null) {
            markerJob = scope.launch { markers().collect { scheduleRefetch() } }
        }
        scope.launch { load() }
    }

    /// The screen went away: stop following markers. The loaded list is
    /// kept, so coming back shows it at once while the reload runs.
    fun stop() {
        markerJob?.cancel(); markerJob = null
        refetchJob?.cancel(); refetchJob = null
    }

    private fun scheduleRefetch() {
        if (refetchJob != null) return
        refetchJob = scope.launch {
            delay(refetchDelayMs)
            refetchJob = null
            load()
        }
    }

    suspend fun load() {
        loadGeneration += 1
        val generation = loadGeneration
        _isLoading.value = true
        try {
            val fetched = api.listRoutines()
            if (generation != loadGeneration) return
            _routines.value = sorted(fetched)
            _isSupported.value = true
            _loadError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (_: RoutinesError.Unsupported) {
            if (generation != loadGeneration) return
            _isSupported.value = false
            _loadError.value = null
        } catch (e: Exception) {
            if (generation != loadGeneration) return
            _loadError.value = e.message ?: e.toString()
        } finally {
            if (generation == loadGeneration) _isLoading.value = false
        }
    }

    fun routine(id: String): Routine? = _routines.value?.firstOrNull { it.id == id }

    fun dismissActionMessage() {
        _actionMessage.value = null
    }

    fun showActionMessage(message: String?) {
        _actionMessage.value = message
    }

    /// Puts one routine's fresh copy into the list at once, so the row shows
    /// the change before the marker's refetch lands.
    private fun replace(routine: Routine) {
        _routines.value = sorted((_routines.value ?: emptyList()).filter { it.id != routine.id } + routine)
        // Any load already in flight was asked before this write: its answer
        // may not have it.
        loadGeneration += 1
        _isLoading.value = false
    }

    /// Pause (`false`) or resume (`true`).
    suspend fun setEnabled(enabled: Boolean, id: String) {
        if (id in _busyIDs.value) return
        _busyIDs.value = _busyIDs.value + id
        try {
            replace(api.setRoutineEnabled(id, enabled))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = e.message ?: e.toString()
            if (e is RoutinesError.NotFound) load()
        } finally {
            _busyIDs.value = _busyIDs.value - id
        }
    }

    /// Fires the routine now. The outcome lands later as a `fired` marker,
    /// which refetches the list. Answers `null` once the journal accepted
    /// it, otherwise what to tell the user (not sent, or the error).
    suspend fun run(id: String): String? {
        if (id in _busyIDs.value) return null
        _busyIDs.value = _busyIDs.value + id
        return try {
            api.runRoutine(id).message
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        } finally {
            _busyIDs.value = _busyIDs.value - id
        }
    }

    /// Why this form can't be saved, or `null`: the journal's rules, plus a
    /// new routine's name must not already be in the list.
    fun formError(isNew: Boolean, name: String, draft: RoutineDraft): String? {
        RoutineRules.formError(name, draft.title, draft.prompt, draft.tz, draft.schedule, draft.trigger)?.let { return it }
        val list = _routines.value
        if (isNew && list == null) {
            return if (_isSupported.value == false) RoutinesError.Unsupported.message else NOT_LOADED_ERROR
        }
        if (isNew && list!!.any { it.name == name }) {
            return "A routine named \"$name\" already exists. Open it from the list to change it."
        }
        if (isNew && list!!.size >= RoutineRules.MAX_ROUTINES) return RoutinesError.TooMany.message
        return null
    }

    /// Creates ([existingID] `null`) or edits. `null` on success, otherwise
    /// the message to show.
    suspend fun save(existingID: String?, name: String, draft: RoutineDraft): String? {
        val isNew = existingID == null
        if (isNew && _routines.value == null) load()
        formError(isNew, name, draft)?.let { return it }
        return try {
            replace(if (existingID != null) api.updateRoutine(existingID, draft) else api.createRoutine(name, draft))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    /// `null` on success, otherwise the message to show. A routine already
    /// gone counts as deleted.
    suspend fun delete(id: String): String? {
        try {
            api.deleteRoutine(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RoutinesError.NotFound) {
            // Already gone: drop it here too.
        } catch (e: Exception) {
            return e.message ?: e.toString()
        }
        _routines.value = _routines.value?.filter { it.id != id }
        loadGeneration += 1
        _isLoading.value = false
        return null
    }

    companion object {
        const val NOT_LOADED_ERROR = "Routines haven't loaded yet, so this name can't be checked. Try again in a moment."

        /// The journal's order (`ORDER BY name`).
        fun sorted(routines: List<Routine>): List<Routine> = routines.sortedBy { it.name }
    }
}
