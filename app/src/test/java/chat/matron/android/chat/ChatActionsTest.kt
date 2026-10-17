package chat.matron.android.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/// Drives the chat-list actions (`refresh`/`mute`/`setArchived`) against
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

    @Test fun archiveRecordsRoomID() = runBlocking {
        val fake = FakeChatServiceForCreate()
        val service: ChatService = fake
        service.setArchived(listOf("!a:s"), archived = true)
        assertEquals(listOf("!a:s"), fake.archivedRooms)
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
