package chat.matron.android.viewmodels

import chat.matron.android.events.RoutineMarkerEvent
import chat.matron.android.journal.RoutineRunResult
import chat.matron.android.journal.RoutinesError
import chat.matron.android.journal.RoutinesProviding
import chat.matron.android.models.Routine
import chat.matron.android.models.RoutineDraft
import chat.matron.android.models.RoutineTrigger
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun routine(name: String, enabled: Boolean = true) = Routine(
    id = "rt_$name", name = name, title = name.replaceFirstChar { it.uppercase() },
    schedule = "0 9 * * *", prompt = "p", enabled = enabled,
    createdAt = Instant.ofEpochSecond(1), updatedAt = Instant.ofEpochSecond(2),
)

private class FakeRoutinesApi : RoutinesProviding {
    var list: List<Routine> = emptyList()
    var listError: Exception? = null
    var lists = 0
    var writeError: Exception? = null
    val created = mutableListOf<Pair<String, RoutineDraft>>()
    val deleted = mutableListOf<String>()
    var runResult: RoutineRunResult = RoutineRunResult.Accepted

    override suspend fun listRoutines(): List<Routine> { lists += 1; listError?.let { throw it }; return list }
    override suspend fun createRoutine(name: String, draft: RoutineDraft): Routine {
        writeError?.let { throw it }
        created += name to draft
        return routine(name)
    }
    override suspend fun updateRoutine(id: String, draft: RoutineDraft): Routine {
        writeError?.let { throw it }
        return list.first { it.id == id }.copy(title = draft.title)
    }
    override suspend fun setRoutineEnabled(id: String, enabled: Boolean): Routine {
        writeError?.let { throw it }
        return list.first { it.id == id }.copy(enabled = enabled)
    }
    override suspend fun deleteRoutine(id: String) { writeError?.let { throw it }; deleted += id }
    override suspend fun runRoutine(id: String): RoutineRunResult { writeError?.let { throw it }; return runResult }
}

class RoutinesViewModelTest {
    private val marker = RoutineMarkerEvent("rt_a", "a", RoutineMarkerEvent.Action.FIRED, outcome = "applied now")

    private fun CoroutineScope.child() = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))

    @Test
    fun startLoadsSortedByName() = runBlocking {
        val api = FakeRoutinesApi().apply { list = listOf(routine("b"), routine("a")) }
        val scope = child()
        val vm = RoutinesViewModel(api, { MutableSharedFlow() }, scope)
        vm.start()
        waitUntil { vm.routines.value != null }
        assertEquals(listOf("a", "b"), vm.routines.value!!.map { it.name })
        assertEquals(true, vm.isSupported.value)
        assertFalse(vm.isLoading.value)
        vm.stop(); scope.cancel()
    }

    @Test
    fun aJournalWithoutRoutinesIsUnsupportedNotAnError() = runBlocking {
        val api = FakeRoutinesApi().apply { listError = RoutinesError.Unsupported }
        val vm = RoutinesViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        assertEquals(false, vm.isSupported.value)
        assertNull(vm.loadError.value)
        assertEquals(RoutinesError.Unsupported.message, vm.formError(true, "x", RoutineDraft("t", "p", "UTC", schedule = "0 9 * * *")))
    }

    @Test
    fun aFailedLoadKeepsTheList() = runBlocking {
        val api = FakeRoutinesApi().apply { list = listOf(routine("a")) }
        val vm = RoutinesViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        api.listError = RoutinesError.Other("offline")
        vm.load()
        assertEquals("offline", vm.loadError.value)
        assertEquals(1, vm.routines.value!!.size)
    }

    @Test
    fun aBurstOfMarkersCostsOneRefetch() = runBlocking {
        val api = FakeRoutinesApi()
        val markers = MutableSharedFlow<RoutineMarkerEvent>(extraBufferCapacity = 8)
        val scope = child()
        val vm = RoutinesViewModel(api, { markers }, scope, refetchDelayMs = 50)
        vm.start()
        waitUntil { api.lists == 1 }
        waitUntil { markers.subscriptionCount.value == 1 }
        repeat(5) { markers.emit(marker) }
        waitUntil(timeoutMs = 1000) { api.lists == 2 }
        kotlinx.coroutines.delay(150)
        assertEquals(2, api.lists)
        vm.stop()
        markers.emit(marker)
        kotlinx.coroutines.delay(150)
        assertEquals(2, api.lists)
        scope.cancel()
    }

    @Test
    fun pauseReplacesTheRowAndAFailureBecomesTheActionMessage() = runBlocking {
        val api = FakeRoutinesApi().apply { list = listOf(routine("a")) }
        val vm = RoutinesViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        vm.setEnabled(false, "rt_a")
        assertFalse(vm.routine("rt_a")!!.enabled)
        assertTrue(vm.busyIDs.value.isEmpty())
        api.writeError = RoutinesError.ApprovalRequired
        vm.setEnabled(true, "rt_a")
        assertEquals(RoutinesError.ApprovalRequired.message, vm.actionMessage.value)
        vm.dismissActionMessage()
        assertNull(vm.actionMessage.value)
    }

    @Test
    fun runAnswersWhatToTellTheUser() = runBlocking {
        val api = FakeRoutinesApi().apply { list = listOf(routine("a")) }
        val vm = RoutinesViewModel(api, { MutableSharedFlow() }, this)
        assertNull(vm.run("rt_a"))
        api.runResult = RoutineRunResult.NotDelivered("busy")
        assertTrue(vm.run("rt_a")!!.startsWith("Not sent"))
    }

    @Test
    fun createChecksNamesAndWrites() = runBlocking {
        val api = FakeRoutinesApi().apply { list = listOf(routine("a")) }
        val vm = RoutinesViewModel(api, { MutableSharedFlow() }, this)
        val draft = RoutineDraft("Title", "Prompt", "UTC", trigger = RoutineTrigger.DiskUnder(20), enabled = true)
        // Not loaded yet: save loads first, then checks the name.
        assertTrue(vm.save(null, "a", draft)!!.contains("already exists"))
        assertEquals(1, api.lists)
        assertNull(vm.save(null, "b", draft))
        assertEquals(listOf("a", "b"), vm.routines.value!!.map { it.name })
        assertEquals("b", api.created.single().first)
        api.writeError = RoutinesError.NameTaken
        assertEquals(RoutinesError.NameTaken.message, vm.save(null, "c", draft))
    }

    @Test
    fun editAndDelete() = runBlocking {
        val api = FakeRoutinesApi().apply { list = listOf(routine("a"), routine("b")) }
        val vm = RoutinesViewModel(api, { MutableSharedFlow() }, this)
        vm.load()
        assertNull(vm.save("rt_a", "a", RoutineDraft("Renamed", "p", "UTC", schedule = "0 9 * * *")))
        assertEquals("Renamed", vm.routine("rt_a")!!.title)
        assertNull(vm.delete("rt_a"))
        assertEquals(listOf("b"), vm.routines.value!!.map { it.name })
        // Already gone elsewhere counts as deleted.
        api.writeError = RoutinesError.NotFound
        assertNull(vm.delete("rt_b"))
        assertTrue(vm.routines.value!!.isEmpty())
    }
}
