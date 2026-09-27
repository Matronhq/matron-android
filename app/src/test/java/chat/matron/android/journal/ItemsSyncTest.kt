package chat.matron.android.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.events.ItemMarkerEvent
import chat.matron.android.journal.db.ItemOutboxEntity
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Scriptable [ItemsProviding] for the sync tests. Ported from the Apple
/// suite's `FakeItems`: gates (`blockNext*`) hold one call open so a test can
/// prove coalescing / stop semantics against a genuinely in-flight request.
private class FakeItems : ItemsProviding {
    private val lock = Any()
    private val _listResponses = ArrayDeque<ItemsPage>()
    private val _listQueries = mutableListOf<ItemsListQuery>()
    private val _detail = mutableMapOf<String, ItemDetail>()
    private val _commentCalls = mutableListOf<Pair<String, String>>()
    private val _listErrorQueue = ArrayDeque<Throwable?>()
    private val _commentErrorForItemID = mutableMapOf<String, Throwable>()
    private val _listGates = ArrayDeque<CompletableDeferred<Unit>>()
    private var _itemGate: CompletableDeferred<Unit>? = null
    private var _commentGate: CompletableDeferred<Unit>? = null
    private var _createGate: CompletableDeferred<Unit>? = null
    private var _itemCalls = 0

    @Volatile var failComments = false
    @Volatile var listError: Throwable? = null
    @Volatile var blockNextList = false
    @Volatile var blockNextItem = false
    /// Like [blockNextItem], but the held call does NOT observe cancellation
    /// (the way a response already in hand doesn't): it resumes normally when
    /// released, so a test can prove the post-await `stopped` guard alone
    /// keeps the write out — and that `stop()` waited for it.
    @Volatile var blockNextItemUncancellable = false
    @Volatile var blockNextComment = false
    @Volatile var blockNextCreate = false

    var listResponses: List<ItemsPage>
        get() = synchronized(lock) { _listResponses.toList() }
        set(v) = synchronized(lock) { _listResponses.clear(); _listResponses.addAll(v) }
    var listErrorQueue: List<Throwable?>
        get() = synchronized(lock) { _listErrorQueue.toList() }
        set(v) = synchronized(lock) { _listErrorQueue.clear(); _listErrorQueue.addAll(v) }
    val listQueries: List<ItemsListQuery> get() = synchronized(lock) { _listQueries.toList() }
    val commentCalls: List<Pair<String, String>> get() = synchronized(lock) { _commentCalls.toList() }
    val itemCalls: Int get() = synchronized(lock) { _itemCalls }
    val isListGated: Boolean get() = synchronized(lock) { _listGates.isNotEmpty() }
    val isItemGated: Boolean get() = synchronized(lock) { _itemGate != null }
    val isCommentGated: Boolean get() = synchronized(lock) { _commentGate != null }
    val isCreateGated: Boolean get() = synchronized(lock) { _createGate != null }

    fun detail(id: String, item: TrackerItem, comments: List<TrackerComment>) = synchronized(lock) { _detail[id] = ItemDetail(item, comments) }
    fun commentError(id: String, error: Throwable) = synchronized(lock) { _commentErrorForItemID[id] = error }
    fun releaseListGate() = synchronized(lock) { _listGates.removeFirstOrNull() }?.complete(Unit)
    fun releaseItemGate() = synchronized(lock) { val g = _itemGate; _itemGate = null; g }?.complete(Unit)
    fun releaseCommentGate() = synchronized(lock) { val g = _commentGate; _commentGate = null; g }?.complete(Unit)
    fun releaseCreateGate() = synchronized(lock) { val g = _createGate; _createGate = null; g }?.complete(Unit)

