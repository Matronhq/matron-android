package chat.matron.android.journal

import chat.matron.android.events.ItemMarkerEvent
import chat.matron.android.journal.db.ItemOutboxEntity
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.MatronDebug
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerItem
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/// What a [ItemsSync.refresh] pass actually did. The refresh swallows its own
/// failures — it drives a banner, not a `throws` — which is right for a
/// banner and wrong for anything that reads the store afterwards and draws a
/// conclusion from a miss, so the outcome is reported too.
sealed interface ItemsRefreshOutcome {
    /// The list was fetched (possibly truncated by the page cap) and the
    /// store is up to date as far as this pass got.
    data object Succeeded : ItemsRefreshOutcome

    /// The journal has no tracker routes (404). Not a transport fault: the
    /// server answered, and `isSupported` is now false.
    data object Unsupported : ItemsRefreshOutcome

    /// `stop()` landed mid-pass (sign-out / teardown), so the run was
    /// abandoned before writing.
    data object Stopped : ItemsRefreshOutcome

    /// The fetch (or a store write inside it) threw; [message] is what the
    /// user is shown.
    data class Failed(val message: String) : ItemsRefreshOutcome
}

/// The tracker sync surface the view models depend on, as an interface so
/// tests fake it. [ItemsSync] implements it.
interface ItemsSyncing {
    suspend fun refresh(scope: ItemsScope): ItemsRefreshOutcome
    suspend fun refreshItem(id: String)

    /// Writes an item the server has just handed back (a close, a reopen)
    /// straight into the local cache. [refreshItem] is the only other way in
    /// and it swallows every failure by design — so a mutation that succeeds
    /// on the journal and is then followed by a refetch that doesn't would
    /// leave the local copy stale, showing an item as open after it was
    /// closed. Landing the returned item first makes the local state honest
    /// whatever the refetch does.
    suspend fun applyItem(item: TrackerItem)
    suspend fun enqueueComment(itemID: String, localID: String, body: String, attachments: List<TrackerAttachment>)

    /// Returns whether the outbox insert itself succeeded — `false` when the
    /// sync is stopped or the local write throws. `true` means only that the
    /// row is durably queued; delivery is a separate, unawaited background
    /// drain.
    suspend fun enqueueCreate(localID: String, new: NewItem): Boolean

    /// `false` once a `GET /items` has answered 404 (a journal predating the
    /// tracker); back to `true` on the next successful fetch. Starts `true`
    /// rather than flashing an "unsupported" state before the first probe.
    val isSupported: StateFlow<Boolean>
}

