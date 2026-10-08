package chat.matron.android.journal

import chat.matron.android.viewmodels.CoordinatorSetting
import chat.matron.android.viewmodels.InMemoryKeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/// [CoordinatorSync]: the cached Coordinator follows the journal (hello /
/// snapshot answers and live `coordinator` events), and picks go through
/// `PUT /coordinator`. Ported from matron-apple's `CoordinatorSyncTests`.
class CoordinatorSyncTest {
    private class FakeCoordinatorApi : CoordinatorProviding {
        var journal: String? = null
        var getFailure: Throwable? = null
        var putFailure: Throwable? = null
        var getGate: CompletableDeferred<Unit>? = null
        var putGate: CompletableDeferred<Unit>? = null
        val puts = mutableListOf<String?>()

        override suspend fun coordinator(): String? {
            getGate?.await()
            getFailure?.let { throw it }
            return journal
        }

        override suspend fun setCoordinator(convoID: String?): String? {
            puts += convoID
            putGate?.await()
            putFailure?.let { throw it }
            journal = convoID
            return convoID
        }
    }

    private val store = InMemoryKeyValueStore()
    private val setting = CoordinatorSetting("u1", store)
    private val api = FakeCoordinatorApi()
    private val feed = MutableSharedFlow<CoordinatorUpdate>(replay = 8)
    private val sync = CoordinatorSync(api, setting, { feed })

    private suspend fun waitUntil(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
    }

    @Test
    fun startAdoptsTheJournalsCoordinator() = runBlocking {
        setting.set("stale")
        setting.migrated = true
        api.journal = "c9"
        sync.start()
        waitUntil { setting.convoID.value == "c9" }
        assertEquals("c9", sync.convoID.value)
        assertEquals(true, sync.isSupported.value)
        sync.stop()
    }

    @Test
    fun helloSnapshotsAndLiveEventsDriveTheCache() = runBlocking {
        api.journal = null
        setting.migrated = true
        sync.start()
        feed.emit(CoordinatorUpdate.Snapshot("c1"))
        waitUntil { setting.convoID.value == "c1" }
        feed.emit(CoordinatorUpdate.Released("c1"))
        waitUntil { setting.convoID.value == null }
        assertNull(setting.convoID.value)
        feed.emit(CoordinatorUpdate.Assigned("c2"))
        waitUntil { setting.convoID.value == "c2" }
        feed.emit(CoordinatorUpdate.Released("other"))
        delay(50)
        assertEquals("releasing another chat leaves this one", "c2", setting.convoID.value)
        feed.emit(CoordinatorUpdate.Snapshot(null))
        waitUntil { setting.convoID.value == null }
        assertNull(setting.convoID.value)
        assertTrue(api.puts.isEmpty())
        sync.stop()
    }

    @Test
    fun aPickGoesToTheJournalAndTheCacheFollowsItsAnswer() = runBlocking {
        api.journal = null
        sync.refresh()
        sync.set("c3")
        assertEquals(listOf<String?>("c3"), api.puts)
        assertEquals("c3", setting.convoID.value)
        assertTrue(setting.migrated)
        sync.set(null)
        assertNull(setting.convoID.value)
    }

    @Test
    fun aRefusedPickChangesNothingAndReachesTheCaller() = runBlocking {
        api.journal = "c1"
        sync.refresh()
        assertEquals("c1", setting.convoID.value)
        api.putFailure = JournalApiError.NotFound
        try {
            sync.set("not-mine")
            fail("expected NotFound")
        } catch (_: JournalApiError.NotFound) {
        }
        assertEquals("c1", setting.convoID.value)
    }

    @Test
    fun aPickShowsAtOnceAndARefusalPutsThePreviousValueBack() = runBlocking {
        api.journal = "c1"
        sync.refresh()
        api.putGate = CompletableDeferred()
        val pick = async { runCatching { sync.set("c2") } }
        waitUntil { api.puts.isNotEmpty() }
        assertEquals("the tab and Settings follow the pick before the answer", "c2", setting.convoID.value)
        api.putFailure = JournalApiError.Forbidden
        api.putGate!!.complete(Unit)
        assertEquals(JournalApiError.Forbidden, pick.await().exceptionOrNull())
        assertEquals("c1", setting.convoID.value)
    }

    @Test
    fun aRefusalDoesNotUndoALiveUpdateThatLandedMeanwhile() = runBlocking {
        api.journal = "c1"
        sync.start()
        waitUntil { setting.convoID.value == "c1" }
        api.putGate = CompletableDeferred()
        api.putFailure = JournalApiError.Forbidden
        val pick = async { runCatching { sync.set("c2") } }
        waitUntil { api.puts.isNotEmpty() }
        feed.emit(CoordinatorUpdate.Assigned("c7"))
        waitUntil { setting.convoID.value == "c7" }
        api.putGate!!.complete(Unit)
        assertTrue(pick.await().isFailure)
        assertEquals("c7", setting.convoID.value)
        sync.stop()
    }

    @Test
    fun anOlderJournalKeepsTheSettingLocal() = runBlocking {
        api.getFailure = JournalApiError.NotFound
        sync.refresh()
        assertEquals(false, sync.isSupported.value)
        sync.set("c4")
        assertTrue("no PUT against a journal without the route", api.puts.isEmpty())
        assertEquals("c4", setting.convoID.value)
    }

    @Test
    fun aPreUpgradePickIsPushedOnce() = runBlocking {
        setting.set("local")
        api.journal = null
        sync.refresh()
        assertEquals(listOf<String?>("local"), api.puts)
        assertEquals("local", setting.convoID.value)
        assertTrue(setting.migrated)
        // Cleared elsewhere afterwards: the journal's null wins from now on.
        api.journal = null
        sync.refresh()
        assertEquals(1, api.puts.size)
    }

    @Test
    fun aPushOfAChatThatIsGoneClearsTheCache() = runBlocking {
        setting.set("gone")
        api.journal = null
        api.putFailure = JournalApiError.NotFound
        sync.refresh()
        assertNull(setting.convoID.value)
        assertTrue(setting.migrated)
    }

    @Test
    fun aGetAnswerOvertakenByALiveUpdateIsDropped() = runBlocking {
        setting.migrated = true
        api.journal = "old"
        api.getGate = CompletableDeferred()
        sync.start()
        val refresh = async { sync.refresh() }
        delay(50)
        feed.emit(CoordinatorUpdate.Assigned("new"))
        waitUntil { setting.convoID.value == "new" }
        api.getGate!!.complete(Unit)
        refresh.await()
        delay(50)
        assertEquals("new", setting.convoID.value)
        assertFalse(api.puts.isNotEmpty())
        sync.stop()
    }
}
