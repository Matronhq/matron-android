package chat.matron.android.viewmodels

import chat.matron.android.journal.ItemCommentResult
import chat.matron.android.journal.ItemDetail
import chat.matron.android.journal.ItemPatch
import chat.matron.android.journal.ItemRankChange
import chat.matron.android.journal.ItemsListQuery
import chat.matron.android.journal.ItemsPage
import chat.matron.android.journal.ItemsProviding
import chat.matron.android.journal.ItemsRefreshOutcome
import chat.matron.android.journal.ItemsStoreReading
import chat.matron.android.journal.ItemsSyncing
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.NewItem
import chat.matron.android.journal.db.ItemOutboxEntity
import chat.matron.android.models.ForYouTab
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Fake store whose flows a test drives by hand (the Apple suite's
/// `FakeItemsStore` continuations → `MutableSharedFlow`s).
internal class FakeItemsStore : ItemsStoreReading {
    val items = MutableSharedFlow<List<TrackerItem>>(replay = 1)
    val creates = MutableSharedFlow<List<ItemOutboxEntity>>(replay = 1)
    val item = MutableSharedFlow<TrackerItem?>(replay = 1)
    val comments = MutableSharedFlow<List<TrackerComment>>(replay = 1)
    val outbox = MutableSharedFlow<List<ItemOutboxEntity>>(replay = 1)
    /// The scope-independent flow `awaitingYou` reads — separate from
    /// [items] so a test can drive the two independently.
    val awaiting = MutableSharedFlow<List<TrackerItem>>(replay = 1)
    var awaitingSubscriptions = 0
    var storedComments: List<TrackerComment> = emptyList()
    var itemsSubscriptions = 0
    var commentsSubscriptions = 0

    override fun itemsFlow(scope: ItemsScope): Flow<List<TrackerItem>> {
        itemsSubscriptions += 1
        // A fresh subscription must not replay a value a previous scope got.
        items.resetReplayCache()
        return items
    }
    override fun itemFlow(id: String): Flow<TrackerItem?> = item
    /// The owner conversation's session state, as the item detail follows it.
    val ownerSession = MutableSharedFlow<String?>(replay = 1)
    override fun itemOwnerSessionStateFlow(convoID: String): Flow<String?> = ownerSession
    override fun commentsFlow(itemID: String): Flow<List<TrackerComment>> {
        commentsSubscriptions += 1
        return comments
    }
    override suspend fun comments(itemID: String): List<TrackerComment> = storedComments
    override fun itemOutboxFlow(itemID: String): Flow<List<ItemOutboxEntity>> = outbox
    var storedOutbox: List<ItemOutboxEntity> = emptyList()
    override suspend fun itemOutboxRows(itemID: String): List<ItemOutboxEntity> = storedOutbox
    override fun itemOutboxCreatesFlow(): Flow<List<ItemOutboxEntity>> {
        creates.resetReplayCache()
        return creates
    }
    override fun needsUserFlow(): Flow<List<TrackerItem>> {
        awaitingSubscriptions += 1
        return awaiting
    }
    /// Every queued comment row, all items (For you's queued Seen taps).
    val commentRows = MutableSharedFlow<List<ItemOutboxEntity>>(replay = 1)
    override fun itemOutboxCommentsFlow(): Flow<List<ItemOutboxEntity>> = commentRows
}

internal open class FakeItemsSync : ItemsSyncing {
    val refreshed = mutableListOf<ItemsScope>()
    val created = mutableListOf<NewItem>()
    val refetched = mutableListOf<String>()
    val applied = mutableListOf<TrackerItem>()
    val comments = mutableListOf<Triple<String, String, List<TrackerAttachment>>>()
    var createSucceeds = true
    var commentSucceeds = true
    var refreshOutcome: ItemsRefreshOutcome = ItemsRefreshOutcome.Succeeded
    override val isSupported = MutableStateFlow(true)
    override suspend fun refresh(scope: ItemsScope): ItemsRefreshOutcome { refreshed += scope; return refreshOutcome }
    override suspend fun refreshItem(id: String) { refetched += id }
    override suspend fun applyItem(item: TrackerItem) { applied += item }
    /// (item id, label, asking-comment id) of every queued button tap.
    val taps = mutableListOf<Triple<String, String, String?>>()
    /// The local id of every queued comment, in order.
    val localIDs = mutableListOf<String>()
    override suspend fun enqueueComment(
        itemID: String, localID: String, body: String, attachments: List<TrackerAttachment>, action: String?, replyTo: String?,
    ): Boolean {
        comments += Triple(itemID, body, attachments)
        if (action != null) taps += Triple(itemID, action, replyTo)
        localIDs += localID
        return commentSucceeds
    }
    override suspend fun enqueueCreate(localID: String, new: NewItem): Boolean { created += new; return createSucceeds }
    /// Local ids of every Retry / Discard on a queued reply, in order.
    val retried = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    override suspend fun retryComment(localID: String) { retried += localID }
    override suspend fun discardComment(localID: String) { discarded += localID }
}

internal open class FakeItemsApi : ItemsProviding {
    val rankCalls = mutableListOf<Pair<String, ItemRankChange>>()
    var failRank = false
    override suspend fun rankItem(id: String, change: ItemRankChange): TrackerItem {
        rankCalls += id to change
        if (failRank) throw JournalApiError.Transport("x")
        return TrackerItem(id = id, num = 0, kind = ItemKind.TASK, title = "", originConvoID = "c1")
    }
    override suspend fun listItems(query: ItemsListQuery): ItemsPage = error("unused")
    override suspend fun item(id: String): ItemDetail = error("unused")
    override suspend fun createItem(new: NewItem, idempotencyKey: String?): TrackerItem = error("unused")
    override suspend fun updateItem(id: String, patch: ItemPatch): TrackerItem = error("unused")
    override suspend fun commentItem(
        id: String, body: String, attachments: List<TrackerAttachment>, idempotencyKey: String?, action: String?, replyTo: String?,
    ): ItemCommentResult = error("unused")
    override suspend fun closeItem(id: String, resolution: ItemResolution, comment: String?): TrackerItem = error("unused")
    override suspend fun reopenItem(id: String, comment: String?): TrackerItem = error("unused")
    override suspend fun uploadMedia(data: ByteArray, contentType: String, progress: ((Double) -> Unit)?): String = "b"
}

/// Ported from matron-apple's `ItemsPanelViewModelTests`. The VM's jobs run on
/// the `runBlocking` scope, so `waitUntil`'s polling pumps them.
class ItemsPanelViewModelTest {
    private fun t(
        id: String, num: Int, kind: ItemKind = ItemKind.TASK, awaiting: ItemAwaiting? = ItemAwaiting.AGENT,
        state: ItemState = ItemState.OPEN, rank: Double, closedMs: Long? = null, convo: String = "c1",
    ) = TrackerItem(
        id = id, num = num, kind = kind, state = state, resolution = if (state == ItemState.CLOSED) ItemResolution.DONE else null,
        awaiting = awaiting, rank = rank, title = "T$num", originConvoID = convo,
        createdAt = Instant.ofEpochSecond(num.toLong()), updatedAt = Instant.ofEpochSecond(num.toLong()),
        closedAt = closedMs?.let(Instant::ofEpochSecond),
    )

    @Test
    fun sectionsRule() {
        val items = listOf(
            t("q", 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 5.0), t("a", 2, rank = 2.0), t("b", 3, rank = 1.0),
            t("d", 4, kind = ItemKind.DECISION, awaiting = null, rank = 9.0), t("x", 5, state = ItemState.CLOSED, rank = 0.0, closedMs = 50),
            t("y", 6, state = ItemState.CLOSED, rank = 0.0, closedMs = 60), t("ut", 7, awaiting = ItemAwaiting.USER, rank = 3.0),
        )
        val s = ItemsPanelViewModel.sections(items)
        assertEquals(listOf("ut", "q"), s.needsYou.map { it.id })
        assertEquals(listOf("b", "a", "ut"), s.tasks.map { it.id })
        assertEquals(listOf("d"), s.decisions.map { it.id })
        assertEquals(listOf("y", "x"), s.done.map { it.id })
        assertFalse(s.isEmpty)
        assertTrue(ItemsPanelViewModel.sections(emptyList()).isEmpty)
    }

    @Test
    fun needsYouCountIsScopedToThisConversation() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel("c1", store, FakeItemsApi(), sync, this)
        vm.start()
        vm.setScope(ItemsScope.All)
        waitUntil { store.itemsSubscriptions == 2 }
        val foreign = TrackerItem(id = "f", num = 9, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0, title = "F", originConvoID = "c2")
        store.items.emit(listOf(t("q", 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0), foreign))
        waitUntil { vm.sections.value.needsYou.size == 2 }
        assertEquals("badge counts only this conversation even in All scope", 1, vm.needsYouCount.value)
        vm.stop()
    }

    @Test
    fun startSubscribesAndRefreshes() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel("c1", store, FakeItemsApi(), sync, this)
        assertEquals(ItemsScope.Convo("c1"), vm.itemsScope.value)
        vm.start()
        waitUntil { sync.refreshed.isNotEmpty() }
        store.items.emit(listOf(t("a", 1, rank = 1.0)))
        waitUntil { vm.sections.value.tasks.isNotEmpty() }
        assertEquals(listOf("a"), vm.sections.value.tasks.map { it.id })
        assertEquals(listOf<ItemsScope>(ItemsScope.Convo("c1")), sync.refreshed)
        vm.setScope(ItemsScope.All)
        waitUntil { sync.refreshed.size == 2 }
        assertEquals(ItemsScope.All, sync.refreshed.last())
        vm.stop()
    }

    @Test
    fun moveIsOptimisticAndRevertsOnFailure() = runBlocking {
        val store = FakeItemsStore(); val api = FakeItemsApi(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel("c1", store, api, sync, this)
        vm.start()
        waitUntil { store.itemsSubscriptions == 1 }
        store.items.emit(listOf(t("a", 1, rank = 1.0), t("b", 2, rank = 2.0), t("c", 3, rank = 3.0)))
        waitUntil { vm.sections.value.tasks.size == 3 }
        vm.move("c", 0)
        assertEquals(listOf("c", "a", "b"), vm.sections.value.tasks.map { it.id })
        assertTrue("optimistic rank, not just optimistic order", vm.sections.value.tasks[0].rank < vm.sections.value.tasks[1].rank)
        assertEquals(ItemRankChange(position = "top"), api.rankCalls.first().second)
        assertEquals(listOf("c"), sync.refetched)
        api.failRank = true
        vm.move("a", 2)
        assertEquals("reverted", listOf("c", "a", "b"), vm.sections.value.tasks.map { it.id })
        assertNotNull(vm.error.value)
        api.failRank = false
        vm.move("b", 1) // [c, a, b] -> [c, b, a]
        assertEquals(listOf("c", "b", "a"), vm.sections.value.tasks.map { it.id })
        assertEquals(ItemRankChange(after = "c", before = "a"), api.rankCalls.last().second)
        val callsBeforeNoOp = api.rankCalls.size
        vm.move("c", 0) // already first: a genuine no-op
        assertEquals("no-op move must not hit the network", callsBeforeNoOp, api.rankCalls.size)
        assertEquals(listOf("c", "b", "a"), vm.sections.value.tasks.map { it.id })
        vm.move("c", 2) // to the end
        assertEquals(ItemRankChange(position = "bottom"), api.rankCalls.last().second)
        assertEquals(listOf("b", "a", "c"), vm.sections.value.tasks.map { it.id })
        vm.stop()
    }

    @Test
    fun createEnqueues() = runBlocking {
        val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel("c1", FakeItemsStore(), FakeItemsApi(), sync, this)
        vm.create(ItemKind.TASK, "  Do X ", "why")
        assertEquals("Do X", sync.created.first().title); assertEquals("c1", sync.created.first().convoID)
        assertEquals("why", sync.created.first().body)
        vm.create(ItemKind.TASK, "   ", "")
        assertEquals(1, sync.created.size); assertNotNull(vm.error.value)
        vm.dismissError()
        assertNull(vm.error.value)
        sync.createSucceeds = false
        vm.create(ItemKind.TASK, "Y", "")
        assertNotNull("a failed enqueue is surfaced, not swallowed", vm.error.value)
    }

    @Test
    fun stopCancelsStream() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel("c1", store, FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { store.itemsSubscriptions == 1 }
        store.items.emit(listOf(t("a", 1, rank = 1.0)))
        waitUntil { vm.sections.value.tasks.size == 1 }
        vm.stop()
        store.items.emit(listOf(t("a", 1, rank = 1.0), t("b", 2, rank = 2.0)))
        waitUntil(200) { vm.sections.value.tasks.size == 2 }
        assertEquals("stop() must cancel the store subscription", listOf("a"), vm.sections.value.tasks.map { it.id })
    }

    /// An outbox "create" row emission surfaces as a `pendingCreates` entry,
    /// filtered to this VM's `convoID` when in the conversation scope.
    @Test
    fun pendingCreatesTrackOutboxCreateFlow() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel("c1", store, FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { store.itemsSubscriptions == 1 }
        val mine = ItemOutboxEntity("L1", null, "create", """{"kind":"task","title":"Do X","body":"","convoID":"c1","attachments":[]}""", 0, 0, null)
        val foreign = ItemOutboxEntity("L2", null, "create", """{"kind":"question","title":"Other chat","body":"","convoID":"c2","attachments":[]}""", 1, 2, "offline")
        store.creates.emit(listOf(mine, foreign))
        waitUntil { vm.pendingCreates.value.isNotEmpty() }
        assertEquals("convo scope filters to this VM's convoID", listOf("L1"), vm.pendingCreates.value.map { it.id })
        assertEquals(ItemKind.TASK, vm.pendingCreates.value.first().kind)
        assertEquals("Do X", vm.pendingCreates.value.first().title)
        assertEquals(0, vm.pendingCreates.value.first().attempts)

        vm.setScope(ItemsScope.All)
        waitUntil { store.itemsSubscriptions == 2 }
        store.creates.emit(listOf(mine, foreign))
        waitUntil { vm.pendingCreates.value.size == 2 }
        assertEquals(setOf("L1", "L2"), vm.pendingCreates.value.map { it.id }.toSet())
        assertEquals("offline", vm.pendingCreates.value.first { it.id == "L2" }.lastError)
        vm.stop()
    }

    /// A stale host's teardown (still holding the FIRST generation) must not
    /// cancel the stream a successor `start()` just began.
    @Test
    fun stopIfGenerationGuardsAgainstStaleTeardown() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel("c1", store, FakeItemsApi(), sync, this)
        vm.start()
        val firstGeneration = vm.observationGeneration
        waitUntil { store.itemsSubscriptions == 1 }
        vm.start()
        val secondGeneration = vm.observationGeneration
        assertTrue(firstGeneration != secondGeneration)
        waitUntil { store.itemsSubscriptions == 2 }

        vm.stop(firstGeneration)
        store.items.emit(listOf(t("a", 1, rank = 1.0)))
        waitUntil { vm.sections.value.tasks.isNotEmpty() }
        assertEquals("a stale stop(generation) must not cancel the successor's stream", listOf("a"), vm.sections.value.tasks.map { it.id })

        vm.stop(secondGeneration)
        store.items.emit(listOf(t("a", 1, rank = 1.0), t("b", 2, rank = 2.0)))
        waitUntil(200) { vm.sections.value.tasks.size == 2 }
        assertEquals("a matching-generation stop cancels the observation", listOf("a"), vm.sections.value.tasks.map { it.id })
    }

    @Test
    fun cancelledRefreshNeverClearsItsSuccessorsSpinner() = runBlocking {
        val store = FakeItemsStore()
        // A refresh whose fetch is already in flight: the real one is a network
        // call, so a cancellation only lands once the call itself returns —
        // which can be well after the replacement pass has started.
        val gates = mutableListOf<CompletableDeferred<Unit>>()
        val sync = object : FakeItemsSync() {
            override suspend fun refresh(scope: ItemsScope): ItemsRefreshOutcome {
                refreshed += scope
                val gate = CompletableDeferred<Unit>()
                gates += gate
                withContext(NonCancellable) { gate.await() }
                return ItemsRefreshOutcome.Succeeded
            }
        }
        val vm = ItemsPanelViewModel("c1", store, FakeItemsApi(), sync, this)
        // Every gate is released whatever happens: a failing assertion must
        // not leave a `NonCancellable` await holding this `runBlocking` open.
        try {
            vm.start()
            waitUntil { gates.size == 1 && vm.isRefreshing.value }
            assertTrue(vm.isRefreshing.value)

            // Switching This chat / All resubscribes: the first pass is
            // cancelled and a second starts while the first is still unwinding.
            vm.setScope(ItemsScope.All)
            waitUntil { gates.size == 2 }
            assertEquals(listOf(ItemsScope.Convo("c1"), ItemsScope.All), sync.refreshed)

            gates[0].complete(Unit)
            repeat(50) { yield() }
            assertTrue("the cancelled pass must not hide the spinner mid-refresh", vm.isRefreshing.value)

            gates[1].complete(Unit)
            waitUntil { !vm.isRefreshing.value }
            assertFalse("the owning pass still clears it when it finishes", vm.isRefreshing.value)
        } finally {
            gates.forEach { it.complete(Unit) }
            vm.stop()
        }
    }

    @Test
    fun isSupportedFollowsTheSyncFlag() = runBlocking {
        val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel("c1", FakeItemsStore(), FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { vm.isSupported.value }
        sync.isSupported.value = false
        waitUntil { !vm.isSupported.value }
        assertFalse(vm.isSupported.value)
        vm.stop()
    }

    @Test
    fun nullConvoStartsInAllScope() {
        val vm = ItemsPanelViewModel(null, FakeItemsStore(), FakeItemsApi(), FakeItemsSync(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))
        assertNull(vm.convoID)
        assertEquals(ItemsScope.All, vm.itemsScope.value)
        assertEquals(0, vm.needsYouCount.value)
    }

    @Test
    fun createWithoutConversationSurfacesAnError() = runBlocking {
        val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel(null, FakeItemsStore(), FakeItemsApi(), sync, this)
        vm.create(ItemKind.TASK, "Do X", "")
        assertTrue(sync.created.isEmpty())
        assertNotNull(vm.error.value)
    }

    // MARK: - App shell: all-conversations mode (spec §1)

    @Test
    fun awaitingYouIsCrossConversationNewestFirstRegardlessOfScope() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel("c1", store, FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { store.awaitingSubscriptions == 1 }
        assertEquals("the panel's own scope is untouched by the awaiting flow", ItemsScope.Convo("c1"), vm.itemsScope.value)
        val mine = t("q", 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0) // updatedAt = 1
        val withAgent = t("a", 2, rank = 2.0) // awaiting agent → excluded
        val foreign = TrackerItem(
            id = "f", num = 9, kind = ItemKind.DECISION, awaiting = ItemAwaiting.USER, rank = 1.0, title = "F",
            originConvoID = "c2", updatedAt = Instant.ofEpochSecond(9),
        )
        store.awaiting.emit(listOf(mine, withAgent, foreign))
        waitUntil { vm.awaitingYouCount.value == 2 }
        assertEquals("needsUser only, newest updatedAt first, every conversation", listOf("f", "q"), vm.awaitingYou.value.map { it.id })
        assertEquals("the per-conversation badge only follows the scoped flow", 0, vm.needsYouCount.value)
        vm.stop()
    }

    @Test
    fun awaitingYouCountTracksStoreEmits() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { store.awaitingSubscriptions == 1 }
        store.awaiting.emit(listOf(t("q", 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0)))
        waitUntil { vm.awaitingYouCount.value == 1 }
        store.awaiting.emit(emptyList())
        waitUntil { vm.awaitingYouCount.value == 0 }
        vm.stop()
        store.awaiting.emit(listOf(t("q", 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0)))
        waitUntil(200) { vm.awaitingYouCount.value == 1 }
        assertEquals("stop() cancels the awaiting subscription too", 0, vm.awaitingYouCount.value)
    }

    @Test
    fun awaitingYouRuleIsPure() {
        val items = listOf(
            t("q", 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0),
            t("a", 2, rank = 2.0),
            t("d", 3, kind = ItemKind.DECISION, awaiting = ItemAwaiting.USER, rank = 3.0, convo = "c2"),
            t("x", 4, awaiting = ItemAwaiting.USER, state = ItemState.CLOSED, rank = 0.0, closedMs = 40),
        )
        assertEquals("open + awaiting user, newest first; a closed item never needs you", listOf("d", "q"), ItemsPanelViewModel.awaitingYou(items).map { it.id })
    }

    /// Bugbot (#75): a failed pull-to-refresh must say so — the inline
    /// error row is the only explanation for a stale or empty list.
    @Test
    fun refreshSurfacesAFailedFetch_butTheOpeningRefreshStaysQuiet() = runBlocking {
        val sync = FakeItemsSync().apply { refreshOutcome = ItemsRefreshOutcome.Failed("offline") }
        val store = FakeItemsStore()
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { sync.refreshed.size == 1 }
        assertNull("the opening refresh must not greet an offline open with an error row", vm.error.value)
        vm.refresh()
        assertEquals("offline", vm.error.value)
        assertFalse(vm.isRefreshing.value)
        vm.dismissError()
        sync.refreshOutcome = ItemsRefreshOutcome.Unsupported
        vm.refresh()
        assertNull("unsupported is carried by isSupported, not the error row", vm.error.value)
        vm.stop()
    }

    /// Bugbot (#75): after a failed pull, a successful retry must take the
    /// error row down again — the list just updated.
    @Test
    fun refreshClearsAStaleErrorOnceARetrySucceeds() = runBlocking {
        val sync = FakeItemsSync().apply { refreshOutcome = ItemsRefreshOutcome.Failed("offline") }
        val vm = ItemsPanelViewModel(null, FakeItemsStore(), FakeItemsApi(), sync, this)
        vm.refresh()
        assertEquals("offline", vm.error.value)
        sync.refreshOutcome = ItemsRefreshOutcome.Succeeded
        vm.refresh()
        assertNull("a successful retry clears the stale row", vm.error.value)
        sync.refreshOutcome = ItemsRefreshOutcome.Failed("offline")
        vm.refresh()
        sync.refreshOutcome = ItemsRefreshOutcome.Unsupported
        vm.refresh()
        assertNull("so does an unsupported outcome (isSupported carries it)", vm.error.value)
    }

    @Test
    fun refreshTogglesIsRefreshing() = runBlocking {
        val vm = ItemsPanelViewModel("c1", FakeItemsStore(), FakeItemsApi(), FakeItemsSync(), this)
        assertFalse(vm.isRefreshing.value)
        vm.refresh()
        assertFalse(vm.isRefreshing.value)
    }

    private fun itemSeenRow(localID: String, itemID: String) = ItemOutboxEntity(
        localID, itemID, ItemOutboxEntity.OP_COMMENT, """{"body":"Seen","action":"Seen"}""", 1, 0, null,
    )

    private fun consentAsk(id: String, num: Int, actions: List<String> = listOf("Approve", "Decline")) =
        t(id, num, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0)
            .copy(labels = listOf(TrackerItem.CONSENT_LABEL), actions = actions)

    @Test
    fun answerConsentQueuesTheItemTapAndHidesTheRowUntilTheCloseLands() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { store.awaitingSubscriptions == 1 }
        val ask = consentAsk("c", 7)
        val question = t("q", 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0)
        store.awaiting.emit(listOf(ask, question))
        waitUntil { vm.awaitingYouCount.value == 2 }

        assertFalse("a question that is no consent request has no row answers", vm.answerConsent(question, "Approve"))
        assertFalse("a label the row does not offer", vm.answerConsent(ask, "Maybe"))
        assertTrue(sync.taps.isEmpty())

        assertTrue(vm.answerConsent(ask, "Approve"))
        assertEquals("an item-level tap: the label, no reply_to", listOf(Triple("c", "Approve", null as String?)), sync.taps)
        assertEquals("the body is the label", "Approve", sync.comments.single().second)
        assertEquals("the row leaves at once", listOf("q"), vm.awaitingYou.value.map { it.id })
        assertFalse("a second tap is not queued", vm.answerConsent(ask, "Decline"))
        assertEquals(1, sync.taps.size)
        vm.stop()
    }

    @Test
    fun aRefusedConsentAnswerBringsTheRowBackWithTheJournalsWords() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { store.awaitingSubscriptions == 1 && store.commentRows.subscriptionCount.value == 1 }
        val ask = consentAsk("c", 7)
        store.awaiting.emit(listOf(ask))
        waitUntil { vm.awaitingYouCount.value == 1 }
        assertTrue(vm.answerConsent(ask, "Decline"))
        val localID = sync.localIDs.single()
        val queued = ItemOutboxEntity(localID, "c", ItemOutboxEntity.OP_COMMENT, """{"body":"Decline","action":"Decline"}""", 1, 0, null)
        store.commentRows.emit(listOf(queued))
        waitUntil(200) { false }
        assertEquals("still out while the tap is queued", 0, vm.awaitingYouCount.value)

        store.commentRows.emit(listOf(queued.copy(rejectedReason = "Already answered: this request was approved.")))
        waitUntil { vm.awaitingYouCount.value == 1 }
        assertEquals("Already answered: this request was approved.", vm.error.value)
        vm.stop()
    }

    @Test
    fun aConsentAnswerQueuedOnTheDetailScreenTakesTheRowOutOfForYou() {
        val rows = listOf(
            ItemOutboxEntity("L1", "c", ItemOutboxEntity.OP_COMMENT, """{"body":"Approve","action":"Approve"}""", 1, 0, null),
            ItemOutboxEntity("L2", "d", ItemOutboxEntity.OP_COMMENT, """{"body":"Decline","action":"Decline"}""", 1, 0, null, rejectedReason = "no"),
            ItemOutboxEntity("L3", "e", ItemOutboxEntity.OP_COMMENT, """{"body":"Go","action":"Go"}""", 1, 0, null),
        )
        assertEquals(setOf("c"), ItemsPanelViewModel.queuedConsentAnswers(rows))
    }

    @Test
    fun aSeenTapQueuedOnTheDetailScreenTakesTheNoticeOutOfForYou() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { store.awaitingSubscriptions == 1 && store.commentRows.subscriptionCount.value == 1 }
        val notice = t("n", 2, kind = ItemKind.NOTICE, awaiting = ItemAwaiting.USER, rank = 1.0)
            .copy(actions = listOf(TrackerItem.SEEN_ACTION))
        val question = t("q", 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0)
        store.awaiting.emit(listOf(notice, question))
        waitUntil { vm.awaitingYouCount.value == 2 }

        store.commentRows.emit(listOf(itemSeenRow("L1", "n")))
        waitUntil { vm.awaitingYouCount.value == 1 }
        assertEquals(listOf("q"), vm.awaitingYou.value.map { it.id })
        assertFalse("already tapped on its own page: no second Seen", vm.markSeen(notice))
        assertTrue(sync.taps.isEmpty())
        vm.stop()
    }

    @Test
    fun markSeenSkipsATapTheFlowHasNotCaughtUpWith() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), sync, this)
        val notice = t("n", 2, kind = ItemKind.NOTICE, awaiting = ItemAwaiting.USER, rank = 1.0)
            .copy(actions = listOf(TrackerItem.SEEN_ACTION))
        store.storedOutbox = listOf(itemSeenRow("L1", "n"))
        assertFalse(vm.markSeen(notice))
        assertTrue(sync.taps.isEmpty())
    }

    @Test
    fun aFailedSeenInsertPutsTheRowBack() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync().apply { commentSucceeds = false }
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { store.awaitingSubscriptions == 1 }
        val notice = t("n", 2, kind = ItemKind.NOTICE, awaiting = ItemAwaiting.USER, rank = 1.0)
            .copy(actions = listOf(TrackerItem.SEEN_ACTION))
        store.awaiting.emit(listOf(notice))
        waitUntil { vm.awaitingYouCount.value == 1 }
        assertFalse(vm.markSeen(notice))
        assertEquals("the row is back", listOf("n"), vm.awaitingYou.value.map { it.id })
        assertNotNull(vm.error.value)
        sync.commentSucceeds = true
        assertTrue("and can be tapped again", vm.markSeen(notice))
        vm.stop()
    }

    @Test
    fun markSeenQueuesTheSeenTapAndHidesTheRowUntilTheCloseLands() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), sync, this)
        vm.start()
        waitUntil { store.awaitingSubscriptions == 1 }
        val notice = t("n", 2, kind = ItemKind.NOTICE, awaiting = ItemAwaiting.USER, rank = 1.0)
            .copy(actions = listOf(TrackerItem.SEEN_ACTION))
        val question = t("q", 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0)
        store.awaiting.emit(listOf(notice, question))
        waitUntil { vm.awaitingYouCount.value == 2 }

        assertFalse("only an open notice offers Seen", vm.markSeen(question))
        assertTrue(sync.taps.isEmpty())

        assertTrue(vm.markSeen(notice))
        assertEquals("an item-level tap: the label, no reply_to", listOf(Triple("n", "Seen", null as String?)), sync.taps)
        assertEquals("the body is the label", "Seen", sync.comments.single().second)
        assertEquals("the row leaves at once (the swipe)", listOf("q"), vm.awaitingYou.value.map { it.id })
        assertEquals(1, vm.awaitingYouCount.value)
        assertFalse("a second swipe on the same row is not queued twice", vm.markSeen(notice))
        assertEquals(1, sync.taps.size)

        // A store emission before the journal's close still lists it as
        // needing the user: it stays out.
        store.awaiting.emit(listOf(notice, question))
        waitUntil(200) { vm.awaitingYouCount.value == 2 }
        assertEquals(listOf("q"), vm.awaitingYou.value.map { it.id })
        // The closed notice lands, then the journal hands it back again
        // (reopened by a later agent write): it is listed afresh.
        store.awaiting.emit(listOf(notice.copy(state = ItemState.CLOSED, awaiting = null), question))
        waitUntil(200) { false }
        store.awaiting.emit(listOf(notice, question))
        waitUntil { vm.awaitingYouCount.value == 2 }
        assertEquals(listOf("n", "q"), vm.awaitingYou.value.map { it.id })
        vm.stop()
    }

    // MARK: - For you's Done tab

    private fun closed(id: String, num: Int, kind: ItemKind, closedSec: Long?, resolution: ItemResolution? = ItemResolution.DONE) =
        t(id, num, kind = kind, awaiting = null, state = ItemState.CLOSED, rank = 0.0, closedMs = closedSec).copy(resolution = resolution)

    @Test
    fun decidedListsClosedQuestionsDecisionsAndNoticesNeverTasks() {
        val items = listOf(
            closed("q", 1, ItemKind.QUESTION, 10, ItemResolution.ANSWERED),
            closed("d", 2, ItemKind.DECISION, 20, ItemResolution.DECIDED),
            closed("n", 3, ItemKind.NOTICE, 30),
            closed("task", 4, ItemKind.TASK, 40),
            closed("cancelled", 5, ItemKind.QUESTION, 50, ItemResolution.CANCELLED),
            closed("bare", 6, ItemKind.DECISION, 60, resolution = null),
            t("open-q", 7, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0),
            t("open-n", 8, kind = ItemKind.NOTICE, awaiting = ItemAwaiting.USER, rank = 1.0),
            t("open-d", 9, kind = ItemKind.DECISION, awaiting = null, rank = 1.0),
        )
        assertEquals(
            "whatever the resolution, newest close first; no tasks, nothing open",
            listOf("bare", "cancelled", "n", "d", "q"), ItemsPanelViewModel.decided(items).map { it.id },
        )
        assertFalse(ItemsPanelViewModel.isDecided(items.first { it.id == "task" }))
        assertFalse(ItemsPanelViewModel.isDecided(items.first { it.id == "open-q" }))
        assertEquals("the tasks page's Done section is untouched", 6, ItemsPanelViewModel.sections(items).done.size)
    }

    @Test
    fun decidedOrdersByTheUsersLastInputThenCloseThenUpdate() {
        val answeredLate = closed("answered-late", 1, ItemKind.QUESTION, 10).copy(lastUserInputAt = Instant.ofEpochSecond(90))
        val closedLate = closed("closed-late", 2, ItemKind.QUESTION, 80)
        // Answered before `closed-late` closed, though its own close came after.
        val answeredEarly = closed("answered-early", 3, ItemKind.DECISION, 99).copy(lastUserInputAt = Instant.ofEpochSecond(70))
        // No close time: its last update (`t` sets it to the number) stands in.
        val neverStamped = closed("never-stamped", 60, ItemKind.NOTICE, null)
        val tieLow = closed("tie-low", 4, ItemKind.QUESTION, 50)
        val tieHigh = closed("tie-high", 5, ItemKind.NOTICE, 50)
        assertEquals(
            listOf("answered-late", "closed-late", "answered-early", "never-stamped", "tie-high", "tie-low"),
            ItemsPanelViewModel.decided(listOf(tieLow, neverStamped, answeredEarly, tieHigh, closedLate, answeredLate)).map { it.id },
        )
    }

    @Test
    fun forYouOpensOnNeedsYouAndTheTabIsNotStored() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), FakeItemsSync(), this)
        assertEquals(ForYouTab.OPEN, vm.forYouTab.value)
        vm.selectForYouTab(ForYouTab.CLOSED)
        assertEquals(ForYouTab.CLOSED, vm.forYouTab.value)
        assertEquals(
            "a fresh launch's view model starts on Needs you again",
            ForYouTab.OPEN, ItemsPanelViewModel(null, store, FakeItemsApi(), FakeItemsSync(), this).forYouTab.value,
        )
    }

    @Test
    fun decidedShowsTheLatestFiftyAndShowMoreRevealsFiftyMore() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), FakeItemsSync(), this)
        vm.start()
        waitUntil { store.itemsSubscriptions == 1 }
        assertEquals(0, vm.decidedVisibleCount.value); assertFalse(vm.hasMoreDecided)

        // 120 closed questions; a larger number closed later.
        val all = (1..120).map { closed("q$it", it, ItemKind.QUESTION, it.toLong()) }
        val awaiting = t("open", 121, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, rank = 1.0)
        store.items.emit(all + awaiting)
        waitUntil { vm.decided.value.size == 120 }
        assertEquals("newest first", "q120", vm.decided.value.first().id)
        assertEquals(50, vm.decidedVisibleCount.value); assertTrue(vm.hasMoreDecided)

        vm.showMoreDecided()
        assertEquals(100, vm.decidedVisibleCount.value); assertTrue(vm.hasMoreDecided)

        // An unrelated emission (the open item changes) keeps the window.
        store.items.emit(all + awaiting.copy(title = "Edited"))
        waitUntil { vm.sections.value.needsYou.singleOrNull()?.title == "Edited" }
        assertEquals("an unrelated store emission keeps the expanded window", 100, vm.decidedVisibleCount.value)

        vm.showMoreDecided()
        assertEquals("capped at what exists", 120, vm.decidedVisibleCount.value); assertFalse(vm.hasMoreDecided)

        // The spare the last tap asked for is used when more close.
        store.items.emit(all + awaiting + closed("late", 122, closedSec = 500, kind = ItemKind.NOTICE))
        waitUntil { vm.decided.value.size == 121 }
        assertEquals("late", vm.decided.value.first().id)
        assertEquals(121, vm.decidedVisibleCount.value)
        vm.stop()
    }

    @Test
    fun decidedGrowsFromEmptyAndOnlyTheAppWideInstanceFillsIt() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemsPanelViewModel(null, store, FakeItemsApi(), FakeItemsSync(), this)
        vm.start()
        waitUntil { store.itemsSubscriptions == 1 }
        store.items.emit(emptyList())
        waitUntil(200) { false }
        assertEquals(0, vm.decidedVisibleCount.value)
        store.items.emit(listOf(closed("n", 1, ItemKind.NOTICE, 10)))
        waitUntil { vm.decided.value.size == 1 }
        assertEquals("an item closed after an empty start still shows", 1, vm.decidedVisibleCount.value)
        vm.stop()

        val chatStore = FakeItemsStore()
        val chat = ItemsPanelViewModel("c1", chatStore, FakeItemsApi(), FakeItemsSync(), this)
        chat.start()
        waitUntil { chatStore.itemsSubscriptions == 1 }
        chatStore.items.emit(listOf(closed("q", 1, ItemKind.QUESTION, 10)))
        waitUntil { chat.sections.value.done.size == 1 }
        assertTrue("a conversation's tasks page never builds the Done tab", chat.decided.value.isEmpty())
        chat.stop()
    }
}
