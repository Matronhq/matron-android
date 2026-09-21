package chat.matron.android.features.coordinator

import chat.matron.android.chat.ChatSummary
import chat.matron.android.models.BotIdentity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// App shell (spec §3, §5b): the Coordinator tab's root depends only on the
/// setting, and the chooser filters the chat list by title. Ported from
/// matron-apple's `CoordinatorTabViewTests` / `CoordinatorChooserSheetTests`.
class CoordinatorViewsTest {
    private val bot = BotIdentity(matrixID = "@b:s", displayName = "Bot", avatarURL = null)
    private fun chat(id: String, title: String) = ChatSummary(id = id, title = title, bot = bot, lastActivity = Instant.now(), unreadCount = 0)

    @Test
    fun rootIsSetupWithoutASettingAndTheChatWithOne() {
        assertEquals(CoordinatorRoot.Setup, coordinatorRoot(null))
        assertEquals("an empty stored value is no coordinator", CoordinatorRoot.Setup, coordinatorRoot(""))
        assertEquals(CoordinatorRoot.Chat("cv_1"), coordinatorRoot("cv_1"))
    }

    @Test
    fun filterMatchesTitleCaseInsensitivelyAndEmptyQueryKeepsAll() {
        val chats = listOf(chat("!1:s", "Auth refactor"), chat("!2:s", "Release notes"))
        assertEquals(listOf("!1:s", "!2:s"), coordinatorChoices(chats, "").map { it.id })
        assertEquals(listOf("!1:s"), coordinatorChoices(chats, "  AUTH ").map { it.id })
        assertTrue(coordinatorChoices(chats, "zzz").isEmpty())
    }
}