    override suspend fun listItems(query: ItemsListQuery): ItemsPage {
        // A cancelled task must actually throw here, the way a cancelled
        // network call would in production.
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val gate = synchronized(lock) {
            _listQueries.add(query)
            if (blockNextList) { blockNextList = false; CompletableDeferred<Unit>().also { _listGates.addLast(it) } } else null
        }
        gate?.await()
        val queued = synchronized(lock) { if (_listErrorQueue.isEmpty()) null else (_listErrorQueue.removeFirst() ?: NoError) }
        when {
            queued == null -> listError?.let { throw it }
            queued === NoError -> Unit
            else -> throw queued
        }
        return synchronized(lock) { _listResponses.removeFirstOrNull() } ?: ItemsPage(emptyList(), null)
    }

    override suspend fun item(id: String): ItemDetail {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        var uncancellable = false
        val gate = synchronized(lock) {
            _itemCalls += 1
            when {
                blockNextItem -> { blockNextItem = false; CompletableDeferred<Unit>().also { _itemGate = it } }
                blockNextItemUncancellable -> { blockNextItemUncancellable = false; uncancellable = true; CompletableDeferred<Unit>().also { _itemGate = it } }
                else -> null
            }
        }
        if (gate != null) {
            if (uncancellable) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate.await() } else gate.await()
        }
        return synchronized(lock) { _detail[id] } ?: throw JournalApiError.NotFound
    }

    override suspend fun createItem(new: NewItem, idempotencyKey: String?): TrackerItem {
        val gate = synchronized(lock) {
            if (blockNextCreate) { blockNextCreate = false; CompletableDeferred<Unit>().also { _createGate = it } } else null
        }
        gate?.await()
        return TrackerItem(id = "it_new", num = 9, kind = new.kind, title = new.title, originConvoID = new.convoID)
    }

    override suspend fun updateItem(id: String, patch: ItemPatch): TrackerItem = error("unused")

    override suspend fun commentItem(id: String, body: String, attachments: List<TrackerAttachment>, idempotencyKey: String?): ItemCommentResult {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val gate = synchronized(lock) {
            if (blockNextComment) { blockNextComment = false; CompletableDeferred<Unit>().also { _commentGate = it } } else null
        }
        gate?.await()
        synchronized(lock) { _commentCalls.add(id to (idempotencyKey ?: "")) }
        synchronized(lock) { _commentErrorForItemID[id] }?.let { throw it }
        if (failComments) throw JournalApiError.Transport("offline")
        val item = TrackerItem(id = id, num = 1, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.AGENT, title = "Q", originConvoID = "c1")
        return ItemCommentResult(item, TrackerComment("ic_srv", id, ItemAuthor.USER, body = body))
    }

    override suspend fun closeItem(id: String, resolution: ItemResolution, comment: String?): TrackerItem = error("unused")
    override suspend fun reopenItem(id: String, comment: String?): TrackerItem = error("unused")
    override suspend fun rankItem(id: String, change: ItemRankChange): TrackerItem = error("unused")
    override suspend fun uploadMedia(data: ByteArray, contentType: String, progress: ((Double) -> Unit)?): String = "blob"

    private object NoError : Throwable()
}

