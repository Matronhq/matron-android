package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.MemoriesRefreshOutcome
import chat.matron.android.journal.MemoriesSyncing
import chat.matron.android.journal.MemorySave
import chat.matron.android.journal.MemoryWrite
import chat.matron.android.models.Memory
import chat.matron.android.models.MemoryType
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun memory(name: String, description: String = "d") = Memory(
    id = "me_$name", name = name, type = MemoryType.FEEDBACK, description = description,
    createdAt = Instant.ofEpochSecond(1), updatedAt = Instant.ofEpochSecond(2),
)

private class FakeMemoriesSync : MemoriesSyncing {
    override val memories = MutableStateFlow<List<Memory>>(emptyList())
    override val isSupported = MutableStateFlow<Boolean?>(null)
    var refreshes = 0
    var refreshOutcome: MemoriesRefreshOutcome = MemoriesRefreshOutcome.Succeeded
    val saves = mutableListOf<Pair<String, MemoryWrite>>()
    val deletes = mutableListOf<String>()
    var saveError: Throwable? = null

    override suspend fun refresh(): MemoriesRefreshOutcome { refreshes += 1; return refreshOutcome }
    override suspend fun save(name: String, write: MemoryWrite): MemorySave {
        saves += name to write
        saveError?.let { throw it }
        return MemorySave(memory(name, write.description), created = true)
    }
    override suspend fun delete(name: String): Memory { deletes += name; return memory(name) }
}

class MemoriesViewModelTest {
    private suspend fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) delay(10)
        assertTrue("condition not met before timeout", cond())
    }

    @Test
    fun listStartsWithARefreshAndMirrorsTheSync() = runBlocking {
        val sync = FakeMemoriesSync()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vm = MemoriesListViewModel(sync, scope)
        vm.start()
        waitUntil { sync.refreshes == 1 }
        sync.memories.value = listOf(memory("a"))
        sync.isSupported.value = true
        waitUntil { vm.memories.value.size == 1 && vm.isSupported.value == true }
        vm.stop(); scope.cancel()
    }

    @Test
    fun aFailedRefreshShowsAndClears() = runBlocking {
        val sync = FakeMemoriesSync()
        val vm = MemoriesListViewModel(sync, CoroutineScope(Dispatchers.Default + SupervisorJob()))
        sync.refreshOutcome = MemoriesRefreshOutcome.Failed("offline")
        vm.refresh()
        assertEquals("offline", vm.error.value)
        sync.refreshOutcome = MemoriesRefreshOutcome.Unsupported
        vm.refresh()
        assertEquals("unsupported keeps the banner untouched", "offline", vm.error.value)
        sync.refreshOutcome = MemoriesRefreshOutcome.Succeeded
        vm.refresh()
        assertNull(vm.error.value)
        assertFalse(vm.isRefreshing.value)
    }

    @Test
    fun editorRefusesBadFieldsAndDuplicateNamesWithoutWriting() = runBlocking {
        val sync = FakeMemoriesSync()
        sync.memories.value = listOf(memory("taken"))
        val vm = MemoryEditorViewModel(name = null, sync = sync, scope = CoroutineScope(Dispatchers.Default + SupervisorJob()))
        assertTrue(vm.isNew)
        assertFalse(vm.save("Bad Name", "d", "", MemoryType.FEEDBACK))
        assertTrue(vm.error.value!!.contains("lowercase"))
        assertFalse(vm.save("taken", "d", "", MemoryType.FEEDBACK))
        assertTrue(vm.error.value!!.contains("already exists"))
        assertTrue(sync.saves.isEmpty())
    }

    @Test
    fun editorSavesTheWholeMemoryTrimmedAndReportsAJournalError() = runBlocking {
        val sync = FakeMemoriesSync()
        val vm = MemoryEditorViewModel(name = null, sync = sync, scope = CoroutineScope(Dispatchers.Default + SupervisorJob()))
        assertTrue(vm.save(" new-rule ", "  Do it.  ", "**Why:** x", MemoryType.USER))
        assertEquals(listOf("new-rule" to MemoryWrite("Do it.", body = "**Why:** x", type = MemoryType.USER)), sync.saves)
        assertNull(vm.error.value)
        sync.saveError = JournalApiError.Conflict
        assertFalse(vm.save("another", "d", "", MemoryType.FEEDBACK))
        assertEquals(JournalApiError.Conflict.message, vm.error.value)
        assertFalse(vm.isBusy.value)
    }

    @Test
    fun editorTracksTheStoredRowAndDeletes() = runBlocking {
        val sync = FakeMemoriesSync()
        sync.memories.value = listOf(memory("keep"), memory("gone"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vm = MemoryEditorViewModel(name = "gone", sync = sync, scope = scope)
        vm.start()
        waitUntil { vm.existing.value?.name == "gone" }
        // An existing memory's save is an update: a name already in the
        // list is expected, not a duplicate.
        assertTrue(vm.save("gone", "changed", "", MemoryType.PROJECT))
        assertTrue(vm.delete())
        assertEquals(listOf("gone"), sync.deletes)
        sync.memories.value = listOf(memory("keep"))
        waitUntil { vm.existing.value == null }
        vm.stop(); scope.cancel()
    }
}
