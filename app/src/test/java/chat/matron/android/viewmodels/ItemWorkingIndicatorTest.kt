package chat.matron.android.viewmodels

import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemWorking
import chat.matron.android.models.ItemWorkingIndicator
import chat.matron.android.models.TrackerItem
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// The indicator at the foot of an item's thread while the agent that owns
/// the item is busy, from the item's `working` state.
class ItemWorkingIndicatorTest {
    private val staging: File = Files.createTempDirectory("staging-").toFile()

    private fun item(working: ItemWorking?) =
        TrackerItem(id = "it_1", num = 7, kind = ItemKind.QUESTION, title = "Ship it?", originConvoID = "c-origin", working = working)

    private fun running() = ItemWorking(ItemWorking.State.RUNNING, since = Instant.now())

    @Test
    fun anIdleItemShowsNothing() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging)
        vm.start()
        store.item.emit(item(null))
        waitUntil { vm.item.value != null }
        assertNull(vm.workingIndicator.value)
        vm.stop()
    }

    @Test
    fun aRunningOwnerShowsWorking() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging)
        vm.start()
        store.item.emit(item(running()))
        waitUntil { vm.workingIndicator.value == ItemWorkingIndicator.WORKING }
        vm.stop()
    }

    /// The reply to a tap already carries `queued`: the indicator is up
    /// before the agent's turn has started, and comes down by itself when
    /// nothing picks the reply up.
    @Test
    fun aQueuedReplyShowsStartingUntilItLapses() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging)
        vm.start()
        store.item.emit(item(ItemWorking(ItemWorking.State.QUEUED, Instant.now(), Instant.now().plusMillis(300))))
        waitUntil { vm.workingIndicator.value == ItemWorkingIndicator.STARTING }
        waitUntil { vm.workingIndicator.value == null }
        vm.stop()
    }

    @Test
    fun aQueuedStateAlreadyPastItsDeadlineShowsNothing() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging)
        vm.start()
        store.item.emit(item(ItemWorking(ItemWorking.State.QUEUED, Instant.now().minusSeconds(700), Instant.now().minusSeconds(100))))
        waitUntil { vm.item.value != null }
        assertNull(vm.workingIndicator.value)
        vm.stop()
    }

    /// Another person's item has no owner conversation on this account.
    @Test
    fun aSharedItemNeverShowsTheIndicator() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging)
        vm.start()
        store.item.emit(item(running()).copy(originConvoID = ""))
        waitUntil { vm.item.value != null && vm.loadedCommentCount.value != null }
        assertNull(vm.workingIndicator.value)
        vm.stop()
    }

    /// A copy that says `running` is only as old as its last fetch: a device
    /// with nothing of its own to set it against waits for the opening read.
    @Test
    fun aCachedRunningCopyWaitsForTheOpeningRead() = runBlocking {
        val store = FakeItemsStore()
        val gate = CompletableDeferred<Unit>()
        val sync = object : FakeItemsSync() {
            override suspend fun refreshItem(id: String) { gate.await(); super.refreshItem(id) }
        }
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), sync, this, staging)
        vm.start()
        store.item.emit(item(running()))
        waitUntil { vm.item.value != null }
        assertNull(vm.workingIndicator.value)
        gate.complete(Unit)
        waitUntil { vm.workingIndicator.value == ItemWorkingIndicator.WORKING }
        vm.stop()
    }

    /// A turn starting or ending does not touch the item, so the detail reads
    /// the item again each time the owner's state changes.
    @Test
    fun theItemIsReadAgainWhenTheOwnersTurnStartsOrEnds() = runBlocking {
        val store = FakeItemsStore(); val sync = FakeItemsSync()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), sync, this, staging)
        vm.start()
        store.ownerSession.emit("waiting")
        store.item.emit(item(null))
        waitUntil { vm.item.value != null && sync.refetched.size == 1 }
        delay(50)
        assertEquals("the first value is the state the opening read already saw", 1, sync.refetched.size)

        store.ownerSession.emit("running")
        waitUntil { sync.refetched.size == 2 }
        store.ownerSession.emit("waiting")
        waitUntil { sync.refetched.size == 3 }
        vm.stop()
    }

    /// A turn that ends while the read its start asked for is still open is
    /// seen at once: the next state does not wait behind that read.
    @Test
    fun aTurnEndingIsSeenWhileTheReadItsStartAskedForIsStillOpen() = runBlocking {
        val store = FakeItemsStore()
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val sync = object : FakeItemsSync() {
            override suspend fun refreshItem(id: String) {
                reads += 1
                if (reads > 1) gate.await()
                super.refreshItem(id)
            }
        }
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), sync, this, staging)
        vm.start()
        store.ownerSession.emit("waiting")
        store.item.emit(item(null))
        waitUntil { vm.loadedCommentCount.value != null }
        store.ownerSession.emit("running")
        waitUntil { reads == 2 }
        store.item.emit(item(running()))
        waitUntil { vm.workingIndicator.value == ItemWorkingIndicator.WORKING }

        store.ownerSession.emit("waiting")
        waitUntil { vm.workingIndicator.value == null }
        gate.complete(Unit)
        vm.stop()
    }

    /// A stored copy that still says `running` after this device has seen the
    /// turn end is not shown while the fresh read is on its way.
    @Test
    fun aStaleRunningCopyIsNotShownOnceTheTurnIsKnownToHaveEnded() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging)
        vm.start()
        store.ownerSession.emit("running")
        store.item.emit(item(running()))
        waitUntil { vm.workingIndicator.value == ItemWorkingIndicator.WORKING }

        store.ownerSession.emit("waiting")
        waitUntil { vm.workingIndicator.value == null }
        vm.stop()
    }

    /// A device without the owner conversation has no state of its own to set
    /// against the journal's.
    @Test
    fun withoutTheOwnerConversationTheJournalsStateStands() = runBlocking {
        val store = FakeItemsStore()
        val vm = ItemDetailViewModel("it_1", store, FakeItemsApi(), FakeItemsSync(), this, staging)
        vm.start()
        store.ownerSession.emit(null)
        store.item.emit(item(running()))
        waitUntil { vm.workingIndicator.value == ItemWorkingIndicator.WORKING }
        delay(50)
        assertEquals(ItemWorkingIndicator.WORKING, vm.workingIndicator.value)
        vm.stop()
    }
}
