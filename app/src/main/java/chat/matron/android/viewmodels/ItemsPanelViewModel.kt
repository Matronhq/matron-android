package chat.matron.android.viewmodels

import chat.matron.android.journal.ItemRankChange
import chat.matron.android.journal.ItemsProviding
import chat.matron.android.journal.ItemsRefreshOutcome
import chat.matron.android.journal.ItemsStoreReading
import chat.matron.android.journal.ItemsSync
import chat.matron.android.journal.ItemsSyncing
import chat.matron.android.journal.MatronJson
import chat.matron.android.journal.NewItem
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
                val awaiting = awaitingYou(items)
                _awaitingYou.value = awaiting
                _awaitingYouCount.value = awaiting.size
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

    private suspend fun runRefresh(surfaceFailure: Boolean) {
        _isRefreshing.value = true
        try {
            val outcome = sync.refresh(_itemsScope.value)
            if (surfaceFailure && outcome is ItemsRefreshOutcome.Failed) _error.value = outcome.message
        } finally {
            _isRefreshing.value = false
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

    companion object {
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
