package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.NewChatDefaults
import chat.matron.android.journal.NewChatDefaultsProviding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDefaultsApi : NewChatDefaultsProviding {
    var stored = NewChatDefaults("opus", null)
    var getError: Exception? = null
    var putError: Exception? = null
    val puts = mutableListOf<Pair<NewChatDefaults.Key, String?>>()
    /// When set, each PUT waits for it before answering.
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun newChatDefaults(): NewChatDefaults { getError?.let { throw it }; return stored }
    override suspend fun setNewChatDefault(key: NewChatDefaults.Key, value: String?): NewChatDefaults {
        puts += key to value
        gate?.await()
        putError?.let { throw it }
        stored = stored.with(key, value?.trim()?.lowercase())
        return stored
    }
}

class NewChatDefaultsStoreTest {
    private fun CoroutineScope.child() = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))

    @Test
    fun startReadsAndFollowsLiveFrames() = runBlocking {
        val api = FakeDefaultsApi()
        val frames = MutableSharedFlow<NewChatDefaults>(extraBufferCapacity = 4)
        val scope = child()
        val store = NewChatDefaultsStore(api, { frames }, scope)
        store.start()
        waitUntil { store.defaults.value != null }
        assertEquals(NewChatDefaults("opus", null), store.defaults.value)
        assertEquals(true, store.isSupported.value)
        waitUntil { frames.subscriptionCount.value == 1 }
        frames.emit(NewChatDefaults("haiku", "low"))
        waitUntil { store.defaults.value?.model == "haiku" }
        assertEquals("low", store.defaults.value?.effort)
        store.stop(); scope.cancel()
    }

    @Test
    fun anOlderJournalIsUnsupported() = runBlocking {
        val api = FakeDefaultsApi().apply { getError = JournalApiError.NotFound }
        val store = NewChatDefaultsStore(api, { MutableSharedFlow() }, this)
        store.refresh()
        assertEquals(false, store.isSupported.value)
        assertNull(store.errorMessage.value)
    }

    @Test
    fun aFailedReadSaysSoAndNothingIsPickedBeforeTheFirstRead() = runBlocking {
        val api = FakeDefaultsApi().apply { getError = JournalApiError.Transport("offline") }
        val store = NewChatDefaultsStore(api, { MutableSharedFlow() }, this)
        store.refresh()
        assertTrue(store.errorMessage.value!!.startsWith("Couldn't load the defaults"))
        store.set(NewChatDefaults.Key.MODEL, "sonnet")
        assertTrue(api.puts.isEmpty())
        api.getError = null
        store.refresh()
        assertNull(store.errorMessage.value)
    }

    @Test
    fun aPickShowsAtOnceAndAFailureGoesBackWithItsError() = runBlocking {
        val api = FakeDefaultsApi()
        val scope = child()
        val store = NewChatDefaultsStore(api, { MutableSharedFlow() }, scope)
        store.refresh()
        store.set(NewChatDefaults.Key.EFFORT, "high")
        assertEquals(NewChatDefaults("opus", "high"), store.defaults.value)
        waitUntil { api.stored.effort == "high" }
        api.putError = JournalApiError.Transport("offline")
        store.set(NewChatDefaults.Key.MODEL, "sonnet")
        assertEquals("sonnet", store.defaults.value?.model)
        waitUntil { store.errorMessage.value != null }
        assertEquals("opus", store.defaults.value?.model)
        assertTrue(store.errorMessage.value!!.startsWith("Couldn't save the default model"))
        // The error stays until that key's next pick.
        api.putError = null
        store.set(NewChatDefaults.Key.MODEL, null)
        assertNull(store.errorMessage.value)
        waitUntil { api.stored.model == null }
        assertNull(store.defaults.value?.model)
        scope.cancel()
    }

    @Test
    fun picksWhileAPutIsInFlightAreSentOnceInOrder() = runBlocking {
        val api = FakeDefaultsApi()
        val scope = child()
        val store = NewChatDefaultsStore(api, { MutableSharedFlow() }, scope)
        store.refresh()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        store.set(NewChatDefaults.Key.MODEL, "sonnet")
        waitUntil { api.puts.size == 1 }
        store.set(NewChatDefaults.Key.MODEL, "haiku")
        store.set(NewChatDefaults.Key.MODEL, "fable")
        assertEquals("fable", store.defaults.value?.model)
        api.gate = null
        gate.complete(Unit)
        waitUntil { api.puts.size == 2 && api.stored.model == "fable" }
        assertEquals(listOf<String?>("sonnet", "fable"), api.puts.map { it.second })
        assertEquals("fable", store.defaults.value?.model)
        scope.cancel()
    }

    @Test
    fun aFrameForAKeyBeingSavedIsHeldBackAndTheAnswerWins() = runBlocking {
        val api = FakeDefaultsApi()
        val scope = child()
        val store = NewChatDefaultsStore(api, { MutableSharedFlow() }, scope)
        store.refresh()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        store.set(NewChatDefaults.Key.MODEL, "sonnet")
        waitUntil { api.puts.size == 1 }
        // An older frame lands mid-save: its model is held, its effort applies.
        store.applyLive(NewChatDefaults("opus", "low"))
        assertEquals(NewChatDefaults("sonnet", "low"), store.defaults.value)
        gate.complete(Unit)
        waitUntil { api.stored.model == "sonnet" }
        waitUntil { store.defaults.value == NewChatDefaults("sonnet", "low") }
        assertEquals(NewChatDefaults("sonnet", "low"), store.defaults.value)
        scope.cancel()
    }

    @Test
    fun aRefusedSaveFallsBackToTheHeldFrame() = runBlocking {
        val api = FakeDefaultsApi()
        val scope = child()
        val store = NewChatDefaultsStore(api, { MutableSharedFlow() }, scope)
        store.refresh()
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        api.putError = JournalApiError.Transport("offline")
        store.set(NewChatDefaults.Key.MODEL, "sonnet")
        waitUntil { api.puts.size == 1 }
        store.applyLive(NewChatDefaults("haiku", null))
        gate.complete(Unit)
        waitUntil { store.errorMessage.value != null }
        assertEquals("haiku", store.defaults.value?.model)
        scope.cancel()
    }

    @Test
    fun stoppingDoesNotCancelASaveInFlight() = runBlocking {
        val api = FakeDefaultsApi()
        val scope = child()
        val store = NewChatDefaultsStore(api, { MutableSharedFlow() }, scope)
        store.start()
        waitUntil { store.defaults.value != null }
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        store.set(NewChatDefaults.Key.EFFORT, "max")
        waitUntil { api.puts.size == 1 }
        store.stop()
        gate.complete(Unit)
        waitUntil { api.stored.effort == "max" && store.defaults.value?.effort == "max" }
        assertEquals("max", store.defaults.value?.effort)
        scope.cancel()
    }

    @Test
    fun choicesKeepAValueOutsideTheList() {
        val choices = NewChatDefaultsStore.choices(NewChatDefaults.Key.MODEL, "claude-custom-1")
        assertEquals("claude-custom-1", choices.last().label)
        assertEquals(BoxDefaultsChoices.claudeModelChoices, NewChatDefaultsStore.choices(NewChatDefaults.Key.MODEL, "opus"))
    }
}
