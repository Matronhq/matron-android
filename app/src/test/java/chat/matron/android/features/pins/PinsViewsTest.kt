package chat.matron.android.features.pins

import chat.matron.android.chat.ChatSummary
import chat.matron.android.journal.ConvoPin
import chat.matron.android.journal.Pins
import chat.matron.android.models.BotIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The Pinned section's rules: position order, the missing entry, the ids
/// the ordinary list leaves out, and the chat header's pin menu.
class PinsViewsTest {
    private val bot = BotIdentity(matrixID = "@b:s", displayName = "Bot", avatarURL = null)

    private fun summary(id: String) = ChatSummary(id = id, title = "Chat $id", bot = bot, lastActivity = null, unreadCount = 0)

    private fun pin(id: String, position: Int, missing: Boolean = false) =
        ConvoPin(id, "Desk $id", "", position, 7L, missing = missing)

    @Test
    fun entriesFollowThePinsAndGreyWhatTheListLacks() {
        val entries = pinnedEntries(
            listOf(pin("c2", 0), pin("gone", 1), pin("c1", 2, missing = true)),
            listOf(summary("c1"), summary("c2"), summary("c3")),
        )
        assertEquals(listOf("c2", "gone", "c1"), entries.map { it.pin.convoID })
        assertEquals("Chat c2", entries[0].summary?.title)
        assertTrue("not in this device's list", entries[1].isMissing)
        assertTrue("flagged missing by the journal, even with a row", entries[2].isMissing)
        assertFalse(entries[0].isMissing)
    }

    @Test
    fun pinnedConversationsLeaveTheOrdinaryList() {
        assertEquals(setOf("c1", "c2"), pinnedIDs(listOf(pin("c1", 0), pin("c2", 1))))
        assertEquals(emptySet<String>(), pinnedIDs(null))
    }

    @Test
    fun theChatHeaderMenuFollowsThePinState() {
        val calls = mutableListOf<String>()
        val actions = object : PinActions {
            override fun edit(convoID: String) { calls += "edit $convoID" }
            override fun move(convoID: String) { calls += "move $convoID" }
            override fun unpin(convoID: String) { calls += "unpin $convoID" }
            override fun shift(convoID: String, direction: Pins.Direction) = Unit
            override fun acceptSuccessor(pin: ConvoPin) = Unit
            override fun dismissSuccessor(pin: ConvoPin) = Unit
            override fun add() = Unit
        }
        assertNull("a journal without pins offers none", chatPinMenu(null, "c1", actions))
        val unpinned = chatPinMenu(listOf(pin("c2", 0)), "c1", actions)!!
        assertFalse(unpinned.isPinned)
        unpinned.onPin()
        val pinned = chatPinMenu(listOf(pin("c1", 0)), "c1", actions)!!
        assertTrue(pinned.isPinned)
        pinned.onMove(); pinned.onUnpin()
        assertEquals(listOf("edit c1", "move c1", "unpin c1"), calls)
    }
}
