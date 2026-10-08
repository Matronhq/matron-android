package chat.matron.android.viewmodels

import chat.matron.android.chat.ChatRecencyGroup
import chat.matron.android.chat.ChatService
import chat.matron.android.chat.ChatSummary
import chat.matron.android.chat.SubChatSummary
import chat.matron.android.models.BotIdentity
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Fake mirroring the long-lived chat-list streaming surface: one stream that
/// yields every queued snapshot in order, stays open on success (so
/// `forceSnapshot` can drive extra yields), and rethrows [streamError] if set.
/// Ported from matron-apple's `FakeStreamingChatService`.
private class FakeStreamingChatService : ChatService {
    var snapshotsToEmit: MutableList<List<ChatSummary>> = mutableListOf()
    var streamError: Throwable? = null
    var callCount = 0
        private set
    var forceSnapshotCalls = 0
        private set

    private val extra = MutableSharedFlow<List<ChatSummary>>(extraBufferCapacity = 64)

    override fun chatSummaries(): Flow<List<ChatSummary>> {
        callCount++
        val queued = snapshotsToEmit.toList()
        snapshotsToEmit.clear()
        val err = streamError
        return flow {
            queued.forEach { emit(it) }
            if (err != null) throw err
            // Stay open so later forceSnapshot() yields flow through the same
            // stream (mirrors the live broadcaster's multi-yield shape).
            extra.collect { emit(it) }
        }
    }

    override suspend fun forceSnapshot() {
        forceSnapshotCalls++
        if (snapshotsToEmit.isEmpty()) return
        extra.emit(snapshotsToEmit.removeAt(0))
    }

    override fun children(parentConvoID: String): Flow<List<SubChatSummary>> = emptyFlow()
    override suspend fun createChat(botID: String): String = "!stub:server"
    override suspend fun refresh() {}
    override suspend fun mute(roomID: String) {}
    override suspend fun leave(roomID: String) {}
}

class ChatListViewModelTest {
    private val bot = BotIdentity(matrixID = "@b:s", displayName = "Bot", avatarURL = null)

    private fun summary(id: String, title: String, lastActivity: Instant?, unread: Int = 0) =
        ChatSummary(id = id, title = title, bot = bot, lastActivity = lastActivity, unreadCount = unread)

    @Test
    fun groupsSummariesByRecency() {
        val now = Instant.ofEpochSecond(1_745_000_000)
        val summaries = listOf(
            summary("!t:s", "Today chat", now.minusSeconds(3600)),
            summary("!y:s", "Yesterday chat", now.minusSeconds(86_400)),
            summary("!w:s", "Earlier chat", now.minusSeconds(86_400 * 30)),
        )
        val groups = ChatListViewModel.group(summaries, now = now)
        assertEquals(ChatRecencyGroup.TODAY, groups.first().group)
        assertEquals(1, groups.first().summaries.size)
        assertEquals(ChatRecencyGroup.EARLIER, groups.last().group)
    }

    @Test
    fun emptyState_isReflected() {
        assertTrue(ChatListViewModel.group(emptyList()).isEmpty())
    }

    @Test
    fun upstreamStreamError_populatesErrorField() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeStreamingChatService()
            fake.streamError = RuntimeException("sliding sync timed out")
            val vm = ChatListViewModel(fake, scope)
            vm.start()
            waitUntil { vm.error.value != null }
            assertEquals("sliding sync timed out", vm.error.value)
            assertFalse(vm.isLoading.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun consumesMultipleYieldsThroughSingleStream() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeStreamingChatService()
            fake.snapshotsToEmit = mutableListOf(
                emptyList(),
                listOf(summary("!1:s", "ok", Instant.now())),
            )
            val vm = ChatListViewModel(fake, scope)
            vm.start()
            waitUntil { vm.groups.value.isNotEmpty() }
            assertTrue(vm.groups.value.isNotEmpty())
            assertEquals(1, fake.callCount)
            assertNull(vm.error.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun refresh_drivesForceSnapshot_andUpdatesGroups() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeStreamingChatService()
            val initial = listOf(summary("!1:s", "first", Instant.now()))
            val refreshed = listOf(
                summary("!1:s", "first", Instant.now()),
                summary("!2:s", "second", Instant.now()),
            )
            fake.snapshotsToEmit = mutableListOf(initial)
            val vm = ChatListViewModel(fake, scope)
            vm.start()
            waitUntil { vm.groups.value.isNotEmpty() }
            assertEquals(1, vm.groups.value.flatMap { it.summaries }.size)

            fake.snapshotsToEmit = mutableListOf(refreshed)
            vm.refresh()
            assertEquals(1, fake.forceSnapshotCalls)
            waitUntil { vm.groups.value.flatMap { it.summaries }.size >= 2 }
            assertEquals(2, vm.groups.value.flatMap { it.summaries }.size)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun totalUnread_isSumOfUnreadCounts_acrossLatestSnapshot() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeStreamingChatService()
            fake.snapshotsToEmit = mutableListOf(
                listOf(
                    summary("!a:s", "A", Instant.now(), unread = 3),
                    summary("!b:s", "B", Instant.now(), unread = 0),
                    summary("!c:s", "C", Instant.now(), unread = 7),
                ),
            )
            val vm = ChatListViewModel(fake, scope)
            vm.start()
            waitUntil { vm.groups.value.isNotEmpty() }
            assertEquals(10, vm.totalUnread.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun totalUnread_updatesWithEachSnapshot() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeStreamingChatService()
            fake.snapshotsToEmit = mutableListOf(
                listOf(summary("!a:s", "A", Instant.now(), unread = 5)),
                listOf(summary("!a:s", "A", Instant.now(), unread = 0)),
            )
            val vm = ChatListViewModel(fake, scope)
            vm.start()
            waitUntil { vm.totalUnread.value == 0 && vm.groups.value.isNotEmpty() }
            assertEquals(0, vm.totalUnread.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun totalUnread_isZero_forEmptySnapshot() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeStreamingChatService()
            fake.snapshotsToEmit = mutableListOf(emptyList())
            val vm = ChatListViewModel(fake, scope)
            vm.start()
            waitUntil { !vm.isLoading.value }
            assertEquals(0, vm.totalUnread.value)
        } finally {
            scope.cancel()
        }
        Unit
    }

    @Test
    fun successfulSnapshot_clearsPriorError() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val fake = FakeStreamingChatService()
            fake.snapshotsToEmit = mutableListOf(listOf(summary("!1:s", "ok", Instant.now())))
            val vm = ChatListViewModel(fake, scope)
            vm.start()
            waitUntil { vm.groups.value.isNotEmpty() }
            assertNull(vm.error.value)
            assertFalse(vm.groups.value.isEmpty())
        } finally {
            scope.cancel()
        }
        Unit
    }
}
