package chat.matron.android.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/// Test double adding the `createChat`/chat-list-action recording surface,
/// ported from matron-apple's `FakeChatServiceForCreate`. Shared with
/// [ChatActionsTest]. Reused by the view-model stage.
class FakeChatServiceForCreate : ChatService {
    val createdWith = mutableListOf<String>()
    var nextRoomID = "!new:server"
    var refreshCalls = 0
    var forceSnapshotCalls = 0
    val mutedRooms = mutableListOf<String>()
    val leftRooms = mutableListOf<String>()

    override fun chatSummaries(): Flow<List<ChatSummary>> = emptyFlow()
    override fun children(parentConvoID: String): Flow<List<SubChatSummary>> = emptyFlow()

    override suspend fun createChat(botID: String): String {
        createdWith.add(botID)
        return nextRoomID
    }

    override suspend fun refresh() { refreshCalls++ }
    override suspend fun forceSnapshot() { forceSnapshotCalls++ }
    override suspend fun mute(roomID: String) { mutedRooms.add(roomID) }
    override suspend fun leave(roomID: String) { leftRooms.add(roomID) }
}

class CreateChatTest {
    @Test fun recordsBotIDAndReturnsRoomID() = runBlocking {
        val fake = FakeChatServiceForCreate()
        val service: ChatService = fake
        assertEquals("!new:server", service.createChat("@bot:s"))
        assertEquals(listOf("@bot:s"), fake.createdWith)
    }

    @Test fun recordsMultipleCallsInOrder() = runBlocking {
        val fake = FakeChatServiceForCreate()
        val service: ChatService = fake
        service.createChat("@one:s")
        service.createChat("@two:s")
        assertEquals(listOf("@one:s", "@two:s"), fake.createdWith)
    }
}
