package chat.matron.android.chat

import chat.matron.android.models.BotIdentity
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private val bot = BotIdentity(matrixID = "@bot:s", displayName = "Bot", avatarURL = null)
private fun summary(id: String) = ChatSummary(id, "T", bot, Instant.now(), 0)

/// Test double for [ChatService], ported from matron-apple's `FakeChatService`.
class FakeChatService : ChatService {
    var snapshotsToEmit: List<List<ChatSummary>> = emptyList()
    var streamError: Throwable? = null
    val createCalls = mutableListOf<String>()
    var nextCreatedRoomID = "!fake:server"
    var refreshCalls = 0
    var forceSnapshotCalls = 0
    val mutedRooms = mutableListOf<String>()
    val leftRooms = mutableListOf<String>()
    val childrenByParent = mutableMapOf<String, List<List<SubChatSummary>>>()

    override fun chatSummaries(): Flow<List<ChatSummary>> = flow {
        snapshotsToEmit.forEach { emit(it) }
        streamError?.let { throw it }
    }

    override fun children(parentConvoID: String): Flow<List<SubChatSummary>> = flow {
        (childrenByParent[parentConvoID] ?: emptyList()).forEach { emit(it) }
    }

    override suspend fun createChat(botID: String): String {
        createCalls.add(botID)
        return nextCreatedRoomID
    }

    override suspend fun refresh() { refreshCalls++ }
    override suspend fun forceSnapshot() { forceSnapshotCalls++ }
    override suspend fun mute(roomID: String) { mutedRooms.add(roomID) }
    override suspend fun leave(roomID: String) { leftRooms.add(roomID) }
}

/// Yields a single `[]` and never finishes — a room-less account whose sync
/// stream stays open with no diffs. Pins `firstSnapshotRoomIDs`'s timeout bound.
private class HangingEmptyChatService : ChatService {
    override fun chatSummaries(): Flow<List<ChatSummary>> = flow {
        emit(emptyList())
        awaitCancellation()
    }
    override fun children(parentConvoID: String): Flow<List<SubChatSummary>> = emptyFlow()
    override suspend fun createChat(botID: String): String = "!x:s"
    override suspend fun refresh() {}
    override suspend fun forceSnapshot() {}
    override suspend fun mute(roomID: String) {}
    override suspend fun leave(roomID: String) {}
}

class ChatServiceFakeTest {
    @Test fun emitsSnapshotsInOrder() = runBlocking {
        val fake = FakeChatService()
        fake.snapshotsToEmit = listOf(
            listOf(summary("!1:s")),
            listOf(summary("!1:s"), summary("!2:s")),
        )
        val received = fake.chatSummaries().toList()
        assertEquals(2, received.size)
        assertEquals(1, received[0].size)
        assertEquals(2, received[1].size)
    }

    @Test fun firstSnapshotRoomIDsMapsFirstNonEmptyYield() = runBlocking {
        val fake = FakeChatService()
        fake.snapshotsToEmit = listOf(
            listOf(summary("!1:s"), summary("!2:s")),
            listOf(summary("!3:s")),
        )
        assertEquals(listOf("!1:s", "!2:s"), fake.firstSnapshotRoomIDs())
    }

    @Test fun firstSnapshotRoomIDsWaitsThroughEmptyWarmup() = runBlocking {
        val fake = FakeChatService()
        fake.snapshotsToEmit = listOf(emptyList(), listOf(summary("!1:s")))
        assertEquals(listOf("!1:s"), fake.firstSnapshotRoomIDs())
    }

    @Test fun firstSnapshotRoomIDsEmptyOnImmediateFinish() = runBlocking {
        val fake = FakeChatService()
        fake.snapshotsToEmit = emptyList()
        assertEquals(emptyList<String>(), fake.firstSnapshotRoomIDs())
    }

    @Test fun firstSnapshotRoomIDsEmptyOnStreamError() = runBlocking {
        val fake = FakeChatService()
        fake.streamError = IOException("not connected")
        assertEquals(emptyList<String>(), fake.firstSnapshotRoomIDs())
    }

    @Test fun firstSnapshotRoomIDsTimesOutToEmpty() = runBlocking {
        val fake = HangingEmptyChatService()
        assertEquals(emptyList<String>(), fake.firstSnapshotRoomIDs(timeout = 200.milliseconds))
    }
}
