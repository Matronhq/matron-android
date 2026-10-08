package chat.matron.android.viewmodels

import chat.matron.android.chat.ChatService
import chat.matron.android.chat.ChatSummary
import chat.matron.android.chat.SubChatSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Minimal [ChatService] fake driving `children()` from a shared flow the test
/// controls, so it can push child-list snapshots one at a time. Every other
/// method is inert — the strip VM only touches `children()`. Ported from
/// matron-apple's `FakeChildrenChatService`.
private class FakeChildrenChatService : ChatService {
    // replay = 1 so a successor collector that subscribes just after a push
    // still observes the latest snapshot (the parent's dying subscription can
    // keep subscriptionCount > 0 momentarily, so the test can't rely on the
    // successor being subscribed before pushing).
    private val flow = MutableSharedFlow<List<SubChatSummary>>(replay = 1, extraBufferCapacity = 64)
    var requestedParent: String? = null
        private set

    val subscriptionCount: Int get() = flow.subscriptionCount.value

    suspend fun push(snapshot: List<SubChatSummary>) = flow.emit(snapshot)

    override fun children(parentConvoID: String): Flow<List<SubChatSummary>> {
        requestedParent = parentConvoID
        return flow
    }

    override fun chatSummaries(): Flow<List<ChatSummary>> = emptyFlow()
    override suspend fun createChat(botID: String): String = "!x:s"
    override suspend fun refresh() {}
    override suspend fun forceSnapshot() {}
    override suspend fun mute(roomID: String) {}
    override suspend fun leave(roomID: String) {}
}

class SubChatStripViewModelTest {

    @Test
    fun subscribesToParentAndSplitsRunningFromFinished() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeChildrenChatService()
            val vm = SubChatStripViewModel(fake, "p1", scope)
            vm.start()

            waitUntil { fake.subscriptionCount > 0 }
            assertEquals("p1", fake.requestedParent)

            fake.push(
                listOf(
                    SubChatSummary("p1:sub:a", "explore", isRunning = true),
                    SubChatSummary("p1:sub:b", "test", isRunning = false),
                    SubChatSummary("p1:sub:c", "docs", isRunning = true),
                ),
            )

            waitUntil { vm.children.value.size == 3 }
            assertEquals(listOf("p1:sub:a", "p1:sub:b", "p1:sub:c"), vm.children.value.map { it.id })
            assertEquals(listOf("p1:sub:a", "p1:sub:c"), vm.runningChildren.value.map { it.id })
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun staleSurfaceStopCannotKillSuccessorsStream() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeChildrenChatService()
            val vm = SubChatStripViewModel(fake, "p1", scope)

            vm.start()
            val parentGeneration = vm.observationGeneration
            waitUntil { fake.subscriptionCount > 0 }

            // Push nav: the successor starts (new generation) before the parent's
            // teardown fires with the old generation.
            vm.start()
            waitUntil { fake.subscriptionCount > 0 }
            vm.stop(parentGeneration) // stale generation → no-op

            fake.push(listOf(SubChatSummary("p1:sub:a", "explore", isRunning = true)))
            waitUntil { vm.runningChildren.value.size == 1 }
            assertEquals(listOf("p1:sub:a"), vm.runningChildren.value.map { it.id })

            // The current surface's matching-generation stop cancels the stream.
            vm.stop(vm.observationGeneration)
            fake.push(emptyList())
            waitUntil(100) { false }
            assertEquals(1, vm.runningChildren.value.size)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun stripEmptiesWhenAllChildrenFinish() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeChildrenChatService()
            val vm = SubChatStripViewModel(fake, "p1", scope)
            vm.start()
            waitUntil { fake.subscriptionCount > 0 }

            fake.push(listOf(SubChatSummary("p1:sub:a", "explore", isRunning = true)))
            waitUntil { vm.runningChildren.value.size == 1 }
            assertEquals("p1:sub:a", vm.soleRunningChild?.id)