/// Ported from matron-apple's `ItemsSyncTests`. The store is a real
/// in-memory Room database (under Robolectric); the sync runs on
/// `Dispatchers.Default` like production, and the tests poll.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ItemsSyncTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class Rig(
        val sync: ItemsSync,
        val store: JournalStore,
        val markers: MutableSharedFlow<Pair<String, ItemMarkerEvent>>,
        val states: MutableStateFlow<SyncConnectionState>,
    )

    private fun make(api: FakeItems, retryBaseMs: Long = 2_000): Rig {
        val store = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")
        val markers = MutableSharedFlow<Pair<String, ItemMarkerEvent>>(extraBufferCapacity = 64)
        val states = MutableStateFlow<SyncConnectionState>(SyncConnectionState.Connecting)
        val sync = ItemsSync(api, store, markers = { markers }, connectionStates = { states }, retryBaseMs = retryBaseMs)
        return Rig(sync, store, markers, states)
    }

    private fun item(id: String, num: Int, updatedMs: Long) =
        TrackerItem(id = id, num = num, kind = ItemKind.TASK, title = "T", originConvoID = "c1", updatedAt = Instant.ofEpochMilli(updatedMs))

    private fun outboxRow(localID: String, itemID: String, body: String, createdAt: Long = 0) =
        ItemOutboxEntity(localID, itemID, ItemOutboxEntity.OP_COMMENT, """{"body":"$body","attachments":[]}""", createdAt, 0, null)

    private suspend fun waitUntil(timeoutMs: Long = 3_000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue("condition not met before timeout", cond())
    }

    /// Per-scope persisted watermark: each scope's `since` comes from its OWN
    /// high-water mark, not a shared MAX(updated_at) over the whole table.
    @Test
    fun refreshPagesAndUsesWatermark() = runBlocking {
        val api = FakeItems()
        api.listResponses = listOf(ItemsPage(listOf(item("a", 1, 10_000)), "n"), ItemsPage(listOf(item("b", 2, 20_000)), null))
        val rig = make(api)
        assertEquals(ItemsRefreshOutcome.Succeeded, rig.sync.refresh(ItemsScope.Convo("c1")))
        assertEquals(2, rig.store.items(ItemsScope.All).size)
        assertEquals(2, api.listQueries.size); assertEquals("n", api.listQueries[1].cursor); assertEquals("c1", api.listQueries[0].convoID)
        assertEquals("updated", api.listQueries[0].sort.wire); assertEquals(500, api.listQueries[0].limit)
        assertNull("no persisted watermark yet for this scope → full fetch", api.listQueries[0].since)
        assertEquals(
            "watermark = newest updated_at seen across every page, persisted after the loop completes",
            Instant.ofEpochMilli(20_000), rig.store.itemsWatermark(ItemsScope.Convo("c1")),
        )

        api.listResponses = listOf(ItemsPage(emptyList(), null))
        rig.sync.refresh(ItemsScope.Convo("c1"))
        assertEquals("since = watermark − 1s", Instant.ofEpochMilli(19_000), api.listQueries[2].since)
        assertEquals("c1", api.listQueries[2].convoID)

        api.listResponses = listOf(ItemsPage(emptyList(), null))
        rig.sync.refresh(ItemsScope.All)
        assertNull("per-scope watermark: .all is independent of convo(c1)'s", api.listQueries[3].since)
        assertNull(api.listQueries[3].convoID)
        assertTrue(rig.sync.isSupported.value)
    }

    /// A throw partway through pagination must NOT persist a watermark for
    /// whatever pages happened to land first.
    @Test
    fun midPaginationFailureLeavesWatermarkUnset() = runBlocking {
        val api = FakeItems()
        api.listResponses = listOf(ItemsPage(listOf(item("a", 1, 10_000)), "n"))
        api.listErrorQueue = listOf(null, IllegalStateException("boom"))
        val rig = make(api)
        val outcome = rig.sync.refresh(ItemsScope.All)
        assertTrue(outcome is ItemsRefreshOutcome.Failed)
        assertNull("mid-pagination failure must not persist a partial watermark", rig.store.itemsWatermark(ItemsScope.All))
        assertEquals("page 1's item, upserted before the failure, is still cached", 1, rig.store.items(ItemsScope.All).size)

        api.listErrorQueue = emptyList()
        api.listResponses = listOf(ItemsPage(emptyList(), null))
        rig.sync.refresh(ItemsScope.All)
        assertNull("no persisted watermark → next refresh is a full fetch", api.listQueries.last().since)
    }

    @Test
    fun concurrentSameScopeRefreshesShareOneFetch() = runBlocking {
        val api = FakeItems()
        api.blockNextList = true
        val rig = make(api)
        val first = async(Dispatchers.Default) { rig.sync.refresh(ItemsScope.All) }
        waitUntil { api.isListGated }
        var secondFinished = false
        val second = async(Dispatchers.Default) { rig.sync.refresh(ItemsScope.All).also { secondFinished = true } }
        delay(150)
        assertFalse("the second caller must await the run already in flight", secondFinished)
        api.releaseListGate()
        first.await(); second.await()
        assertEquals("two concurrent .all refreshes hit the API once", 1, api.listQueries.size)
        // Coalescing is per-run, not a permanent latch.
        rig.sync.refresh(ItemsScope.All)
        assertEquals(2, api.listQueries.size)
    }

    @Test
    fun refreshReportsWhatItDid() = runBlocking {
        val api = FakeItems()
        api.listResponses = listOf(ItemsPage(listOf(item("a", 1, 10_000)), null))
        val rig = make(api)
        assertEquals(ItemsRefreshOutcome.Succeeded, rig.sync.refresh(ItemsScope.All))
        api.listError = JournalApiError.NotFound
        assertEquals(ItemsRefreshOutcome.Unsupported, rig.sync.refresh(ItemsScope.All))
        assertFalse(rig.sync.isSupported.value)
        api.listError = JournalApiError.Transport("offline")
        val failed = rig.sync.refresh(ItemsScope.All)
        assertEquals(ItemsRefreshOutcome.Failed(JournalApiError.Transport("offline").message!!), failed)
    }

    @Test
    fun coalescedRefreshJoinersShareTheFailure() = runBlocking {
        val api = FakeItems()
        api.listError = JournalApiError.Transport("offline")
        api.blockNextList = true
        val rig = make(api)
        val first = async(Dispatchers.Default) { rig.sync.refresh(ItemsScope.All) }
        waitUntil { api.isListGated }
        val second = async(Dispatchers.Default) { rig.sync.refresh(ItemsScope.All) }
        delay(100)
        api.releaseListGate()
        val outcomes = listOf(first.await(), second.await())
        assertEquals(1, api.listQueries.size)
        outcomes.forEach { assertTrue("$it", it is ItemsRefreshOutcome.Failed) }
    }

    @Test
    fun concurrentDifferentScopesStillFetchIndependently() = runBlocking {
        val api = FakeItems()
        api.blockNextList = true
        val rig = make(api)
        val first = async(Dispatchers.Default) { rig.sync.refresh(ItemsScope.All) }
        waitUntil { api.isListGated }
        rig.sync.refresh(ItemsScope.Convo("c1"))
        assertEquals(2, api.listQueries.size)
        api.releaseListGate()
        first.await()
        Unit
    }

    @Test
    fun stopCancelsAnInFlightRefreshSoItsDataNeverLands() = runBlocking {
        val api = FakeItems()
        api.listResponses = listOf(ItemsPage(listOf(item("a", 1, 10_000)), null))
        api.blockNextList = true
        val rig = make(api)
        rig.sync.start()
        val refresh = async(Dispatchers.Default) { rig.sync.refresh(ItemsScope.All) }
        waitUntil { api.isListGated }
        rig.sync.stop()
        api.releaseListGate()
        assertEquals(ItemsRefreshOutcome.Stopped, withTimeout(2_000) { refresh.await() })
        assertTrue("a stopped refresh writes nothing", rig.store.items(ItemsScope.All).isEmpty())
        assertNull(rig.store.itemsWatermark(ItemsScope.All))
    }

    @Test
    fun notFoundMarksUnsupported() = runBlocking {
        val api = FakeItems(); api.listError = JournalApiError.NotFound
        val rig = make(api)
        assertEquals(ItemsRefreshOutcome.Unsupported, rig.sync.refresh(ItemsScope.All))
        assertFalse(rig.sync.isSupported.value)
    }

    @Test
    fun markerRefetchesThatItem() = runBlocking {
        val api = FakeItems()
        api.detail("it_1", item("it_1", 1, 5_000), listOf(TrackerComment("ic_1", "it_1", ItemAuthor.USER, body = "x")))
        val rig = make(api)
        rig.sync.start()
        waitUntil { rig.markers.subscriptionCount.value > 0 }
        rig.markers.emit("c1" to ItemMarkerEvent("it_1", 1, ItemKind.TASK, "T", ItemMarkerEvent.Action.COMMENTED, ItemAuthor.USER))
        // The refetch lands as two writes (item, then comments); waiting on the
        // item alone can observe the gap between them under load.
        waitUntil { rig.store.comments("it_1").isNotEmpty() }
        assertNotNull(rig.store.item("it_1"))
        assertEquals(listOf("ic_1"), rig.store.comments("it_1").map { it.id })
        rig.sync.stop()
    }

    /// A `refreshItem` that lands while another refetch of the same id is in
    /// flight must not return until the store holds the server's thread — it
    /// waits for the in-flight run, which then runs once more.
    @Test
    fun coalescedRefreshItemAwaitsTheInFlightRun() = runBlocking {
        val api = FakeItems()
        api.detail("it_1", item("it_1", 1, 5_000), listOf(TrackerComment("ic_1", "it_1", ItemAuthor.USER, body = "x")))
        val rig = make(api)
        api.blockNextItem = true
        val first = launch(Dispatchers.Default) { rig.sync.refreshItem("it_1") }
        waitUntil { api.isItemGated }
        var secondReturned = false
        val second = launch(Dispatchers.Default) { rig.sync.refreshItem("it_1"); secondReturned = true }
        delay(150)
        assertFalse("the coalesced caller must wait for the in-flight refetch", secondReturned)
        api.releaseItemGate()
        first.join(); second.join()
        assertEquals("the in-flight run repeats once for the coalesced request", 2, api.itemCalls)
        assertEquals(listOf("ic_1"), rig.store.comments("it_1").map { it.id })
    }

    /// `applyItem` lands the item a close/reopen returned so the screen is
    /// honest even if the follow-up refetch fails. A refetch that was ALREADY
    /// in flight when that happened is carrying the pre-mutation snapshot, and
    /// must not put it back: the thread would read as open again right after a
    /// close, and the retry that invites hits a conflict.
    @Test
    fun aRefetchInFlightAcrossAnApplyItemDoesNotPutTheOldSnapshotBack() = runBlocking {
        val api = FakeItems()
        val open = item("it_1", 1, 5_000)
        api.detail("it_1", open, listOf(TrackerComment("ic_1", "it_1", ItemAuthor.USER, body = "x")))
        val rig = make(api)
        api.blockNextItem = true
        val refetch = launch(Dispatchers.Default) { rig.sync.refreshItem("it_1") }
        waitUntil { api.isItemGated }

        // The close's own result, while that GET is still open.
        val closed = open.copy(
            state = ItemState.CLOSED, resolution = ItemResolution.DONE, updatedAt = Instant.ofEpochMilli(9_000),
        )
        rig.sync.applyItem(closed)
        assertEquals(ItemState.CLOSED, rig.store.item("it_1")?.state)

        api.releaseItemGate()
        withTimeout(2_000) { refetch.join() }
        assertEquals(
            "a response produced before the mutation must not overwrite it",
            ItemState.CLOSED, rig.store.item("it_1")?.state,
        )
        assertEquals("and its thread is dropped with it", emptyList<String>(), rig.store.comments("it_1").map { it.id })

        // A refetch that STARTS after the local write is authoritative again.
        rig.sync.refreshItem("it_1")
        assertEquals(ItemState.OPEN, rig.store.item("it_1")?.state)
        assertEquals(listOf("ic_1"), rig.store.comments("it_1").map { it.id })
    }

    @Test
    fun outboxDrainsOnRunningAndDeletesOnSuccess() = runBlocking {
        val api = FakeItems(); api.failComments = true
        val rig = make(api)
        rig.sync.start()
        rig.sync.enqueueComment("it_1", "L1", "hello", emptyList())
        waitUntil { rig.store.itemOutboxRows("it_1").firstOrNull()?.attempts == 1 }
        api.failComments = false
        rig.states.value = SyncConnectionState.Running
        waitUntil { rig.store.itemOutboxPending().isEmpty() }
        assertEquals("idempotency key = local id on every attempt", listOf("L1", "L1"), api.commentCalls.map { it.second })
        assertEquals(ItemAwaiting.AGENT, rig.store.item("it_1")?.awaiting)
        assertEquals("the posted comment is kept locally", listOf("ic_srv"), rig.store.comments("it_1").map { it.id })
        rig.sync.stop()
    }

    /// A failed drain schedules exactly one delayed retry; `Running` is never
    /// yielded here, so the outbox draining is proof the retry timer did it.
    /// The exact count guards against a self-cancelling retry storm.
    @Test
    fun failedDrainSchedulesBackoffRetry() = runBlocking {
        val api = FakeItems(); api.failComments = true
        val rig = make(api, retryBaseMs = 50)
        rig.sync.start()
        rig.sync.enqueueComment("it_1", "L1", "hello", emptyList())
        waitUntil { rig.store.itemOutboxRows("it_1").firstOrNull()?.attempts == 1 }
        api.failComments = false
        waitUntil { rig.store.itemOutboxPending().isEmpty() }
        assertEquals("exactly one original attempt + one retry-delivered attempt", 2, api.commentCalls.size)
        assertEquals(ItemAwaiting.AGENT, rig.store.item("it_1")?.awaiting)
        rig.sync.stop()
    }

    @Test
    fun repeatedCursorTruncatesPaginationAndLeavesWatermarkUnset() = runBlocking {
        val api = FakeItems()
        val looping = ItemsPage(listOf(item("a", 1, 10_000)), "loop")
        api.listResponses = listOf(looping, looping, looping)
        val rig = make(api)
        rig.sync.refresh(ItemsScope.All)
        assertNull("a truncated pagination run must not persist a watermark", rig.store.itemsWatermark(ItemsScope.All))
        assertEquals("stops as soon as the SAME cursor repeats", 2, api.listQueries.size)
    }

    @Test
    fun drainWaitsForSupportBeforeAttemptingRows() = runBlocking {
        val api = FakeItems(); api.listError = JournalApiError.NotFound
        val rig = make(api)
        rig.sync.refresh(ItemsScope.All)
        assertFalse(rig.sync.isSupported.value)
        rig.store.itemOutboxInsert(outboxRow("L1", "it_1", "x"))
        rig.sync.drainOutbox()
        assertEquals("no attempt is made while unsupported", 0, rig.store.itemOutboxRows("it_1").first().attempts)
        assertEquals(0, api.commentCalls.size)
        assertEquals(1, rig.store.itemOutboxPending().size)
    }

    /// A successful refresh — the thing that flips `isSupported` true — must
    /// itself resume a paused drain, not just publish the flag.
    @Test
    fun refreshDrainsOutboxOnceSupportIsProven() = runBlocking {
        val api = FakeItems(); api.listError = JournalApiError.NotFound
        val rig = make(api)
        rig.sync.refresh(ItemsScope.All)
        rig.sync.enqueueComment("it_1", "L1", "hello", emptyList())
        assertEquals(listOf("L1"), rig.store.itemOutboxPending().map { it.localID })
        assertEquals("the drain never attempted the row while unsupported", 0, api.commentCalls.size)
        api.listError = null
        rig.sync.refresh(ItemsScope.All)
        waitUntil { rig.store.itemOutboxPending().isEmpty() }
        assertEquals(1, api.commentCalls.size)
        assertEquals(ItemAwaiting.AGENT, rig.store.item("it_1")?.awaiting)
    }

    @Test
    fun notFoundOnOutboxWriteIsRetryableNotPoison() = runBlocking {
        val api = FakeItems()
        api.commentError("it_1", JournalApiError.NotFound)
        val rig = make(api)
        rig.store.itemOutboxInsert(outboxRow("L1", "it_1", "x"))
        rig.sync.drainOutbox()
        assertEquals("404 is retryable: the row survives with a bumped attempt count", 1, rig.store.itemOutboxRows("it_1").first().attempts)
        assertEquals(1, api.commentCalls.size)
        rig.sync.stop()
    }

    @Test
    fun authRejectionPausesWithoutDeletingCountingOrRetrying() = runBlocking {
        val api = FakeItems()
        api.commentError("it_1", JournalApiError.Unauthenticated)
        val rig = make(api, retryBaseMs = 50)
        rig.store.itemOutboxInsert(outboxRow("L1", "it_1", "x"))
        rig.sync.drainOutbox()
        assertEquals("an auth rejection must not count as an attempt", 0, rig.store.itemOutboxRows("it_1").first().attempts)
        assertEquals(1, api.commentCalls.size)
        delay(300)
        assertEquals("no backoff retry is scheduled on a pause", 1, api.commentCalls.size)
        assertEquals("the row is left queued, not deleted", 1, rig.store.itemOutboxPending().size)
    }

    @Test
    fun poisonRowIsSkippedNotBlockingQueue() = runBlocking {
        val api = FakeItems()
        api.commentError("it_bad", JournalApiError.Http(400, "bad request"))
        val rig = make(api)
        rig.store.itemOutboxInsert(outboxRow("L1", "it_bad", "bad", 0))
        rig.store.itemOutboxInsert(outboxRow("L2", "it_good", "ok", 1))
        rig.sync.drainOutbox()
        waitUntil { rig.store.itemOutboxPending().isEmpty() }
        assertEquals(listOf("it_bad", "it_good"), api.commentCalls.map { it.first })
        assertEquals(1, api.commentCalls.count { it.first == "it_bad" })
    }

    @Test
    fun commentSurvivesEvenWhenFollowUpRefreshItemFails() = runBlocking {
        val api = FakeItems() // no `detail` entry ⇒ the follow-up GET /items/:id 404s
        val rig = make(api)
        rig.store.itemOutboxInsert(outboxRow("L1", "it_1", "x"))
        rig.sync.drainOutbox()
        assertTrue(rig.store.itemOutboxPending().isEmpty())
        assertEquals("the server's comment stays visible despite the failed refetch", listOf("ic_srv"), rig.store.comments("it_1").map { it.id })
    }

    @Test
    fun stopAbortsInFlightDrainWithoutWriting() = runBlocking {
        val api = FakeItems()
        api.blockNextComment = true
        val rig = make(api)
        rig.sync.start()
        rig.store.itemOutboxInsert(outboxRow("L1", "it_1", "x"))
        val drain = launch(Dispatchers.Default) { rig.sync.drainOutbox() }
        waitUntil { api.isCommentGated }
        rig.sync.stop()
        api.releaseCommentGate()
        withTimeout(2_000) { drain.join() }
        assertEquals("the row is left exactly as it was", listOf("L1"), rig.store.itemOutboxPending().map { it.localID })
        assertEquals(0, rig.store.itemOutboxRows("it_1").first().attempts)
        assertTrue("nothing lands after stop()", rig.store.comments("it_1").isEmpty())
    }

    /// A per-item refetch in flight at `stop()` never writes after `stop()`
    /// returns (Bugbot, #71): the job is cancelled and joined like a refresh.
    @Test
    fun stopCancelsAnInFlightRefetchSoItsDataNeverLands() = runBlocking {
        val api = FakeItems()
        api.detail("it_1", item("it_1", 1, 5_000), listOf(TrackerComment("ic_1", "it_1", ItemAuthor.USER, body = "x")))
        api.blockNextItem = true
        val rig = make(api)
        rig.sync.start()
        val refetch = launch(Dispatchers.Default) { rig.sync.refreshItem("it_1") }
        waitUntil { api.isItemGated }
        withTimeout(2_000) { rig.sync.stop() }
        api.releaseItemGate()
        withTimeout(2_000) { refetch.join() }
        assertNull("nothing lands after stop()", rig.store.item("it_1"))
        assertTrue(rig.store.comments("it_1").isEmpty())
    }

    /// The harder shape: the refetch's network call has already returned by
    /// the time `stop()` runs (it can't observe cancellation any more).
    /// `stop()` must WAIT for it, and the resumed refetch must not write.
    @Test
    fun stopWaitsForARefetchPastItsNetworkCallAndItStillWritesNothing() = runBlocking {
        val api = FakeItems()
        api.detail("it_1", item("it_1", 1, 5_000), emptyList())
        api.blockNextItemUncancellable = true
        val rig = make(api)
        rig.sync.start()
        val refetch = launch(Dispatchers.Default) { rig.sync.refreshItem("it_1") }
        waitUntil { api.isItemGated }
        var stopped = false
        val stop = launch(Dispatchers.Default) { rig.sync.stop(); stopped = true }
        delay(200)
        assertFalse("stop() must not return while a refetch can still write", stopped)
        api.releaseItemGate()
        withTimeout(2_000) { stop.join(); refetch.join() }
        assertNull("the resumed refetch saw `stopped` and wrote nothing", rig.store.item("it_1"))
    }

    /// The background drain `enqueueCreate` kicks (and never awaits) is
    /// cancelled and joined by `stop()` too.
    @Test
    fun stopCancelsTheBackgroundDrainAnEnqueueCreateStarted() = runBlocking {
        val api = FakeItems()
        api.blockNextCreate = true
        val rig = make(api)
        rig.sync.refresh(ItemsScope.All) // proves support so the drain attempts the row
        assertTrue(rig.sync.enqueueCreate("L1", NewItem(kind = ItemKind.TASK, title = "T", convoID = "c1")))
        waitUntil { api.isCreateGated }
        withTimeout(2_000) { rig.sync.stop() }
        api.releaseCreateGate()
        delay(100)
        assertEquals("the row is left queued, untouched", listOf("L1"), rig.store.itemOutboxPending().map { it.localID })
        assertNull("the create's result never lands after stop()", rig.store.item("it_new"))
    }

    @Test
    fun enqueueAfterStopDoesNotInsertIntoOutbox() = runBlocking {
        val api = FakeItems()
        val rig = make(api)
        rig.sync.start()
        rig.sync.stop()
        rig.sync.enqueueComment("it_1", "L1", "hello", emptyList())
        assertFalse(rig.sync.enqueueCreate("L2", NewItem(kind = ItemKind.TASK, title = "T", convoID = "c1")))
        assertTrue(rig.store.itemOutboxPending().isEmpty())
    }

    @Test
    fun enqueueCreateReturnsBeforeTheBackgroundDrainCompletes() = runBlocking {
        val api = FakeItems()
        api.blockNextCreate = true
        val rig = make(api)
        rig.sync.refresh(ItemsScope.All) // proves support so the drain attempts the row
        val queued = withTimeout(2_000) { rig.sync.enqueueCreate("L1", NewItem(kind = ItemKind.TASK, title = "Do X", body = "why", convoID = "c1")) }
        assertTrue(queued)
        assertEquals("durably queued before the network round-trip finishes", listOf("L1"), rig.store.itemOutboxPending().map { it.localID })
        waitUntil { api.isCreateGated }
        api.releaseCreateGate()
        waitUntil { rig.store.itemOutboxPending().isEmpty() }
        assertEquals("Do X", rig.store.item("it_new")?.title)
    }
}
