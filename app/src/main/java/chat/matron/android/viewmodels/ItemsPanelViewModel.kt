package chat.matron.android.viewmodels

import chat.matron.android.journal.ItemRankChange
import chat.matron.android.journal.ItemsProviding
import chat.matron.android.journal.ItemsRefreshOutcome
import chat.matron.android.journal.ItemsStoreReading
import chat.matron.android.journal.ItemsSync
import chat.matron.android.journal.ItemsSyncing
import chat.matron.android.journal.MatronJson
import chat.matron.android.journal.NewItem
import chat.matron.android.journal.db.ItemOutboxEntity
import chat.matron.android.models.ForYouTab
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemState
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.MatronDebug
import chat.matron.android.models.TrackerItem
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/// Backs the per-chat / cross-chat items panel (spec: Apps → Panel content).
/// Reads flow from the local store ([ItemsStoreReading]'s flows); writes go
/// through [ItemsSyncing], which owns the outbox and refetch coalescing —
/// this view model never coalesces refetches itself. Ported from
/// matron-apple's `ItemsPanelViewModel`.
///
/// [scope] replaces the Swift original's `@MainActor Task`s; the UI stage
/// supplies a lifecycle-scoped one (the chat VM cache's session scope).
class ItemsPanelViewModel(
    /// The home conversation, or `null` for an app-wide instance: `null`
    /// starts [scope] at [ItemsScope.All], disables [create] (no conversation
    /// to file into) and leaves [needsYouCount] at zero.
    val convoID: String?,
    private val store: ItemsStoreReading,
    private val api: ItemsProviding,
    private val sync: ItemsSyncing,
    private val scope: CoroutineScope,
) {
    data class Sections(
        val needsYou: List<TrackerItem> = emptyList(),
        val tasks: List<TrackerItem> = emptyList(),
        val decisions: List<TrackerItem> = emptyList(),
        val done: List<TrackerItem> = emptyList(),
    ) {
        val isEmpty: Boolean get() = needsYou.isEmpty() && tasks.isEmpty() && decisions.isEmpty() && done.isEmpty()
    }

    /// A local "create" outbox row that hasn't landed on the server yet —
    /// without this, an offline/in-flight create is invisible: the create
    /// sheet dismisses and the row lives only in `item_outbox` until the
    /// drain succeeds, with no on-screen trace in the meantime.
    data class PendingItem(
        val id: String,
        val kind: ItemKind,
        val title: String,
        val attempts: Int,
        val lastError: String?,
    )

    private val _itemsScope = MutableStateFlow(convoID?.let { ItemsScope.Convo(it) } ?: ItemsScope.All)
    /// Which items the list shows. Setting it resubscribes and refreshes.
    val itemsScope: StateFlow<ItemsScope> = _itemsScope.asStateFlow()

    private val _sections = MutableStateFlow(Sections())
    val sections: StateFlow<Sections> = _sections.asStateFlow()

    /// Items in THIS conversation awaiting the user — the toolbar badge.
    /// Scoped to [convoID] regardless of the panel's current scope, so
    /// switching the list to "All" doesn't inflate the chat's badge.
    private val _needsYouCount = MutableStateFlow(0)
    val needsYouCount: StateFlow<Int> = _needsYouCount.asStateFlow()

    /// Every open item awaiting the user across ALL conversations, newest
    /// `updatedAt` first — independent of [itemsScope], fed by its own
    /// [ItemsStoreReading.needsUserFlow] subscription. Backs the Decisions
    /// list and the tab badge (spec §1, §2).
    private val _awaitingYou = MutableStateFlow<List<TrackerItem>>(emptyList())
    val awaitingYou: StateFlow<List<TrackerItem>> = _awaitingYou.asStateFlow()
    /// `awaitingYou.size`, kept in lockstep (not a `stateIn` derivation: that
    /// would pin a collector to [scope] for the VM's lifetime).
    private val _awaitingYouCount = MutableStateFlow(0)
    val awaitingYouCount: StateFlow<Int> = _awaitingYouCount.asStateFlow()

    /// Every locally known closed question, decision and notice, in
    /// [decided] order — backs For you's Done tab, so an item stays findable
    /// once it is closed instead of vanishing. Purely local: the store
    /// already mirrors closed items, so there is no server page to reach
    /// for. Only the app-wide instance (`convoID == null`) fills it.
    private val _decided = MutableStateFlow<List<TrackerItem>>(emptyList())
    val decided: StateFlow<List<TrackerItem>> = _decided.asStateFlow()
    /// How many of [decided], from the front, the Done tab shows. Grown by
    /// [showMoreDecided] and otherwise re-derived from
    /// [desiredDecidedVisibleCount] on every store emission, so an unrelated
    /// item changing elsewhere never resets how far the user has expanded.
    private val _decidedVisibleCount = MutableStateFlow(0)
    val decidedVisibleCount: StateFlow<Int> = _decidedVisibleCount.asStateFlow()
    /// Whether there is more of [decided] beyond the current window.
    val hasMoreDecided: Boolean get() = _decidedVisibleCount.value < _decided.value.size
    /// The user's intent for how many [decided] rows to show;
    /// [decidedVisibleCount] is this capped at what exists.
    private var desiredDecidedVisibleCount = 0
    /// Which For you tab is showing. The host maps [decided] into rows only
    /// while it is [ForYouTab.CLOSED], so the open list never pays for it.
    private val _forYouTab = MutableStateFlow(ForYouTab.OPEN)
    val forYouTab: StateFlow<ForYouTab> = _forYouTab.asStateFlow()

    /// Notices the user has marked seen whose closed copy has not come back
    /// yet. Kept out of [awaitingYou] so a swiped row stays gone while the
    /// tap waits in the outbox; each id drops out of this set once the store
    /// no longer lists the item as needing the user (the journal's close
    /// landed).
    private var seenPending: Set<String> = emptySet()
    /// Notices whose Seen tap sits in the comment outbox right now, whichever
    /// screen made it (this list or the detail screen). Kept out of
    /// [awaitingYou] like [seenPending], and a second tap is not queued.
    private var seenQueued: Set<String> = emptySet()
    /// Consent requests answered from their For you row (Approve / Decline)
    /// whose close has not come back yet, each with its outbox row's local
    /// id: kept out of [awaitingYou] like [seenPending], and the local id is
    /// how a refusal (most often "already answered") is told back.
    private var answerPending: Map<String, String> = emptyMap()
    /// Consent requests whose answer tap sits in the comment outbox right
    /// now, whichever screen made it.
    private var answerQueued: Set<String> = emptySet()
    /// The latest [ItemsStoreReading.needsUserFlow] value, so a Seen tap can
    /// re-derive [awaitingYou] without waiting for the store.
    private var lastNeedsUserItems: List<TrackerItem> = emptyList()

    private val _isSupported = MutableStateFlow(true)
    val isSupported: StateFlow<Boolean> = _isSupported.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /// Creates still sitting in the local outbox, not yet confirmed by the
    /// server — filtered to [convoID] when the scope is a conversation,
    /// unfiltered in the All scope.
    private val _pendingCreates = MutableStateFlow<List<PendingItem>>(emptyList())
    val pendingCreates: StateFlow<List<PendingItem>> = _pendingCreates.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var itemsJob: Job? = null
    private var awaitingJob: Job? = null
    private var seenQueuedJob: Job? = null
    private var pendingCreatesJob: Job? = null
    private var supportedJob: Job? = null
    private var refreshJob: Job? = null

    /// Monotonic token identifying the current observation run; bumped by
    /// every [start]. This VM is shared per-room across surfaces (the chat
    /// screen's badge and the tasks page), and Compose can run a successor's
    /// `start()` before a predecessor's disposal — the same remount hazard
    /// `SubChatStripViewModel` guards against. Hosts pass the generation to
    /// [stop] so a stale surface's teardown can never cancel a successor's
    /// fresh stream.
    var observationGeneration: Int = 0
        private set

    fun start() {
        observationGeneration += 1
        stop()
        resubscribe()
        awaitingJob = scope.launch {
            store.needsUserFlow().collect { items ->
                lastNeedsUserItems = items
                publishAwaitingYou()
            }
        }
        seenQueuedJob = scope.launch {
            store.itemOutboxCommentsFlow().collect { rows ->
                seenQueued = queuedSeenTaps(rows)
                answerQueued = queuedConsentAnswers(rows)
                noteRefusedAnswers(rows)
                publishAwaitingYou()
            }
        }
        supportedJob = scope.launch {
            sync.isSupported.collect { _isSupported.value = it }
        }
    }

    /// Stops the observation only if [generation] still identifies the
    /// current run — a stale host's teardown becomes a no-op.
    fun stop(generation: Int) {
        if (generation == observationGeneration) stop()
    }

    fun stop() {
        awaitingJob?.cancel(); awaitingJob = null
        seenQueuedJob?.cancel(); seenQueuedJob = null
        itemsJob?.cancel(); itemsJob = null
        pendingCreatesJob?.cancel(); pendingCreatesJob = null
        supportedJob?.cancel(); supportedJob = null
        refreshJob?.cancel(); refreshJob = null
    }

    fun setScope(scope: ItemsScope) {
        if (_itemsScope.value == scope) return
        _itemsScope.value = scope
        // Only a running observation follows the scope; before `start()` the
        // first subscription picks the current scope up itself.
        if (itemsJob != null) resubscribe()
    }

    private fun resubscribe() {
        val current = _itemsScope.value
        val home = convoID
        itemsJob?.cancel()
        itemsJob = scope.launch {
            store.itemsFlow(current).collect { items ->
                val s = sections(items)
                _sections.value = s
                _needsYouCount.value = if (home == null) 0 else s.needsYou.count { it.originConvoID == home }
                // Only the app-wide For you instance ever shows `decided`; a
                // conversation's tasks page would redo this work on every
                // emission of its own flow for a list nothing reads.
                if (home == null && current is ItemsScope.All) updateDecided(decided(items))
            }
        }
        pendingCreatesJob?.cancel()
        pendingCreatesJob = scope.launch {
            store.itemOutboxCreatesFlow().collect { rows ->
                _pendingCreates.value = rows.mapNotNull { row ->
                    val payload = runCatching { MatronJson.decodeFromString(ItemsSync.CreatePayload.serializer(), row.payloadJson) }.getOrNull()
                        ?: return@mapNotNull null
                    val kind = ItemKind.fromWire(payload.kind) ?: return@mapNotNull null
                    if (current is ItemsScope.Convo && payload.convoID != current.id) return@mapNotNull null
                    PendingItem(id = row.localID, kind = kind, title = payload.title, attempts = row.attempts, lastError = row.lastError)
                }
            }
        }
        // The opening refresh stays quiet: an offline open would otherwise
        // greet every tasks page with an error row for a cache that is
        // rendering fine. A pull the user asked for is different (below).
        refreshJob?.cancel()
        refreshJob = scope.launch { runRefresh(surfaceFailure = false) }
    }

    /// Pull to refresh. A failed fetch surfaces through [error] (spec §7:
    /// "refresh failure surfaces via the view model's existing error") —
    /// otherwise a stale or empty list and badge stay on screen with no
    /// explanation (Bugbot, #75). `Unsupported` is already carried by
    /// [isSupported].
    suspend fun refresh() = runRefresh(surfaceFailure = true)

    /// Identifies the refresh pass that currently owns [_isRefreshing]. Every
    /// pass through [runRefresh] claims it; only the claimant clears the flag
    /// on the way out. Without that check the spinner races its own successor:
    /// a scope switch (This chat / All) or a remount cancels [refreshJob] and
    /// starts another straight away, and the cancelled pass — whose fetch is
    /// a network call, so its cancellation only lands when that call returns
    /// — would run its `finally` after the fresh pass had already raised the
    /// flag, hiding the spinner mid-refresh.
    private var refreshToken: Long = 0

    private suspend fun runRefresh(surfaceFailure: Boolean) {
        val token = ++refreshToken
        _isRefreshing.value = true
        try {
            val outcome = sync.refresh(_itemsScope.value)
            // A user-driven pull owns the error row: a failure fills it, a
            // later success (or an unsupported journal, carried by
            // isSupported) clears it — otherwise a retry that just updated
            // the list would leave the stale row up (Bugbot, #75).
            if (surfaceFailure) _error.value = (outcome as? ItemsRefreshOutcome.Failed)?.message
        } finally {
            if (refreshToken == token) _isRefreshing.value = false
        }
    }

    /// Reorder inside the Tasks section. Optimistic: the local list is
    /// reordered first, the journal is told second, and a failure restores
    /// the previous order and surfaces the error. `after`/`before` are the
    /// moved item's new neighbours (computed post-removal); a move to either
    /// end sends a `position` instead.
    suspend fun move(itemID: String, toIndex: Int) {
        val before = _sections.value.tasks
        val from = before.indexOfFirst { it.id == itemID }
        if (from < 0) return
        val reordered = before.toMutableList()
        val moved = reordered.removeAt(from)
        val target = toIndex.coerceIn(0, reordered.size)
        // Stage a real, ordered `rank` on the moved item — not just a
        // reordered list — so a store emission that lands while `rankItem` is
        // still in flight (an unrelated row changing status, say) recomputes
        // sections from ranks that already agree with the optimistic order,
        // instead of snapping back to the pre-move rank.
        val optimisticRank = when {
            reordered.isEmpty() -> moved.rank
            target == 0 -> reordered[0].rank - 1024
            target == reordered.size -> reordered[reordered.size - 1].rank + 1024
            else -> (reordered[target - 1].rank + reordered[target].rank) / 2
        }
        reordered.add(target, moved.copy(rank = optimisticRank))
        if (reordered.map { it.id } == before.map { it.id }) return
        val change = when (target) {
            0 -> ItemRankChange(position = "top")
            reordered.size - 1 -> ItemRankChange(position = "bottom")
            else -> ItemRankChange(after = reordered[target - 1].id, before = reordered[target + 1].id)
        }
        _sections.value = _sections.value.copy(tasks = reordered)
        try {
            api.rankItem(itemID, change)
            sync.refreshItem(itemID)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _sections.value = _sections.value.copy(tasks = before)
            _error.value = error.message ?: error.toString()
        }
    }

    suspend fun create(kind: ItemKind, title: String, body: String) {
        val home = convoID
        if (home == null) { _error.value = "Open a chat's tracker to file a new item."; return }
        val t = title.trim()
        if (t.isEmpty() || t.length > 200) { _error.value = "Give the item a title (up to 200 characters)."; return }
        val queued = sync.enqueueCreate(UUID.randomUUID().toString(), NewItem(kind = kind, title = t, body = body, convoID = home))
        if (!queued) _error.value = "Couldn't file the item — try again."
    }

    fun dismissError() {
        _error.value = null
    }

    fun selectForYouTab(tab: ForYouTab) {
        _forYouTab.value = tab
    }

    /// Takes a fresh [decided] list from the store. The desired window only
    /// ever grows: it is raised to [defaultDecidedWindow] on every emission
    /// (not just seeded once), so an item closed after this view model
    /// started with none still shows, while an unrelated change never
    /// shrinks a window the user has expanded with [showMoreDecided].
    private fun updateDecided(decided: List<TrackerItem>) {
        _decided.value = decided
        desiredDecidedVisibleCount = maxOf(desiredDecidedVisibleCount, defaultDecidedWindow(decided))
        _decidedVisibleCount.value = minOf(desiredDecidedVisibleCount, decided.size)
    }

    /// The Done tab's "Show more": grows the visible window from local data.
    fun showMoreDecided() {
        desiredDecidedVisibleCount += DECIDED_PAGE_SIZE
        _decidedVisibleCount.value = minOf(desiredDecidedVisibleCount, _decided.value.size)
    }

    private fun publishAwaitingYou() {
        val stillWaiting = lastNeedsUserItems.filter { it.needsUser }.map { it.id }.toSet()
        seenPending = seenPending intersect stillWaiting
        answerPending = answerPending.filterKeys { it in stillWaiting }
        val awaiting = awaitingYou(lastNeedsUserItems).filter {
            it.id !in seenPending && it.id !in seenQueued && it.id !in answerPending && it.id !in answerQueued
        }
        _awaitingYou.value = awaiting
        _awaitingYouCount.value = awaiting.size
    }

    /// For you's one-tap "Seen" on a notice row (the button, or a swipe):
    /// the same tap the detail screen's Seen button makes — a comment whose
    /// body and `action` are "Seen", with no `reply_to` — queued through the
    /// outbox so a tap made offline lands later. The journal closes the
    /// notice as done in that same write. The row leaves the list at once
    /// and stays out while the tap is in flight. Returns whether a tap was
    /// queued: `false` for anything but an open notice, one already marked
    /// (here or on its detail screen), or when the outbox write fails — the
    /// row then comes back with an error.
    suspend fun markSeen(item: TrackerItem): Boolean {
        if (!item.canMarkSeen || item.id in seenPending || item.id in seenQueued) return false
        seenPending = seenPending + item.id
        publishAwaitingYou()
        // The flow may not have caught up with a tap the detail screen just queued.
        val queued = runCatching { store.itemOutboxRows(item.id) }.getOrDefault(emptyList())
        if (ItemDetailViewModel.pendingItemTap(queued) == TrackerItem.SEEN_ACTION) return false
        val inserted = sync.enqueueComment(
            item.id, UUID.randomUUID().toString(), TrackerItem.SEEN_ACTION, emptyList(),
            action = TrackerItem.SEEN_ACTION, replyTo = null,
        )
        if (!inserted) {
            seenPending = seenPending - item.id
            publishAwaitingYou()
            _error.value = "Couldn't mark it seen — try again."
        }
        return inserted
    }

    /// For you's one-tap answer to a consent request (a chat, join, spawn or
    /// file transfer waiting on the user): the same item-action tap the
    /// detail screen makes, queued through the outbox, which the journal
    /// answers exactly as the request card and closes the item. The row
    /// leaves the list at once. Returns whether the tap was queued: `false`
    /// for a label the row does not offer, a request already being answered,
    /// or a failed outbox write (the row then comes back with an error).
    suspend fun answerConsent(item: TrackerItem, label: String): Boolean {
        if (label !in item.rowConsentAnswers || item.id in answerPending || item.id in answerQueued) return false
        val localID = UUID.randomUUID().toString()
        answerPending = answerPending + (item.id to localID)
        publishAwaitingYou()
        // The flow may not have caught up with an answer the detail screen
        // just queued: one answer per request, never a second (or opposite) one.
        val queued = runCatching { store.itemOutboxRows(item.id) }.getOrDefault(emptyList())
        if (ItemDetailViewModel.pendingItemTap(queued) in TrackerItem.ROW_ANSWERS) {
            answerPending = answerPending - item.id
            answerQueued = answerQueued + item.id
            publishAwaitingYou()
            return false
        }
        val inserted = sync.enqueueComment(item.id, localID, label, emptyList(), action = label, replyTo = null)
        if (!inserted) {
            answerPending = answerPending - item.id
            publishAwaitingYou()
            _error.value = "Couldn't send your answer — try again."
        }
        return inserted
    }

    /// A row answer the journal refused: the row comes back (unless the
    /// refetch that follows a refusal drops it), and the refusal's words —
    /// the journal's own "Already answered: this request was approved." —
    /// are shown once.
    private fun noteRefusedAnswers(rows: List<ItemOutboxEntity>) {
        if (answerPending.isEmpty()) return
        val refused = rows.filter { it.isRejected && it.itemID != null && answerPending[it.itemID] == it.localID }
        if (refused.isEmpty()) return
        answerPending = answerPending - refused.mapNotNull { it.itemID }.toSet()
        _error.value = refused.last().rejectedReason
        scope.launch { runRefresh(surfaceFailure = false) }
    }

    companion object {
        /// The items with a queued consent answer among [rows]: an item-level
        /// tap (no `reply_to`) on one of the row answers, not refused.
        fun queuedConsentAnswers(rows: List<ItemOutboxEntity>): Set<String> =
            rows.groupBy { it.itemID }
                .filter { (id, itemRows) -> id != null && ItemDetailViewModel.pendingItemTap(itemRows) in TrackerItem.ROW_ANSWERS }
                .keys.filterNotNull().toSet()

        /// The items with a queued item-level Seen tap among [rows] — a
        /// comment row whose `action` is Seen and which replies to nothing.
        fun queuedSeenTaps(rows: List<ItemOutboxEntity>): Set<String> =
            rows.groupBy { it.itemID }
                .filter { (id, itemRows) -> id != null && ItemDetailViewModel.pendingItemTap(itemRows) == TrackerItem.SEEN_ACTION }
                .keys.filterNotNull().toSet()

        /// How many more rows one "Show more" reveals, and the default window.
        const val DECIDED_PAGE_SIZE = 50

        /// Whether a closed item belongs on For you's Done tab: any closed
        /// question or decision, WHATEVER its resolution, and never a task.
        /// The journal does not pair kinds with resolutions, so a question
        /// closed as done or cancelled (an agent abandoning it rather than
        /// answering it) is still a closed question that must stay findable.
        /// A closed notice counts too: nothing about it was decided, but its
        /// Seen tap is the user saying they have read it, and they sometimes
        /// want to find it again.
        fun isDecided(item: TrackerItem): Boolean =
            item.state == ItemState.CLOSED &&
                (item.kind == ItemKind.QUESTION || item.kind == ItemKind.DECISION || item.kind == ItemKind.NOTICE)

        /// The Done tab's rows: [isDecided] items, newest
        /// [TrackerItem.closedSortDate] first, `num` descending on a tie.
        fun decided(items: List<TrackerItem>): List<TrackerItem> =
            items.filter(::isDecided)
                .sortedWith(compareByDescending<TrackerItem> { it.closedSortDate }.thenByDescending { it.num })

        /// The Done tab's default visible row count: the latest [latest]
        /// closed items. A window by age would grow with the tracker, and
        /// every row in it is rebuilt on each item change.
        fun defaultDecidedWindow(decided: List<TrackerItem>, latest: Int = DECIDED_PAGE_SIZE): Int =
            minOf(latest, decided.size)

        /// The Decisions rule (spec §1): `needsUser` only, every conversation,
        /// newest `updatedAt` first.
        fun awaitingYou(items: List<TrackerItem>): List<TrackerItem> =
            items.filter { it.needsUser }.sortedByDescending { it.updatedAt }

        /// Sections rule (spec *Panel content*): `needsYou` = `needsUser` (any
        /// kind) sorted `updatedAt` desc — an item can appear here AND in
        /// `tasks`. `tasks` = kind task, open, sorted rank/num. `decisions` =
        /// kind decision, open, `createdAt` desc. `done` = closed, `closedAt`
        /// desc, capped 200.
        fun sections(items: List<TrackerItem>): Sections = Sections(
            needsYou = items.filter { it.needsUser }.sortedByDescending { it.updatedAt },
            tasks = items.filter { it.kind == ItemKind.TASK && it.state == ItemState.OPEN }
                .sortedWith(compareBy<TrackerItem> { it.rank }.thenBy { it.num }),
            decisions = items.filter { it.kind == ItemKind.DECISION && it.state == ItemState.OPEN }.sortedByDescending { it.createdAt },
            done = items.filter { it.state == ItemState.CLOSED }
                .sortedByDescending { it.closedAt ?: Instant.MIN }
                .take(200),
        )
    }
}
