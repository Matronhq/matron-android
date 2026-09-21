package chat.matron.android.journal

import chat.matron.android.models.MatronDebug
import chat.matron.android.search.SearchService
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// What [JournalMaintenance] needs from the store. An interface so the
/// scheduler can be tested against a recorder without a Room database;
/// [JournalStore] satisfies it as written. Timestamps are epoch ms.
interface MaintenanceSweeping {
    suspend fun purgeExpiredToolOutputSnippets(now: Long)
    suspend fun applyRetention(now: Long): List<Long>
    suspend fun maintenanceLastRun(): Long?
    suspend fun recordMaintenanceRun(at: Long)
    /// The tool_output/diff seqs past the retention window that have not
    /// yet been retired from the search index, and the cutoff this call
    /// actually finished scanning up to. Gated on its own watermark,
    /// independent of [applyRetention]'s — see
    /// `JournalStore.SEARCH_RETENTION_WATERMARK_KEY` for why the two must
    /// not share one.
    suspend fun pendingSearchRetirements(now: Long): JournalStore.SearchRetirements
    /// Advances the search-retention watermark. Callers must only call this
    /// after `SearchService.removeAll` has succeeded for the seqs that came
    /// with this cutoff.
    suspend fun recordSearchRetirement(upTo: Long)
}

