package chat.matron.android.journal

import chat.matron.android.events.MemoryMarkerEvent
import chat.matron.android.models.Memory
import chat.matron.android.models.MemoryType
import chat.matron.android.models.SyncConnectionState
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMemories : MemoriesProviding {
    private val lock = Any()
    private var _list: List<Memory> = emptyList()
    private var _listCalls = 0
    private var _listGate: CompletableDeferred<Unit>? = null
    val saves = mutableListOf<Pair<String, MemoryWrite>>()
    val deletes = mutableListOf<String>()
    @Volatile var listError: Throwable? = null
    @Volatile var blockNextList = false

    var list: List<Memory>
        get() = synchronized(lock) { _list }
        set(v) = synchronized(lock) { _list = v }
    val listCalls: Int get() = synchronized(lock) { _listCalls }
    fun releaseListGate() = synchronized(lock) { val g = _listGate; _listGate = null; g }?.complete(Unit)

    override suspend fun listMemories(): List<Memory> {
        val gate = synchronized(lock) {
            _listCalls += 1
            if (blockNextList) { blockNextList = false; CompletableDeferred<Unit>().also { _listGate = it } } else null
        }
        gate?.await()
        listError?.let { throw it }
        return list
    }

    override suspend fun saveMemory(name: String, write: MemoryWrite): MemorySave {
        saves += name to write
        val m = memory(name, write.description)
        list = list.filterNot { it.name == name } + m
        return MemorySave(m, created = true)
    }

    override suspend fun deleteMemory(name: String): Memory {
        deletes += name
        val gone = list.firstOrNull { it.name == name } ?: throw JournalApiError.NotFound
        list = list.filterNot { it.name == name }
        return gone
    }
}

private fun memory(name: String, description: String = "d") = Memory(
    id = "me_$name", name = name, type = MemoryType.FEEDBACK, description = description,
    createdAt = Instant.ofEpochSecond(1), updatedAt = Instant.ofEpochSecond(2),
)

private fun marker(id: String = "me_x", action: MemoryMarkerEvent.Action = MemoryMarkerEvent.Action.SAVED) =
    "c1" to MemoryMarkerEvent(memoryID = id, action = action)

/// Plain JUnit: there is no store, so no Robolectric. The sync runs on
/// `Dispatchers.Default` like production and the tests poll.
class MemoriesSyncTest {
    private class Rig(
        val sync: MemoriesSync,
        val markers: MutableSharedFlow<Pair<String, MemoryMarkerEvent>>,
        val states: MutableStateFlow<SyncConnectionState>,
    )

    private fun make(api: FakeMemories): Rig {
        val markers = MutableSharedFlow<Pair<String, MemoryMarkerEvent>>(extraBufferCapacity = 64)
        val states = MutableStateFlow<SyncConnectionState>(SyncConnectionState.Connecting)
        return Rig(MemoriesSync(api, markers = { markers }, connectionStates = { states }), markers, states)
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue("condition not met before timeout", cond())
    }

    @Test
    fun refreshLoadsTheListSortedAndMarksSupported() = runBlocking {
        val api = FakeMemories()
        api.list = listOf(memory("b"), memory("a"))
        val rig = make(api)
        assertNull(rig.sync.isSupported.value)
        assertEquals(MemoriesRefreshOutcome.Succeeded, rig.sync.refresh())
        assertEquals(listOf("a", "b"), rig.sync.memories.value.map { it.name })
        assertEquals(true, rig.sync.isSupported.value)
    }

    @Test
    fun a404IsUnsupportedAndAnyOtherFailureKeepsTheLastList() = runBlocking {
        val api = FakeMemories()
        api.list = listOf(memory("a"))
        val rig = make(api)
        rig.sync.refresh()
        api.listError = JournalApiError.NotFound
        assertEquals(MemoriesRefreshOutcome.Unsupported, rig.sync.refresh())
        assertEquals(false, rig.sync.isSupported.value)
        api.listError = JournalApiError.Transport("offline")
        val failed = rig.sync.refresh()
        assertTrue(failed is MemoriesRefreshOutcome.Failed)
        assertEquals(listOf("a"), rig.sync.memories.value.map { it.name })
        assertEquals(false, rig.sync.isSupported.value)
    }

