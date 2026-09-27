package chat.matron.android.journal

import chat.matron.android.events.MissionMarker
import chat.matron.android.models.MatronDebug
import chat.matron.android.models.Mission
import chat.matron.android.models.SyncConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/// What a [MissionsSync.refresh] / [MissionsSync.refreshMission] pass did.
/// [Unsupported] is a real answer from an old journal (404 on
/// `GET /missions`), not a transport fault — the Missions tab hides itself.
sealed interface MissionsRefreshOutcome {
    data object Succeeded : MissionsRefreshOutcome
    data object Unsupported : MissionsRefreshOutcome
    /// `stop()` landed mid-pass (sign-out / teardown).
    data object Stopped : MissionsRefreshOutcome
    data class Failed(val message: String) : MissionsRefreshOutcome
}

/// The write/refresh surface the view models depend on, mirroring
/// [ItemsSyncing]. [MissionsSync] implements it; tests fake it.
interface MissionsSyncing {
    suspend fun refresh(): MissionsRefreshOutcome
    suspend fun refreshMission(id: String): MissionsRefreshOutcome
    suspend fun closeMission(id: String, summary: String): Mission

    /// Tri-state: `null` until the first list fetch answers, `false` once
    /// `GET /missions` has 404'd (a journal predating missions), `true` once
    /// a fetch has actually succeeded. A transport failure never flips it.
    val isSupported: StateFlow<Boolean?>
}

