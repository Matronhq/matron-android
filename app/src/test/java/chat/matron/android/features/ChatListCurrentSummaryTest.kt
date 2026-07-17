package chat.matron.android.features

import chat.matron.android.chat.ChatRecencyGroup
import chat.matron.android.chat.ChatSummary
import chat.matron.android.features.chatlist.currentSummary
import chat.matron.android.models.BotIdentity
import chat.matron.android.viewmodels.ChatListViewModel
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ports the `ChatListView.currentSummary(for:)` lookup contract (MatronTests/
 * ChatListViewBindingTests.swift): resolve the latest summary for an id, or null
 * when the room is absent from the current snapshot.
 */
class ChatListCurrentSummaryTest {

    private val bot = BotIdentity(matrixID = "@b:s", displayName = "Bot", avatarURL = null)

    private fun summary(id: String, unread: Int) = ChatSummary(
        id = id,
        title = "Chat",
        bot = bot,
        lastActivity = Instant.now(),
        unreadCount = unread,
    )

    private fun grouped(vararg summaries: ChatSummary) = listOf(
        ChatListViewModel.GroupedSummaries(ChatRecencyGroup.TODAY, summaries.toList()),
    )

    @Test fun resolvesMatchingSummary() {
        val groups = grouped(summary("!1:s", 7), summary("!2:s", 0))
        assertEquals(7, currentSummary(groups, "!1:s")?.unreadCount)
    }

    @Test fun returnsNull_whenRoomAbsent() {
        val groups = grouped(summary("!1:s", 0))
        assertNull(currentSummary(groups, "!ghost:s"))
    }

    @Test fun returnsNull_forEmptyGroups() {
        assertNull(currentSummary(emptyList(), "!1:s"))
    }
}
