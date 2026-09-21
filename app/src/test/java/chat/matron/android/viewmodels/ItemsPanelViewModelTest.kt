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
    override fun commentsFlow(itemID: String): Flow<List<TrackerComment>> {
        commentsSubscriptions += 1
        return comments
    }
    override suspend fun comments(itemID: String): List<TrackerComment> = storedComments
    override fun itemOutboxFlow(itemID: String): Flow<List<ItemOutboxEntity>> = outbox
    override fun itemOutboxCreatesFlow(): Flow<List<ItemOutboxEntity>> {
        creates.resetReplayCache()
        return creates
    }
}

internal open class FakeItemsSync : ItemsSyncing {
    val refreshed = mutableListOf<ItemsScope>()
    val created = mutableListOf<NewItem>()
    val refetched = mutableListOf<String>()
    val applied = mutableListOf<TrackerItem>()
    val comments = mutableListOf<Triple<String, String, List<TrackerAttachment>>>()
    var createSucceeds = true
    override val isSupported = MutableStateFlow(true)
    override suspend fun refresh(scope: ItemsScope): ItemsRefreshOutcome { refreshed += scope; return ItemsRefreshOutcome.Succeeded }
    override suspend fun refreshItem(id: String) { refetched += id }
    override suspend fun applyItem(item: TrackerItem) { applied += item }
    override suspend fun enqueueComment(itemID: String, localID: String, body: String, attachments: List<TrackerAttachment>) {
        comments += Triple(itemID, body, attachments)
    }
    override suspend fun enqueueCreate(localID: String, new: NewItem): Boolean { created += new; return createSucceeds }
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
    override suspend fun commentItem(id: String, body: String, attachments: List<TrackerAttachment>, idempotencyKey: String?): ItemCommentResult = error("unused")
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

    @Test
    fun refreshTogglesIsRefreshing() = runBlocking {
        val vm = ItemsPanelViewModel("c1", FakeItemsStore(), FakeItemsApi(), FakeItemsSync(), this)
        assertFalse(vm.isRefreshing.value)
        vm.refresh()
        assertFalse(vm.isRefreshing.value)
    }
}
