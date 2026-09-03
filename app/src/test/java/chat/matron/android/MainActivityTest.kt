package chat.matron.android

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/// [openConversationCallback] is the non-Compose seam behind the agent-spawn
/// card / `SpawnOutcomeRow` "Open" action's nav wiring (Task 3 of the
/// agent-spawn-card plan): the deep link a `started` outcome's room id
/// resolves to. The Compose threading that carries it from the nav host down
/// through `ChatScreen`/`SubChatView`/`TimelineItemView` isn't unit-testable
/// without Compose, but the effect ordering it produces is — this covers the
/// [NewChatSheet] precedent it copies: `prepareConversation` completes fully
/// BEFORE `navigate` fires, so a navigation into `chat/$roomId` never beats
/// the placeholder row it depends on.
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityTest {

    @Test
    fun preparesTheConversationBeforeNavigating() = runTest {
        val calls = mutableListOf<String>()
        val callback = openConversationCallback(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            prepareConversation = { roomId -> calls += "prepare:$roomId" },
            navigate = { roomId -> calls += "navigate:$roomId" },
        )

        callback("room-9")
        advanceUntilIdle()

        assertEquals(listOf("prepare:room-9", "navigate:room-9"), calls)
    }

    @Test
    fun navigateNeverFiresWhenPrepareConversationThrows() = runTest {
        var navigated = false
        var caught: Throwable? = null
        val handler = CoroutineExceptionHandler { _, error -> caught = error }
        val callback = openConversationCallback(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + handler),
            prepareConversation = { throw IllegalStateException("boom") },
            navigate = { navigated = true },
        )

        callback("room-9")
        advanceUntilIdle()

        assertFalse(navigated)
        assertEquals("boom", caught?.message)
    }

    @Test
    fun eachCallCarriesItsOwnRoomId() = runTest {
        val navigated = mutableListOf<String>()
        val callback = openConversationCallback(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            prepareConversation = { },
            navigate = { roomId -> navigated += roomId },
        )

        callback("room-1")
        callback("room-2")
        advanceUntilIdle()

        assertEquals(listOf("room-1", "room-2"), navigated)
    }
}