/// The store's background housekeeping: the tool-output TTL sweep, the
/// 30-day retention sweep, and the search-index removal that follows it.
/// Port of matron-apple's `JournalMaintenance` actor (#212).
///
/// This replaces the sweep the composition root used to launch at store
/// creation — a full `event` scan of every tool_output row inside one write
/// transaction, on every launch, growing forever. Nothing here is on the
/// launch path: the first run is [FIRST_RUN_DELAY_MS] after construction
/// (or as soon as the first catch-up reaches the live cursor, whichever
/// comes first), and every pass runs on [dispatcher] (IO by default), never
/// on the main thread.
///
/// Failures are logged and retried at the next tick. `maintenance_last_run`
/// is stamped only after a complete pass, so a failed sweep does not buy
/// itself an hour of silence.
class JournalMaintenance(
    private val store: MaintenanceSweeping,
    /// The FTS index, or `null` when it failed to open — search retirement
    /// is then skipped and its watermark left untouched, so the same rows
    /// are found by a later pass on a process where it did open. (Apple
    /// also needs a late `attachSearch` for its locked-launch path; the
    /// Android index opens with the process, so there is no late attach.)
    private val search: SearchService?,
    /// Epoch-ms clock, injectable for tests.
    private val now: () -> Long = { System.currentTimeMillis() },
    /// Time between passes, and the staleness threshold every trigger uses.
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val firstRunDelayMs: Long = FIRST_RUN_DELAY_MS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()

    // Guarded by `lock`.
    private var inFlight: Job? = null
    private var schedule: Job? = null
    /// Set at the top of [stop]. [start], [runIfDue] and the engine's
    /// caught-up trigger all no-op once set: sign-out's teardown spends
    /// seconds on push deregistration between `stop()` and `store.wipe()`,
    /// plenty of time for a still-live sync engine reaching Running again
    /// to open a brand new pass against a store about to be wiped. There is
    /// no "unstop": a new sign-in builds a new instance on a new core.
    private var stopped = false
    /// Launch hold, armed at construction: [runIfDue] no-ops while `now` is
    /// before it. The foreground hook calls [runIfDue] with no guard of its
    /// own, and on a fresh launch the activity starts immediately, so on an
    /// upgrade with no stored `maintenance_last_run` that call would start
    /// the first (potentially history-sized) pass right on the launch path,
    /// contending for the database with first list paint and catch-up.
    /// Armed here rather than in [start] so no hook can reach the instance
    /// before the hold exists. [runAfterCatchUp] clears it early: catch-up
    /// finishing is the signal the launch path is over.
    @Volatile
    private var holdUntil: Long? = now() + firstRunDelayMs

    /// Arms the periodic cadence. Idempotent, and a no-op once [stop] has
    /// been called. The schedule's first tick sleeps [firstRunDelayMs] from
    /// now, so it always clears the construction-time hold on its own.
    fun start() {
        synchronized(lock) {
            if (stopped || schedule != null) return
            schedule = scope.launch {
                delay(firstRunDelayMs)
                runIfDue()
                while (isActive) {
                    delay(intervalMs)
                    runIfDue()
                }
            }
        }
    }

    /// Cancels the schedule AND waits for any pass already running, then
    /// fences every future trigger. Idempotent. Waiting matters: a pass
    /// suspended in `search.removeAll` would otherwise resume after sign-out
    /// has wiped the mirror and stamp `maintenance_last_run` on an empty
    /// `meta`. The store's sweeps observe cancellation at chunk boundaries
    /// and skip their watermark write, so the interrupted pass is resumed
    /// from the same range next time.
    suspend fun stop() {
        val (sched, pass) = synchronized(lock) {
            stopped = true
            val s = schedule
            schedule = null
            val p = inFlight
            inFlight = null
            s to p
        }
        sched?.cancel()
        pass?.cancel()
        pass?.join()
    }

    /// Sweeps when the stored `maintenance_last_run` is older than
    /// [intervalMs] (or absent). Every trigger — the first-run delay, the
    /// hourly tick, the sync engine's first catch-up, app foreground, and
    /// the background worker — funnels through here, so "whichever comes
    /// first" needs no extra state: the first caller does the work and the
    /// rest are no-ops. A no-op once [stop] has been called, and during the
    /// launch hold unless [ignoreLaunchHold] (a WorkManager-started process
    /// has no launch path to protect).
    suspend fun runIfDue(now: Long = this.now(), ignoreLaunchHold: Boolean = false) {
        if (stopped) return
        if (!ignoreLaunchHold) {
            val hold = holdUntil
            if (hold != null && now < hold) return
        }
        if (synchronized(lock) { inFlight?.isActive == true }) return
        val last = runCatching { store.maintenanceLastRun() }.getOrNull()
        if (last != null && now - last < intervalMs) return
        val pass = synchronized(lock) {
            if (stopped) return
            if (inFlight?.isActive == true) return
            scope.launch { run(now) }.also { inFlight = it }
        }
        // Joined (not awaited inline) so the pass lives on this instance's
        // scope: a cancelled caller (a lifecycle scope going STOPPED, a
        // worker deadline) drops the wait, not the sweep — `stop()` is the
        // one thing that cancels a pass.
        pass.join()
    }

    /// The engine's catch-up-complete signal: the replay reaching the live
    /// cursor means the launch path is over, so it's safe to run sooner
    /// than the first-run delay if catch-up itself took longer. Clears the
    /// hold unconditionally and then defers to the normal watermark gate.
    suspend fun runAfterCatchUp() {
        holdUntil = null
        runIfDue()
    }

    /// RETENTION FIRST. On a first pass the 24 h sweep's range contains
    /// every row older than 30 days, and [EventTombstone.apply] gives those
    /// the RETENTION rewrite anyway — running retention first just means a
    /// row gets the strongest applicable rewrite in one pass rather than
    /// the weaker one now and the stronger one an hour later.
    ///
    /// Search retirement is INDEPENDENT of [MaintenanceSweeping.applyRetention]'s
    /// return value — it has its own watermark — so a pass with no search
    /// attached simply leaves it untouched and the same rows are found once
    /// a search is available.
    private suspend fun run(now: Long) {
        try {
            val retentionVisited = store.applyRetention(now)
            store.purgeExpiredToolOutputSnippets(now)
            var searchRetired = 0
            val search = this.search
            if (search != null) {
                val pending = store.pendingSearchRetirements(now)
                if (pending.seqs.isNotEmpty()) {
                    // Search rows are keyed by `seq.toString()` by every
                    // feeder (JournalSyncEngine.indexForSearch), so the seqs
                    // this scan returns ARE the index's event ids.
                    search.removeAll(pending.seqs.map { it.toString() })
                }
                // Recorded even when `seqs` is empty: an empty pass still
                // scanned up to the cutoff, and skipping this write would
                // re-scan the same empty range every tick. Recorded only
                // AFTER removeAll succeeded (or wasn't needed) — a thrown
                // removeAll skips to the catch below, leaving the watermark
                // where it was so the next pass retries the same seqs.
                currentCoroutineContext().ensureActive()
                store.recordSearchRetirement(pending.cutoffMs)
                searchRetired = pending.seqs.size
            }
            currentCoroutineContext().ensureActive()
            store.recordMaintenanceRun(now)
            MatronDebug.breadcrumb(
                "JournalMaintenance: pass done; retention visited ${retentionVisited.size}, search retired $searchRetired",
            )
        } catch (e: CancellationException) {
            // stop(): no stamp, no watermark — the next pass resumes.
            throw e
        } catch (e: Throwable) {
            // No stamp on failure: the next tick retries immediately rather
            // than waiting out the hour.
            MatronDebug.breadcrumb("JournalMaintenance: pass failed: $e")
        }
    }

    companion object {
        /// Default time between passes (spec §3.4).
        const val DEFAULT_INTERVAL_MS: Long = 60L * 60 * 1000

        /// Long enough for the connect + first catch-up replay to have the
        /// disk to themselves; short enough that a session left open still
        /// gets swept.
        const val FIRST_RUN_DELAY_MS: Long = 10_000
    }
}