            fake.push(listOf(SubChatSummary("p1:sub:a", "explore", isRunning = false)))
            waitUntil { vm.runningChildren.value.isEmpty() }
            assertTrue(vm.runningChildren.value.isEmpty())
            assertEquals(1, vm.children.value.size)
            assertNull(vm.soleRunningChild)
        } finally {
            scope.cancel()
        }
        Unit
    }

    // MARK: - subtask-message linking (pure helpers)

    @Test
    fun subtaskDescription_parsesBridgeIndicatorText() {
        assertEquals(
            "Test sub-chat plumbing",
            SubChatStripViewModel.subtaskDescription("🔀 Subtask: Test sub-chat plumbing"),
        )
        assertEquals("padded", SubChatStripViewModel.subtaskDescription("  🔀 Subtask: padded  \n"))
    }

    @Test
    fun subtaskDescription_rejectsNonSubtaskBodies() {
        assertNull(SubChatStripViewModel.subtaskDescription("plain message"))
        assertNull(SubChatStripViewModel.subtaskDescription("🔀 Subtask: "))
        assertNull(SubChatStripViewModel.subtaskDescription("prefix 🔀 Subtask: x"))
    }

    @Test
    fun resolveSubtaskTarget_matchesTitleExactly() {
        val children = listOf(
            SubChatSummary("p:sub:a", "explore", isRunning = false),
            SubChatSummary("p:sub:b", "test suites", isRunning = false),
        )
        assertEquals("p:sub:b", SubChatStripViewModel.resolveSubtaskTarget("test suites", children)?.id)
        assertNull(SubChatStripViewModel.resolveSubtaskTarget("unknown", children))
    }

    @Test
    fun resolveSubtaskTarget_prefixMatchesTruncatedIndicator() {
        val longTitle = "x".repeat(100)
        val truncated = longTitle.take(80)
        val children = listOf(SubChatSummary("p:sub:long", longTitle, isRunning = false))
        assertEquals("p:sub:long", SubChatStripViewModel.resolveSubtaskTarget(truncated, children)?.id)
    }

    @Test
    fun resolveSubtaskTarget_exactMatchOutranksLongerPrefixMatch() {
        val children = listOf(
            SubChatSummary("p:sub:short", "lint", isRunning = false),
            SubChatSummary("p:sub:long", "linting tool", isRunning = true),
        )
        assertEquals("p:sub:short", SubChatStripViewModel.resolveSubtaskTarget("lint", children)?.id)
    }

    @Test
    fun resolveSubtaskTarget_prefersRunningThenNewestOnDuplicateTitles() {
        val children = listOf(
            SubChatSummary("p:sub:old", "lint", isRunning = false),
            SubChatSummary("p:sub:mid", "lint", isRunning = true),
            SubChatSummary("p:sub:new", "lint", isRunning = false),
        )
        assertEquals("p:sub:mid", SubChatStripViewModel.resolveSubtaskTarget("lint", children)?.id)

        val allDone = children.map { SubChatSummary(it.id, it.title, isRunning = false) }
        assertEquals("p:sub:new", SubChatStripViewModel.resolveSubtaskTarget("lint", allDone)?.id)
    }

    @Test
    fun pathReplacingCurrentChild_swapsStackTail() {
        assertEquals(
            listOf("p:sub:b"),
            SubChatStripViewModel.pathReplacingCurrentChild(listOf("p:sub:a"), "p:sub:a", "p:sub:b"),
        )
    }

    @Test
    fun pathReplacingCurrentChild_sameChildIsNoOp() {
        assertNull(SubChatStripViewModel.pathReplacingCurrentChild(listOf("p:sub:a"), "p:sub:a", "p:sub:a"))
    }

    @Test
    fun pathReplacingCurrentChild_unexpectedTailFallsBackToPush() {
        assertEquals(
            listOf("other", "p:sub:b"),
            SubChatStripViewModel.pathReplacingCurrentChild(listOf("other"), "p:sub:a", "p:sub:b"),
        )
    }

    /// apple #167: the probe skips leading whitespace without copying the
    /// body, and still tolerates it on both sides of the indicator.
    @Test
    fun subtaskDescription_toleratesSurroundingWhitespace_withoutFullTrim() {
        val prefix = "🔀 Subtask: "
        assertEquals("fix the build", SubChatStripViewModel.subtaskDescription("  \n$prefix fix the build  \n"))
        assertEquals("fix the build", SubChatStripViewModel.subtaskDescription("$prefix fix the build"))
        assertNull(SubChatStripViewModel.subtaskDescription("   "))
        assertNull(SubChatStripViewModel.subtaskDescription("plain message $prefix nope"))
    }
}
