package chat.matron.android.features.share

import chat.matron.android.chat.ChatSummary
import chat.matron.android.models.BotIdentity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareTargetsTest {
    private fun target(id: String, title: String = id, at: Long? = null, coordinator: Boolean = false) =
        ShareTarget(id = id, title = title, isCoordinator = coordinator, lastActivity = at?.let(Instant::ofEpochSecond))

    @Test
    fun orderPutsTheCoordinatorFirstThenNewestThenUndated() {
        val ordered = ShareTargets.ordered(
            listOf(target("b"), target("old", at = 10), target("coord", at = 1, coordinator = true), target("new", at = 20), target("a")),
        )
        assertEquals(listOf("coord", "new", "old", "a", "b"), ordered.map { it.id })
    }

    @Test
    fun orderIsCutToTheLimit() {
        val many = (1..10).map { target("c$it", at = it.toLong()) }
        assertEquals(listOf("c10", "c9", "c8"), ShareTargets.ordered(many, limit = 3).map { it.id })
    }

    @Test
    fun filterNeedsEveryWordIgnoringCaseAndAccents() {
        val targets = listOf(target("1", "Café menu redesign"), target("2", "Menu prices"), target("3", "Release notes"))
        assertEquals(listOf("1"), ShareTargets.filter(targets, "cafe MENU").map { it.id })
        assertEquals(listOf("1", "2"), ShareTargets.filter(targets, " menu ").map { it.id })
        assertEquals(targets, ShareTargets.filter(targets, "   "))
        assertEquals(emptyList<ShareTarget>(), ShareTargets.filter(targets, "menu notes"))
    }

    @Test
    fun fromSummariesLeavesOutChildChatsAndNamesTheUntitled() {
        val bot = BotIdentity(matrixID = "bot", displayName = "Agent", avatarURL = null)
        val summaries = listOf(
            ChatSummary(id = "c1", title = "", bot = bot, lastActivity = Instant.ofEpochSecond(5), unreadCount = 0, boxName = "box-a"),
            ChatSummary(id = "c2", title = "Sub task", bot = bot, lastActivity = Instant.ofEpochSecond(9), unreadCount = 0, parentConvoID = "c1"),
            ChatSummary(id = "c3", title = "Coordinator", bot = bot, lastActivity = null, unreadCount = 0),
        )
        val targets = ShareTargets.from(summaries, coordinatorID = "c3")
        assertEquals(listOf("c3", "c1"), targets.map { it.id })
        assertEquals("Untitled", targets[1].title)
        assertEquals("box-a", targets[1].shownDetail)
        // Its title already says what it is.
        assertNull(targets[0].shownDetail)
        assertEquals("Coordinator", target("x", "Planning", coordinator = true).shownDetail)
    }

    @Test
    fun shortcutIdsRoundTripToConversations() {
        assertEquals("c-42", ShareShortcuts.conversationID(ShareShortcuts.shortcutID("c-42")))
        assertNull(ShareShortcuts.conversationID("something-else"))
        assertNull(ShareShortcuts.conversationID("convo:"))
    }
}
