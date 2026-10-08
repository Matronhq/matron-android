package chat.matron.android.journal

import chat.matron.android.viewmodels.InMemoryKeyValueStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// [PinsSync]: adopting the journal's list from the engine feed and from
/// every write's answer, the cold-start cache, and the writes' wording.
class PinsSyncTest {
    private fun pin(convoID: String, position: Int) =
        ConvoPin(convoID, "Pin $convoID", "", position, 7L)

    private class FakePinsApi : PinsProviding {
        val calls = mutableListOf<String>()
        var answer: PinList = PinList(emptyList(), 5)
        var failure: Throwable? = null

        private fun respond(call: String): PinList {
            calls += call
            failure?.let { throw it }
            return answer
        }

        override suspend fun pins() = respond("get")
        override suspend fun reorderPins(order: List<String>) = respond("reorder ${order.joinToString(",")}")
        override suspend fun putPin(convoID: String, label: String?, emoji: String?) = respond("put $convoID $label|$emoji")
        override suspend fun deletePin(convoID: String) = respond("delete $convoID")
        override suspend fun movePin(convoID: String, toConvoID: String) = respond("move $convoID $toConvoID")
        override suspend fun dismissPinSuccessor(convoID: String, successorID: String) = respond("dismiss $convoID $successorID")
    }

    private suspend fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
    }

    @Test
    fun adoptsTheFeedAndCachesItForTheNextStart() = runBlocking {
        val store = InMemoryKeyValueStore()
        val feed = MutableSharedFlow<List<ConvoPin>?>(replay = 1)
        val sync = PinsSync(FakePinsApi(), { feed }, store, "u1")
        assertNull("nothing heard, nothing cached: no pins", sync.pins.value)
        sync.start()
        feed.emit(listOf(pin("a", 0), pin("b", 1)))
        waitUntil { sync.pins.value != null }
        assertEquals(listOf("a", "b"), sync.pins.value!!.map { it.convoID })

        val next = PinsSync(FakePinsApi(), { MutableSharedFlow() }, store, "u1")
        assertEquals("a cold start draws the cached list", listOf("a", "b"), next.pins.value!!.map { it.convoID })
        assertNull("another user's cache is separate", PinsSync(FakePinsApi(), { MutableSharedFlow() }, store, "u2").pins.value)

        feed.emit(null)
        waitUntil { sync.pins.value == null }
        assertNull("an older journal drops the cache", store.getString(PinsSync.cacheKey("u1")))
        sync.stop()
    }

    @Test
    fun aWriteAdoptsTheAnswerAndItsLimit() = runBlocking {
        val api = FakePinsApi()
        api.answer = PinList(listOf(pin("c1", 0)), 4)
        val sync = PinsSync(api, { MutableSharedFlow() })
        assertNull(sync.save("c1", "  Inbox   triage ", " 📮 "))
        assertEquals(listOf("put c1 Inbox triage|📮"), api.calls)
        assertEquals(listOf("c1"), sync.pins.value!!.map { it.convoID })
        assertEquals(4, sync.limit.value)
    }

    @Test
    fun aRefusedWriteResolvesToItsWordsAndChangesNothing() = runBlocking {
        val api = FakePinsApi()
        api.failure = PinRequestError(409, "pin_limit", 5)
        val sync = PinsSync(api, { MutableSharedFlow() })
        assertEquals("You can pin up to 5 chats.", sync.save("c6", "Sixth", ""))
        api.failure = PinRequestError(409, "already_pinned", null)
        assertEquals("That chat is already pinned.", sync.move("c1", "c2"))
        api.failure = JournalApiError.Transport("")
        assertEquals("Couldn't reach the server.", sync.remove("c1"))
        assertNull(sync.pins.value)
    }

    @Test
    fun shiftSendsTheWholeOrderAndIgnoresAnEnd() = runBlocking {
        val api = FakePinsApi()
        val feed = MutableSharedFlow<List<ConvoPin>?>(replay = 1)
        val sync = PinsSync(api, { feed })
        sync.start()
        feed.emit(listOf(pin("a", 0), pin("b", 1), pin("c", 2)))
        waitUntil { sync.pins.value != null }
        api.answer = PinList(listOf(pin("b", 0), pin("a", 1), pin("c", 2)), 5)
        assertNull(sync.shift("b", Pins.Direction.UP))
        assertEquals(listOf("reorder b,a,c"), api.calls)
        assertEquals(listOf("b", "a", "c"), sync.pins.value!!.map { it.convoID })
        assertNull("the top pin cannot move up: no request", sync.shift("b", Pins.Direction.UP))
        assertEquals(1, api.calls.size)
        sync.stop()
    }

    @Test
    fun successorAcceptMovesAndDismissPosts() = runBlocking {
        val api = FakePinsApi()
        val sync = PinsSync(api, { MutableSharedFlow() })
        val offered = pin("c1", 0).copy(successor = ConvoPinSuccessor("c9", "Next", 2))
        sync.acceptSuccessor(offered)
        sync.dismissSuccessor(offered)
        sync.acceptSuccessor(pin("c2", 1))
        assertEquals(listOf("move c1 c9", "dismiss c1 c9"), api.calls)
    }

    @Test
    fun aStoppedSyncAdoptsNothing() = runBlocking {
        val store = InMemoryKeyValueStore()
        val api = FakePinsApi()
        api.answer = PinList(listOf(pin("a", 0)), 5)
        val sync = PinsSync(api, { MutableSharedFlow() }, store, "u1")
        sync.start()
        sync.stop()
        sync.refresh()
        assertNull(sync.pins.value)
        assertNull(store.getString(PinsSync.cacheKey("u1")))
    }

    @Test
    fun refreshOn404ReadsAsNoPins() = runBlocking {
        val api = FakePinsApi()
        val sync = PinsSync(api, { MutableSharedFlow() })
        api.answer = PinList(listOf(pin("a", 0)), 3)
        sync.refresh()
        assertEquals(3, sync.limit.value)
        api.failure = JournalApiError.NotFound
        sync.refresh()
        assertNull(sync.pins.value)
    }
}