/// Keeps the local mission cache fresh (spec: Apps → Shared core). Three
/// triggers refetch: a `mission`/`milestone` marker (refetch THAT mission),
/// a reconnect (full list), and an explicit refresh from a view model.
/// Markers are invalidation signals only — nothing they carry is written to
/// the store, because a marker written across the privacy boundary has no
/// title at all. There is no outbox: the one write the apps can make (a
/// user close) is an interactive, foreground action that reports its own
/// failure. Ported from matron-apple's `MissionsSync` actor; the
/// bookkeeping is confined by one monitor [lock] whose critical sections
/// never suspend, the same shape as [ItemsSync].
class MissionsSync(
    private val api: MissionsProviding,
    private val store: JournalStore,
    private val markers: () -> Flow<Pair<String, MissionMarker>>,
    private val connectionStates: () -> Flow<SyncConnectionState>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MissionsSyncing {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lock = Any()

    // Guarded by `lock`.
    private var markerJob: Job? = null
    private var stateJob: Job? = null
    /// List-refresh coalescing, mirroring `ItemsSync.refresh(scope)`: a
    /// reconnect, a tab open and a pull-to-refresh landing together each ran
    /// their own full GET over the same rows. One scope here, so one slot.
    private var inFlightRefresh: Deferred<MissionsRefreshOutcome>? = null
    /// Per-mission refetch coalescing, mirroring `ItemsSync.refreshItem`: a
    /// joiner AWAITS the run already in flight (callers take "refreshMission
    /// returned" to mean the store now holds the server's page), and the
    /// running pass repeats once more if another was requested meanwhile.
    private val inFlightRefetches = mutableMapOf<String, Deferred<MissionsRefreshOutcome>>()
    private val refetchAgain = mutableSetOf<String>()
    /// Ids a detail refresh (or a close) has written since the CURRENT
    /// full-list GET started. [refreshOnce] clears this right before
    /// issuing `listMissions` and hands it to `replaceMissions`, so a
    /// mission that didn't exist when the list request was made — but was
    /// created and detail-fetched (via a marker) while that request was in
    /// flight — survives the authoritative sweep instead of being read as
    /// "the list doesn't have it, so it's gone", and its fresher row is not
    /// reverted by the stale list row either.
    private val protectedSinceListStart = mutableSetOf<String>()
    /// Serialises every store write this sync makes — the list replace
    /// (with the protected-set snapshot it reads) and the detail / close
    /// upserts (with the registration they make). Apple's `MissionsSync` is
    /// an actor over a synchronous GRDB queue, so nothing can interleave a
    /// detail write between the snapshot and the replace; Room writes
    /// suspend, so without this a detail refresh or a user close committing
    /// in that window would not be in the snapshot and the older list row
    /// would overwrite it (Bugbot, #79). Internal so a test can hold it.
    internal val writes = Mutex()
    /// Test-only observability: incremented the instant a joiner registers
    /// against an in-flight refetch, so a test can wait for that
    /// registration deterministically instead of sleeping.
    @Volatile
    internal var refetchJoins = 0
        private set
    /// Test-only observability: incremented when a per-mission refetch run
    /// has finished EVERY write and deregistered — the completion signal a
    /// test waits on instead of polling one store row (the first of three
    /// writes) and then asserting the rest.
    @Volatile
    internal var refetchesCompleted = 0
        private set
    /// The user-close writes in flight, so [stop] can cancel and join them
    /// like the refetches: the API call is the caller's, but the store
    /// write it leads to must never land after a sign-out wipe (Bugbot, #79).
    private val closeWrites = mutableSetOf<Job>()
    internal val closeWritesInFlight: Int get() = synchronized(lock) { closeWrites.size }

    /// Set by [stop], cleared by [start]. Every write site re-checks it
    /// immediately after its await, before touching the store.
    @Volatile
    private var stopped = false

    private val _isSupported = MutableStateFlow<Boolean?>(null)
    override val isSupported: StateFlow<Boolean?> = _isSupported.asStateFlow()

    fun start() {
        synchronized(lock) {
            stopped = false
            if (markerJob != null) return
            markerJob = scope.launch {
                markers().collect { (_, marker) -> refreshMission(marker.missionID) }
            }
            stateJob = scope.launch {
                connectionStates().collect { state ->
                    // No `isSupported = true` here: publishing "supported"
                    // before the probe would flash true→false for an old
                    // journal that 404s. `refresh` publishes the real answer.
                    if (state is SyncConnectionState.Running) refresh()
                }
            }
        }
    }

    /// Suspends until nothing more will happen: cancels the marker/state
    /// jobs AND every in-flight refresh and refetch, and joins them all, so
    /// a caller's sign-out wipe can never race a resuming network call's
    /// store write. Same contract as [ItemsSync.stop].
    suspend fun stop() {
        val toJoin: List<Job>
        synchronized(lock) {
            stopped = true
            toJoin = listOfNotNull(markerJob, stateJob, inFlightRefresh) + inFlightRefetches.values + closeWrites
            markerJob = null; stateJob = null
        }
        toJoin.forEach { it.cancel() }
        toJoin.forEach { it.join() }
    }

    /// Full `GET /missions` (both states — the list shows open and a
    /// collapsed Closed section). Joiners of a coalesced run get the SAME
    /// outcome, so two triggers racing one fetch cannot disagree.
    override suspend fun refresh(): MissionsRefreshOutcome {
        val run: Deferred<MissionsRefreshOutcome> = synchronized(lock) {
            inFlightRefresh ?: run {
                lateinit var self: Deferred<MissionsRefreshOutcome>
                self = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        refreshOnce()
                    } finally {
                        // Only clear the slot if it still holds THIS task.
                        synchronized(lock) { if (inFlightRefresh === self) inFlightRefresh = null }
                    }
                }
                inFlightRefresh = self
                self
            }
        }
        run.start()
        return awaitOutcome(run)
    }

    private suspend fun awaitOutcome(run: Deferred<MissionsRefreshOutcome>): MissionsRefreshOutcome =
        try {
            run.await()
        } catch (cancel: CancellationException) {
            currentCoroutineContext().ensureActive()
            MissionsRefreshOutcome.Stopped
        }

    private suspend fun refreshOnce(): MissionsRefreshOutcome {
        // Full list, unconditionally — unlike `ItemsSync`, the row counts
        // (`open_items`, `needs_you`, …) are server-side aggregates over
        // OTHER tables, so a `?since=` on `missions.updated_at` cannot see an
        // item answered or closed and the tab badge would go permanently
        // stale. The list is tens of rows; a full GET is cheap and right.
        synchronized(lock) { protectedSinceListStart.clear() }
        return try {
            val decoded = api.listMissions(MissionsListQuery())
            if (stopped || !currentCoroutineContext().isActive) return MissionsRefreshOutcome.Stopped
            // Authoritative: a mission the server no longer returns must not
            // linger — except one a concurrent detail refresh just wrote that
            // this (now stale) response predates, or one this device merely
            // failed to DECODE this time.
            // Snapshot and replace under the write lock: no detail write can
            // register or commit between the two.
            writes.withLock {
                val protected = synchronized(lock) { protectedSinceListStart.toSet() } + decoded.droppedIDs
                store.replaceMissions(decoded.missions, protected)
            }
            _isSupported.value = true
            MissionsRefreshOutcome.Succeeded
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (notFound: JournalApiError.NotFound) {
            // The journal has no /missions routes. Not a transport fault: the
            // server answered, and the tab hides itself.
            _isSupported.value = false
            MissionsRefreshOutcome.Unsupported
        } catch (error: Throwable) {
            MatronDebug.breadcrumb("MissionsSync: refresh failed: $error")
            MissionsRefreshOutcome.Failed(error.message ?: error.toString())
        }
    }

    /// `GET /missions/:id` — the mission row, its milestones, its open items
    /// and its conversations, all written in one pass. Called on a marker
    /// and whenever a mission page opens. Returns the outcome so
    /// `MissionDetailViewModel.refresh()` can tell a transport failure from a
    /// quiet success and surface it.
    override suspend fun refreshMission(id: String): MissionsRefreshOutcome {
        val run: Deferred<MissionsRefreshOutcome> = synchronized(lock) {
            inFlightRefetches[id]?.also {
                refetchAgain.add(id)
                refetchJoins += 1
            } ?: run {
                lateinit var self: Deferred<MissionsRefreshOutcome>
                self = scope.async(start = CoroutineStart.LAZY) {
                    var deregistered = false
                    try {
                        var outcome: MissionsRefreshOutcome
                        while (true) {
                            outcome = refreshMissionOnce(id)
                            // The final "again?" check and the deregistration
                            // are one critical section, so a joiner can never
                            // flag `refetchAgain` against a finished task.
                            val again = synchronized(lock) {
                                if (refetchAgain.remove(id)) {
                                    true
                                } else {
                                    if (inFlightRefetches[id] === self) inFlightRefetches.remove(id)
                                    deregistered = true
                                    false
                                }
                            }
                            if (!again) break
                        }
                        outcome
                    } finally {
                        if (!deregistered) synchronized(lock) { if (inFlightRefetches[id] === self) inFlightRefetches.remove(id) }
                        refetchesCompleted += 1
                    }
                }
                inFlightRefetches[id] = self
                self
            }
        }
        run.start()
        return awaitOutcome(run)
    }

    private suspend fun refreshMissionOnce(id: String): MissionsRefreshOutcome {
        return try {
            val detail = api.mission(id)
            if (stopped) return MissionsRefreshOutcome.Stopped
            writes.withLock {
                // Registered BEFORE the write, under the same lock the list
                // replace snapshots under, so the id is protected from the
                // moment the write is decided — never a window in which the
                // row is committed but a concurrent, older list response
                // could still sweep or overwrite it.
                synchronized(lock) { protectedSinceListStart.add(detail.mission.id) }
                // The detail row carries the same aggregates as a list row
                // (the journal's `getMission` selects `countsSql`), so this
                // upsert never zeroes `needs_you` / `last_milestone`.
                store.upsertMissions(listOf(detail.mission))
                store.replaceMilestones(detail.mission.id, detail.milestones)
                store.replaceMissionConversations(detail.mission.id, detail.conversations)
                // The detail's items are ordinary tracker rows carrying
                // `mission_id`; upserting them keeps the tracker cache and the
                // mission page in agreement without a second /items fetch.
                if (detail.items.isNotEmpty()) store.upsertItems(detail.items)
            }
            _isSupported.value = true
            MissionsRefreshOutcome.Succeeded
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (notFound: JournalApiError.NotFound) {
            // Unknown, or invisible to this caller. Not a support signal —
            // `refresh` owns `isSupported` — and not a failure to surface
            // either: the page has nothing to retry into.
            MatronDebug.breadcrumb("MissionsSync: mission $id not found or not visible")
            MissionsRefreshOutcome.Succeeded
        } catch (error: Throwable) {
            MatronDebug.breadcrumb("MissionsSync: mission refetch $id failed: $error")
            MissionsRefreshOutcome.Failed(error.message ?: error.toString())
        }
    }

    /// The user's own close. Throws so the view model can surface the real
    /// message; the returned row is written straight to the store so the
    /// page flips to "closed" without waiting for a marker round-trip, and
    /// its id is protected from an in-flight list GET that still holds the
    /// older, still-open snapshot.
    override suspend fun closeMission(id: String, summary: String): Mission {
        val mission = api.closeMission(id, summary)
        // The write runs as a job on this sync's scope, registered under the
        // lock in the same critical section that checks `stopped`, so a
        // `stop()` landing at any point after this either cancels it or
        // joins it — and the wipe that follows `stop()` can never be beaten
        // by a close committing the previous session's row.
        val write: Job
        synchronized(lock) {
            if (stopped) return mission
            lateinit var self: Job
            self = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    writes.withLock {
                        // Re-checked under the write lock, right before the
                        // upsert: a stop that won the race to the lock wins.
                        if (stopped) return@withLock
                        // Same ordering as `refreshMissionOnce`: protect
                        // first, write second, both under the write lock.
                        synchronized(lock) { protectedSinceListStart.add(mission.id) }
                        store.upsertMissions(listOf(mission))
                    }
                } finally {
                    synchronized(lock) { closeWrites.remove(self) }
                }
            }
            closeWrites.add(self)
            write = self
        }
        write.start()
        // A caller cancelled while waiting leaves the write for `stop()` to
        // own; a write `stop()` cancelled simply never lands.
        write.join()
        return mission
    }
}
