package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import chat.matron.android.viewmodels.CoordinatorSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Keeps this device's cached Coordinator ([CoordinatorSetting]) in step
/// with the journal's (Coordinator redesign §3a). Reads: `GET /coordinator`
/// on start, the `hello_ok` / snapshot field on every connect
/// ([CoordinatorUpdate.Snapshot]) and live `coordinator` events. Writes:
/// [set], the user's own pick or clear. The cache is what every view reads,
/// so nothing else writes it.
///
/// Reconnect-vs-backlog ordering is resolved upstream, in
/// `JournalSyncEngine.publishCoordinatorEvent`, by comparing each event's
/// seq against the hello's head seq. Ported from matron-apple's
/// `CoordinatorSync` actor: its state sits behind one monitor [lock] that is
/// never held across a request, and [epoch] guards the same interleavings
/// actor reentrancy allowed there.
class CoordinatorSync(
    private val api: CoordinatorProviding,
    private val setting: CoordinatorSetting,
    private val updates: () -> Flow<CoordinatorUpdate>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lock = Any()

    /// The Coordinator conversation as the views read it.
    val convoID: StateFlow<String?> get() = setting.convoID

    /// `null` until the journal answers; `false` once `GET /coordinator`
    /// 404s — a journal predating the route, where the cache is the only
    /// store and [set] writes it alone.
    private val _isSupported = MutableStateFlow<Boolean?>(null)
    val isSupported: StateFlow<Boolean?> = _isSupported.asStateFlow()

    // Guarded by `lock`.
    private var updatesJob: Job? = null
    private var refreshJob: Job? = null
    /// Bumped by every applied live update and by [set]. A request that
    /// started before a bump answers with older news than what already
    /// landed, so its answer is dropped.
    private var epoch = 0

    fun start() {
        synchronized(lock) {
            if (updatesJob != null) return
            updatesJob = scope.launch { updates().collect { apply(it) } }
            refreshJob = scope.launch { refresh() }
        }
    }

    /// Cancels the feed and any request in flight and joins them, so a
    /// sign-out can never race an answer back into the cache.
    suspend fun stop() {
        val jobs = synchronized(lock) {
            epoch += 1
            listOfNotNull(updatesJob, refreshJob).also { updatesJob = null; refreshJob = null }
        }
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
    }

    /// `GET /coordinator` and reconcile. A transport failure leaves the
    /// cache as it is; the next connect's hello reconciles instead.
    suspend fun refresh() {
        val startEpoch = synchronized(lock) { epoch }
        val journal = try {
            api.coordinator()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: JournalApiError.NotFound) {
            _isSupported.value = false
            return
        } catch (e: Throwable) {
            MatronDebug.breadcrumb("CoordinatorSync: GET /coordinator failed: $e")
            return
        }
        // Answered at all: the route exists, even if the value is stale.
        _isSupported.value = true
        if (synchronized(lock) { epoch } != startEpoch) return
        reconcile(journal)
    }

    /// The user's pick or clear (Settings, the chooser, New coordinator
    /// chat). It shows at once — the tab root, the Settings row and the
    /// badges all read the cache — and the journal's answer settles it: a
    /// failed `PUT` puts the previous value back (unless a live update
    /// landed meanwhile, which is newer than both) and reaches the caller.
    suspend fun set(convoID: String?) {
        if (_isSupported.value == false) {
            synchronized(lock) { setting.set(convoID); epoch += 1 }
            return
        }
        val (previous, pickEpoch) = synchronized(lock) {
            val previous = setting.convoID.value
            setting.set(convoID)
            epoch += 1
            previous to epoch
        }
        try {
            val stored = api.setCoordinator(convoID)
            synchronized(lock) {
                if (epoch == pickEpoch) setting.set(stored)
                setting.migrated = true
                epoch += 1
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (notFound: JournalApiError.NotFound) {
            // We never learned whether this journal has the route (the
            // start-up GET failed transport-side rather than 404ing), so a
            // 404 reads as "no route" as plausibly as "not owned". Treat it
            // as the former: the pick, already cached, stays.
            if (_isSupported.value == null) {
                _isSupported.value = false
                return
            }
            revert(previous, pickEpoch)
            throw notFound
        } catch (e: Throwable) {
            revert(previous, pickEpoch)
            throw e
        }
    }

    private fun revert(previous: String?, pickEpoch: Int) = synchronized(lock) {
        if (epoch == pickEpoch) setting.set(previous)
    }

    private suspend fun apply(update: CoordinatorUpdate) {
        when (update) {
            is CoordinatorUpdate.Snapshot -> {
                synchronized(lock) { epoch += 1 }
                _isSupported.value = true
                reconcile(update.convoID)
            }
            is CoordinatorUpdate.Assigned -> synchronized(lock) {
                epoch += 1
                _isSupported.value = true
                setting.set(update.convoID)
                setting.migrated = true
            }
            is CoordinatorUpdate.Released -> synchronized(lock) {
                epoch += 1
                _isSupported.value = true
                if (setting.convoID.value == update.convoID) setting.set(null)
                setting.migrated = true
            }
        }
    }

    private suspend fun reconcile(journal: String?) {
        val (decision, startEpoch) = synchronized(lock) {
            CoordinatorSetting.reconcile(journal, setting.convoID.value, setting.migrated) to epoch
        }
        when (decision) {
            is CoordinatorSetting.Reconcile.Adopt -> synchronized(lock) {
                if (epoch != startEpoch) return
                setting.set(decision.convoID)
                setting.migrated = true
            }
            is CoordinatorSetting.Reconcile.Push -> {
                // A live update can land while this PUT is in flight; its
                // answer (success or 404) is then stale and must not
                // overwrite what the update set.
                try {
                    val stored = api.setCoordinator(decision.convoID)
                    synchronized(lock) {
                        if (epoch != startEpoch) return
                        setting.set(stored)
                        setting.migrated = true
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: JournalApiError.NotFound) {
                    // The cached chat is gone or not this user's: nothing to carry over.
                    synchronized(lock) {
                        if (epoch != startEpoch) return
                        setting.set(null)
                        setting.migrated = true
                    }
                } catch (e: Throwable) {
                    // Stays unmigrated: the next hello tries again.
                    MatronDebug.breadcrumb("CoordinatorSync: migration PUT failed: $e")
                }
            }
        }
    }
}
