package chat.matron.android.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/// Drives the chat-list actions (`refresh`/`mute`/`leave`) against
/// [FakeChatServiceForCreate], pinned to the `ChatService` type so protocol-
/// surface regressions are caught here. Ported from matron-apple's
/// `ChatActionsTests`.
class ChatActionsTest {
    @Test fun refreshRecordsCall() = runBlocking {
        val fake = FakeChatServiceForCreate()
        val service: ChatService = fake
        service.refresh()
        assertEquals(1, fake.refreshCalls)
    }

    @Test fun muteRecordsRoomID() = runBlocking {
        val fake = FakeChatServiceForCreate()
        val service: ChatService = fake
        service.mute("!a:s")
        assertEquals(listOf("!a:s"), fake.mutedRooms)
    }

    @Test fun leaveRecordsRoomID() = runBlocking {
        val fake = FakeChatServiceForCreate()
        val service: ChatService = fake
        service.leave("!a:s")
        assertEquals(listOf("!a:s"), fake.leftRooms)
    }

    @Test fun refreshIsCountedAcrossMultipleCalls() = runBlocking {
        val fake = FakeChatServiceForCreate()
        val service: ChatService = fake
        service.refresh()
        service.refresh()
        service.refresh()
        assertEquals(3, fake.refreshCalls)
    }
}