    @Test
    fun nothingIsFetchedUntilAScreenAsksAndMarkersThenRefetch() = runBlocking {
        val api = FakeMemories()
        val rig = make(api)
        rig.sync.start()
        // The marker flow has no replay: emit only once the sync's collector
        // is subscribed, as the engine's callbackFlow guarantees in the app.
        waitUntil { rig.markers.subscriptionCount.value > 0 }
        rig.states.value = SyncConnectionState.Running
        rig.markers.emit(marker())
        delay(100)
        assertEquals("no fetch before the first refresh", 0, api.listCalls)
        rig.sync.refresh()
        assertEquals(1, api.listCalls)
        api.list = listOf(memory("new-one"))
        rig.markers.emit(marker())
        waitUntil { rig.sync.memories.value.map { it.name } == listOf("new-one") }
        // A reconnect refetches a loaded list too.
        api.list = listOf(memory("after-reconnect"))
        rig.states.value = SyncConnectionState.Connecting
        // A StateFlow conflates back-to-back writes; let the collector see
        // the drop before the reconnect, as a real socket would.
        delay(50)
        rig.states.value = SyncConnectionState.Running
        waitUntil { rig.sync.memories.value.map { it.name } == listOf("after-reconnect") }
        rig.sync.stop()
    }

    @Test
    fun aBurstOfMarkersDuringAFetchJoinsItAndCostsOneFollowUp() = runBlocking {
        val api = FakeMemories()
        val rig = make(api)
        rig.sync.refresh()
        api.blockNextList = true
        val first = async { rig.sync.refresh() }
        waitUntil { api.listCalls == 2 }
        // Four markers (the journal writes one per conversation) while the
        // fetch is open join it rather than queueing four more …
        rig.sync.start()
        waitUntil { rig.markers.subscriptionCount.value > 0 }
        rig.markers.emit(marker()); rig.markers.emit(marker())
        rig.markers.emit(marker()); rig.markers.emit(marker())
        // Wait for the joiners to park on the open fetch (no fixed delay:
        // under a loaded suite the launched refreshes can lag).
        waitUntil { rig.sync.rerunQueued }
        delay(50)
        assertEquals(2, api.listCalls)
        // … but the joined fetch may predate them, so exactly one follow-up
        // runs once it completes and its answer is what the list shows.
        api.list = listOf(memory("landed-mid-fetch"))
        api.releaseListGate()
        assertEquals(MemoriesRefreshOutcome.Succeeded, first.await())
        waitUntil { rig.sync.memories.value.map { it.name } == listOf("landed-mid-fetch") }
        waitUntil { api.listCalls == 3 }
        delay(100)
        assertEquals(3, api.listCalls)
        rig.sync.stop()
    }

    @Test
    fun saveAndDeleteHitTheApiThenRefetch() = runBlocking {
        val api = FakeMemories()
        val rig = make(api)
        rig.sync.refresh()
        val saved = rig.sync.save("rule-one", MemoryWrite("Do it.", body = "why", type = MemoryType.USER))
        assertTrue(saved.created)
        assertEquals(listOf("rule-one" to MemoryWrite("Do it.", body = "why", type = MemoryType.USER)), api.saves)
        assertEquals(listOf("rule-one"), rig.sync.memories.value.map { it.name })
        val gone = rig.sync.delete("rule-one")
        assertEquals("rule-one", gone.name)
        assertEquals(listOf("rule-one"), api.deletes)
        assertTrue(rig.sync.memories.value.isEmpty())
        assertEquals(3, api.listCalls)
    }
}