/// Keeps the local tracker cache fresh (spec: Apps → ItemsSync). Three
/// triggers refetch: a marker event for an item (refetch that item), a
/// panel open / explicit refresh (since-watermark list), and a reconnect
/// (same). An item outbox holds comments and creates written offline and
/// drains whenever the connection is running. Ported from matron-apple's
/// `ItemsSync` actor.
///
/// The Swift original is an actor; here the bookkeeping (in-flight maps,
/// drain flags, the retry job) is confined by one monitor [lock] whose
/// critical sections never suspend — the same shape as `JournalSyncEngine`.
class ItemsSync(
    private val api: ItemsProviding,
    private val store: JournalStore,
    private val markers: () -> Flow<Pair<String, ItemMarkerEvent>>,
    private val connectionStates: () -> Flow<SyncConnectionState>,
    /// Base delay for the outbox retry backoff (`min(60s, retryBase × 2^attempts)`).
    /// Overridable so tests can bound the wait.
    private val retryBaseMs: Long = 2_000,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ItemsSyncing {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val lock = Any()

    // Guarded by `lock`.
    private var markerJob: Job? = null
    private var stateJob: Job? = null
    /// Scheduled after a retryable drain failure; cancelled by [stop] and by
    /// any fresh drain trigger, since a fresh trigger supersedes whatever
    /// backoff was pending.
    private var retryJob: Job? = null
    /// Drain re-entrancy: a loop rather than a plain guard so an enqueue that
    /// lands mid-drain is never lost — it sets [drainRequested] and the
    /// running drain loops once more before releasing [draining]. Only
    /// honored when the just-finished pass was clean: a re-request during a
    /// FAILED pass does not immediately retry (that would hammer a dead
    /// network); the scheduled backoff handles that instead.
    private var draining = false
    private var drainRequested = false
    /// Per-item refetch coalescing: a marker event and the drain's own inline
    /// `refreshItem` after a successful comment can race for the same item
    /// id. Rather than let two concurrent GETs land their `replaceComments`
    /// out of order, a refetch already in flight for an id just notes that
    /// another pass is wanted and then AWAITS that run (callers such as the
    /// detail view model take "refreshItem returned" to mean "the store now
    /// holds the server's thread"); the in-flight run repeats once more.
    private val inFlightRefetches = mutableMapOf<String, Job>()
    private val refetchAgain = mutableSetOf<String>()
    /// Per-SCOPE list-refresh coalescing: a reconnect and a panel open landing
    /// together must not each run their own full paginated GET over the same
    /// rows. Concurrent callers await the run already in flight for that
    /// scope. Deliberately no `refetchAgain`-style repeat: a joiner wants
    /// "the list, freshly fetched", not "a fetch that started after I asked".
    private val inFlightRefreshes = mutableMapOf<ItemsScope, Deferred<ItemsRefreshOutcome>>()
    /// Every drain pass runs as its own job here (callers join it), so
    /// [stop] can cancel and await a drain the same way it does a refresh —
    /// including the background one `enqueueCreate` kicks and never awaits.
    private val drainJobs = mutableSetOf<Job>()

    /// Set by [stop], cleared by [start]. Every write site in refresh /
    /// refetch / drain re-checks this immediately after its await, before
    /// touching the store — a suspended network call that resumes after a
    /// sign-out must not write into the wiped database.
    @Volatile
    private var stopped = false

    private val _isSupported = MutableStateFlow(true)
    override val isSupported: StateFlow<Boolean> = _isSupported.asStateFlow()

    fun start() {
        synchronized(lock) {
            stopped = false
            if (markerJob != null) return
            markerJob = scope.launch {
                markers().collect { (_, marker) -> refreshItem(marker.itemID) }
            }
            stateJob = scope.launch {
                connectionStates().collect { state ->
                    if (state is SyncConnectionState.Running) {
                        // No `isSupported = true` here: publishing "supported"
                        // before the probe would flash true→false for an old
                        // journal that 404s on GET /items. `refresh` publishes
                        // the real result once it knows it.
                        refresh(ItemsScope.All)
                        drainOutbox()
                    }
                }
            }
        }
        // No eager drain here: the connection state flow replays `Running`
        // once caught up, even on a cold start, and that already drains.
    }

    /// Suspends until nothing more will happen: cancels the marker/state/retry
    /// jobs AND every in-flight refresh, per-item refetch and drain pass, and
    /// joins them all, so a caller's sign-out wipe can never race a resuming
    /// network call's store write — and a `start()` for a new session can
    /// never run until the old tasks have actually exited. Every piece of
    /// work that can write to the store runs as a job on this sync's own
    /// scope precisely so it is reachable from here (Bugbot, #71).
    suspend fun stop() {
        val toJoin: List<Job>
        synchronized(lock) {
            stopped = true
            toJoin = listOfNotNull(markerJob, stateJob, retryJob) +
                inFlightRefreshes.values + inFlightRefetches.values + drainJobs
            markerJob = null; stateJob = null; retryJob = null
        }
        toJoin.forEach { it.cancel() }
        toJoin.forEach { it.join() }
        // No blanket clear of `inFlightRefreshes`: every refresh task
        // deregisters ITSELF (identity-checked), and a `start()` + `refresh()`
        // racing this method's suspension may legitimately have registered a
        // brand-new task for some scope that must survive.
    }

    override suspend fun refresh(scope: ItemsScope): ItemsRefreshOutcome {
        // Registration is one critical section: check for a run in flight,
        // else create (lazily — nothing runs under the lock) and register.
        val run: Deferred<ItemsRefreshOutcome> = synchronized(lock) {
            inFlightRefreshes[scope] ?: run {
                lateinit var self: Deferred<ItemsRefreshOutcome>
                self = this.scope.async(start = CoroutineStart.LAZY) {
                    try {
                        refreshOnce(scope)
                    } finally {
                        // Only clear the slot if it still holds THIS task; if
                        // it now holds a different (newer) task, leave it alone.
                        synchronized(lock) { if (inFlightRefreshes[scope] === self) inFlightRefreshes.remove(scope) }
                    }
                }
                inFlightRefreshes[scope] = self
                self
            }
        }
        run.start() // a no-op for an already-running joinee
        return awaitRefresh(run)
    }

    /// Joiners and the owner get the SAME outcome out of the one shared task,
    /// so two callers racing a single fetch can't disagree about whether it
    /// worked. A task `stop()` cancelled reads as [ItemsRefreshOutcome.Stopped]
    /// to a caller that is itself still running.
    private suspend fun awaitRefresh(run: Deferred<ItemsRefreshOutcome>): ItemsRefreshOutcome =
        try {
            run.await()
        } catch (cancel: CancellationException) {
            currentCoroutineContext().ensureActive()
            ItemsRefreshOutcome.Stopped
        }

    private suspend fun refreshOnce(scope: ItemsScope): ItemsRefreshOutcome {
        val watermark = runCatching { store.itemsWatermark(scope) }.getOrNull()
        var query = ItemsListQuery(
            convoID = (scope as? ItemsScope.Convo)?.id,
            sort = ItemsListQuery.Sort.UPDATED,
            since = watermark?.minusSeconds(1),
            limit = 500,
        )
        // Newest `updated_at` across every page actually fetched this run.
        // Persisted as the new watermark ONLY if the whole loop completes
        // without throwing AND without truncating — advancing it on a partial
        // run would let a later refresh believe it already has data it never
        // actually fetched, permanently skipping the gap.
        var newestSeen: Instant? = null
        val seenCursors = mutableSetOf<String>()
        var pageCount = 0
        var truncated = false
        try {
            do {
                val page = api.listItems(query)
                if (stopped || !currentCoroutineContext().isActive) return ItemsRefreshOutcome.Stopped
                store.upsertItems(page.items)
                for (i in page.items) if (newestSeen == null || i.updatedAt.isAfter(newestSeen)) newestSeen = i.updatedAt
                pageCount += 1
                val next = page.nextCursor
                query = when {
                    next != null && next in seenCursors -> {
                        MatronDebug.breadcrumb("ItemsSync.refresh($scope): nextCursor repeated ($next) — stopping pagination")
                        truncated = true
                        query.copy(cursor = null)
                    }
                    pageCount >= MAX_PAGES -> {
                        if (next != null) {
                            MatronDebug.breadcrumb("ItemsSync.refresh($scope): hit $MAX_PAGES-page cap — stopping pagination")
                            truncated = true
                        }
                        query.copy(cursor = null)
                    }
                    else -> {
                        next?.let { seenCursors.add(it) }
                        query.copy(cursor = next)
                    }
                }
            } while (query.cursor != null)
            val newest = newestSeen
            if (newest != null && !truncated) {
                runCatching { store.setItemsWatermark(newest, scope) }
                    .onFailure { MatronDebug.breadcrumb("ItemsSync: setItemsWatermark failed for $scope: $it") }
            }
            _isSupported.value = true
            // A successful refresh is also what proves the tracker routes
            // exist, which is what unblocks a paused drain: `drainOnce` gates
            // on `isSupported`, and nothing else re-kicks the drain after the
            // flag flips outside of `Running` or a fresh enqueue.
            if (runCatching { store.itemOutboxPending() }.getOrDefault(emptyList()).isNotEmpty()) drainOutbox()
            return ItemsRefreshOutcome.Succeeded
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (notFound: JournalApiError.NotFound) {
            _isSupported.value = false
            return ItemsRefreshOutcome.Unsupported
        } catch (error: Throwable) {
            MatronDebug.breadcrumb("ItemsSync: refresh failed: $error")
            return ItemsRefreshOutcome.Failed(error.message ?: error.toString())
        }
    }

    override suspend fun refreshItem(id: String) {
        val run: Job
        synchronized(lock) {
            inFlightRefetches[id]?.let { existing ->
                refetchAgain.add(id)
                run = existing
                return@synchronized
            } ?: run {
                lateinit var self: Job
                self = scope.launch(start = CoroutineStart.LAZY) {
                    var deregistered = false
                    try {
                        while (true) {
                            refreshItemOnce(id)
                            // The final "again?" check and the deregistration
                            // are one critical section, so a joiner can never
                            // flag `refetchAgain` against a task that has
                            // already finished and left its flag unconsumed.
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
                    } finally {
                        if (!deregistered) synchronized(lock) { if (inFlightRefetches[id] === self) inFlightRefetches.remove(id) }
                    }
                }
                inFlightRefetches[id] = self
                run = self
            }
        }
        run.start()
        run.join()
    }

    override suspend fun applyItem(item: TrackerItem) {
        if (stopped) return
        try {
            store.upsertItems(listOf(item))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            MatronDebug.breadcrumb("ItemsSync: applying returned item ${item.id} failed: $error")
        }
    }

    private suspend fun refreshItemOnce(id: String) {
        try {
            val detail = api.item(id)
            if (stopped) return
            store.upsertItems(listOf(detail.item))
            store.replaceComments(id, detail.comments)
            _isSupported.value = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            MatronDebug.breadcrumb("ItemsSync: item refetch $id failed: $error")
        }
    }

    @Serializable
    internal data class CommentPayload(val body: String, val attachments: List<TrackerAttachment> = emptyList())

    /// Only the fields the apps-side create flow currently needs.
    /// `labels`/`links`/`awaiting`/`position`/`supersedes` are deliberately
    /// not round-tripped through the outbox — `enqueueCreate` never receives
    /// them from callers yet. `ItemsPanelViewModel.pendingCreates` decodes
    /// this same shape.
    @Serializable
    internal data class CreatePayload(
        val kind: String,
        val title: String,
        val body: String,
        val convoID: String,
        val attachments: List<TrackerAttachment> = emptyList(),
    )

    override suspend fun enqueueComment(itemID: String, localID: String, body: String, attachments: List<TrackerAttachment>) {
        // An enqueue racing sign-out must not insert after the outbox wipe has
        // already run — `stop()` is called before the wipe, so this flag being
        // set means the outbox is either already cleared or about to be.
        if (stopped) return
        val payload = MatronJson.encodeToString(CommentPayload.serializer(), CommentPayload(body, attachments))
        runCatching {
            store.itemOutboxInsert(
                ItemOutboxEntity(
                    localID = localID, itemID = itemID, op = ItemOutboxEntity.OP_COMMENT, payloadJson = payload,
                    createdAt = System.currentTimeMillis(), attempts = 0, lastError = null,
                ),
            )
        }.onFailure { MatronDebug.breadcrumb("ItemsSync: enqueueComment insert failed for $localID: $it") }
        drainOutbox()
    }

    /// The drain itself is kicked in the background, NOT awaited: the write
    /// is already durable once the outbox row lands, and the drain's own
    /// retry/backoff loop owns delivery from here.
    override suspend fun enqueueCreate(localID: String, new: NewItem): Boolean {
        if (stopped) return false
        val payload = MatronJson.encodeToString(
            CreatePayload.serializer(),
            CreatePayload(new.kind.wire, new.title, new.body, new.convoID, new.attachments),
        )
        val inserted = runCatching {
            store.itemOutboxInsert(
                ItemOutboxEntity(
                    localID = localID, itemID = null, op = ItemOutboxEntity.OP_CREATE, payloadJson = payload,
                    createdAt = System.currentTimeMillis(), attempts = 0, lastError = null,
                ),
            )
        }.onFailure { MatronDebug.breadcrumb("ItemsSync: enqueueCreate insert failed for $localID: $it") }
        if (inserted.isFailure) return false
        startDrain()
        return true
    }

    /// Runs one drain pass to completion. The pass itself is a job on this
    /// sync's scope (see [startDrain]) so [stop] can cancel and await it; a
    /// caller cancelled while waiting leaves the pass running for `stop()`
    /// to own.
    suspend fun drainOutbox() = startDrain().join()

    private fun startDrain(): Job {
        lateinit var self: Job
        synchronized(lock) {
            self = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    drainPass()
                } finally {
                    synchronized(lock) { drainJobs.remove(self) }
                }
            }
            drainJobs.add(self)
        }
        self.start()
        return self
    }

    private suspend fun drainPass() {
        val supersededRetry: Job?
        val alreadyDraining: Boolean
        synchronized(lock) {
            // A fresh trigger (an enqueue, a reconnect, `start()`) supersedes
            // any pending backoff retry.
            supersededRetry = retryJob; retryJob = null
            alreadyDraining = draining
            if (draining) drainRequested = true else draining = true
        }
        supersededRetry?.cancel()
        if (alreadyDraining) return
        var released = false
        try {
            while (true) {
                synchronized(lock) { drainRequested = false }
                when (val outcome = drainOnce()) {
                    DrainOutcome.Clean -> {
                        // The re-request check and the release are one critical
                        // section, so an enqueue can't slip between them.
                        val again = synchronized(lock) {
                            if (drainRequested) true else { draining = false; released = true; false }
                        }
                        if (!again) return
                    }
                    is DrainOutcome.Retry -> {
                        // Stopped early on a retryable failure: schedule the
                        // backoff retry and stop, even if another caller kicked
                        // the drain mid-attempt — retrying instantly would just
                        // fail the same way again.
                        scheduleRetry(outcome.attempts)
                        return
                    }
                    DrainOutcome.Paused -> {
                        // Auth lost, or the routes aren't proven to exist yet:
                        // stop entirely. No backoff timer — a successful
                        // `refresh` (proving support) or a fresh sign-in is
                        // what unblocks this, not a clock.
                        return
                    }
                }
            }
        } finally {
            if (!released) synchronized(lock) { draining = false }
        }
    }

    private fun scheduleRetry(afterAttempts: Int) {
        val delayMs = min(60_000.0, retryBaseMs * 2.0.pow(afterAttempts.toDouble())).toLong()
        synchronized(lock) {
            if (stopped) return
            retryJob = scope.launch {
                delay(delayMs)
                if (!isActive || stopped) return@launch
                // Clear the slot BEFORE draining: `drainOutbox()` cancels
                // whatever `retryJob` holds, and this IS that job — draining
                // from inside it without this would cancel its own network
                // call, classify that as retryable, and reschedule forever.
                val self = coroutineContext[Job]
                synchronized(lock) { if (retryJob === self) retryJob = null }
                drainOutbox()
            }
        }
    }

    /// How a failed outbox request should be handled: auth/support loss
    /// needs a third behaviour beyond retry/poison — leave the row alone and
    /// stop, rather than deleting it or bumping its attempt count.
    private enum class FailureDisposition { RETRYABLE, POISON, PAUSE }

    private fun disposition(error: Throwable): FailureDisposition = when (error) {
        // A lost or rejected session, not a permanently-bad request.
        JournalApiError.Unauthenticated, JournalApiError.BadCredentials, JournalApiError.Forbidden -> FailureDisposition.PAUSE
        JournalApiError.Conflict -> FailureDisposition.POISON
        // The journal never deletes items server-side — a 404 on a write is
        // far likelier "this journal doesn't have the tracker routes yet" than
        // "the item was deleted out from under us". `refresh` catching its own
        // 404 is what flips `isSupported` false and pauses the queue.
        JournalApiError.NotFound -> FailureDisposition.RETRYABLE
        is JournalApiError.Http ->
            if (error.status == 408 || error.status == 429) FailureDisposition.RETRYABLE
            else if (error.status in 400..499) FailureDisposition.POISON
            else FailureDisposition.RETRYABLE
        // Anything unrecognised: the safe default is to neither drop nor
        // freeze a user's data.
        else -> FailureDisposition.RETRYABLE
    }

    private sealed interface DrainOutcome {
        /// Every pending row was either applied or dropped as poison.
        data object Clean : DrainOutcome

        /// Stopped early on a retryable failure; carries the failed row's
        /// post-mark attempt count for the backoff calculation.
        data class Retry(val attempts: Int) : DrainOutcome

        /// Stopped early because the routes aren't proven to exist yet or a
        /// request was rejected on auth grounds.
        data object Paused : DrainOutcome
    }

    private suspend fun drainOnce(): DrainOutcome {
        // Rows wait until a `refresh` proves the tracker routes exist on this
        // journal — draining against an unproven journal risks exactly the
        // poison-row misclassification the 404 rule above guards against.
        if (!_isSupported.value) return DrainOutcome.Paused
        if (stopped) return DrainOutcome.Clean
        val rows = runCatching { store.itemOutboxPending() }.getOrNull() ?: return DrainOutcome.Clean
        for (row in rows) {
            if (stopped) return DrainOutcome.Clean
            try {
                when (row.op) {
                    ItemOutboxEntity.OP_COMMENT -> {
                        val itemID = row.itemID
                        val payload = runCatching { MatronJson.decodeFromString(CommentPayload.serializer(), row.payloadJson) }.getOrNull()
                        if (itemID == null || payload == null) { store.itemOutboxDelete(row.localID); continue }
                        val result = api.commentItem(itemID, payload.body, payload.attachments, idempotencyKey = row.localID)
                        if (stopped) return DrainOutcome.Clean
                        // Keep the posted comment locally BEFORE the coalesced
                        // `refreshItem` below: that GET can itself fail (an
                        // offline blip), and the row is already gone from the
                        // outbox — without this the reply would be invisible
                        // until the detail screen is reopened.
                        store.commitOutboxResult(result.item, result.comment, deletingLocalID = row.localID)
                        refreshItem(itemID)
                    }
                    ItemOutboxEntity.OP_CREATE -> {
                        val payload = runCatching { MatronJson.decodeFromString(CreatePayload.serializer(), row.payloadJson) }.getOrNull()
                        val kind = ItemKind.fromWire(payload?.kind)
                        if (payload == null || kind == null) { store.itemOutboxDelete(row.localID); continue }
                        val item = api.createItem(
                            NewItem(kind = kind, title = payload.title, body = payload.body, attachments = payload.attachments, convoID = payload.convoID),
                            idempotencyKey = row.localID,
                        )
                        if (stopped) return DrainOutcome.Clean
                        store.commitOutboxResult(item, deletingLocalID = row.localID)
                    }
                    else -> store.itemOutboxDelete(row.localID)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // A throw can land after `stop()` flipped `stopped` mid-await:
                // a stale failure must neither mark an attempt nor delete a
                // row, and `Retry` would make the outer loop schedule a fresh
                // retry after `stop()` already tore the old one down.
                if (stopped) return DrainOutcome.Clean
                when (disposition(error)) {
                    FailureDisposition.RETRYABLE -> {
                        runCatching { store.itemOutboxMarkAttempt(row.localID, error.message) }
                            .onFailure { MatronDebug.breadcrumb("ItemsSync: itemOutboxMarkAttempt failed for ${row.localID}: $it") }
                        // Stop at the first retryable failure: the rest will
                        // fail the same way (offline) and order matters for
                        // comments on one item.
                        return DrainOutcome.Retry(row.attempts + 1)
                    }
                    FailureDisposition.POISON -> {
                        MatronDebug.breadcrumb("ItemsSync: outbox row ${row.localID} rejected non-retryably ($error) — dropping")
                        runCatching { store.itemOutboxDelete(row.localID) }
                            .onFailure { MatronDebug.breadcrumb("ItemsSync: itemOutboxDelete failed for poisoned row ${row.localID}: $it") }
                    }
                    FailureDisposition.PAUSE -> {
                        MatronDebug.breadcrumb("ItemsSync: outbox row ${row.localID} paused (auth/session rejected): $error — left queued")
                        return DrainOutcome.Paused
                    }
                }
            }
        }
        return DrainOutcome.Clean
    }

    private companion object {
        /// Pagination safety valve: a runaway or looping server pagination
        /// must not spin this forever.
        const val MAX_PAGES = 50
    }
}
