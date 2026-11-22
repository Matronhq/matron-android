package chat.matron.android.viewmodels

import chat.matron.android.models.MissionRoom
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRoomsViewModelTest {
    private fun room(id: String, participants: List<String>, at: Long) =
        MissionRoom(id, "Room $id", "waiting", Instant.ofEpochMilli(at), participants)

    @Test
    fun followsTheConversationsOwnRoomsAndStopsOnTheCurrentGenerationOnly() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val source = MutableStateFlow(listOf(room("r1", listOf("c-a", "c-b"), 1), room("r2", listOf("c-b"), 2)))
        val vm = ConversationRoomsViewModel(convoID = "c-a", source = { source }, scope = scope)
        vm.start()
        val first = vm.observationGeneration
        scope.advanceUntilIdle()
        assertEquals(listOf("r1"), vm.rooms.value.map { it.id })

        source.value = source.value + room("r3", listOf("c-a"), 5)
        scope.advanceUntilIdle()
        assertEquals(listOf("r3", "r1"), vm.rooms.value.map { it.id })

        // A successor started; the stale screen's stop must not end it.
        vm.start()
        vm.stop(first)
        source.value = source.value + room("r4", listOf("c-a"), 9)
        scope.advanceUntilIdle()
        assertEquals(listOf("r4", "r3", "r1"), vm.rooms.value.map { it.id })

        vm.stop(vm.observationGeneration)
        source.value = emptyList()
        scope.advanceUntilIdle()
        assertTrue("stopped: the last list stays", vm.rooms.value.isNotEmpty())
    }
}
