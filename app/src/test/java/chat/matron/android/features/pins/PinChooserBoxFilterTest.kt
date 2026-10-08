package chat.matron.android.features.pins

import chat.matron.android.chat.ChatSummary
import chat.matron.android.models.BotIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// The pin chooser's box filter: which boxes it offers, how a box narrows
/// the rows (with the title search), and when it falls back to "All".
class PinChooserBoxFilterTest {
    private val bot = BotIdentity(matrixID = "@b:s", displayName = "Bot", avatarURL = null)

    private fun chat(id: String, title: String = "Chat $id", box: String? = null, roomBoxes: List<String> = emptyList()) =
        ChatSummary(
            id = id, title = title, bot = bot, lastActivity = null, unreadCount = 0,
            boxName = box, roomBoxNames = roomBoxes,
        )

    @Test
    fun optionsAreEveryNamedBoxOnceSortedByName() {
        val chats = listOf(
            chat("c1", box = "slate"),
            chat("c2", box = "Aspen"),
            chat("c3", box = "slate"),
            chat("c4"),
            chat("c5", roomBoxes = listOf("slate", "lab-mac")),
        )
        assertEquals(listOf("Aspen", "lab-mac", "slate"), pinChooserBoxOptions(chats))
    }

    @Test
    fun theFilterHidesWithFewerThanTwoBoxes() {
        assertEquals(emptyList<String>(), pinChooserBoxOptions(emptyList()))
        assertEquals("no named boxes (a single-box journal)", emptyList<String>(), pinChooserBoxOptions(listOf(chat("c1"), chat("c2"))))
        assertEquals(emptyList<String>(), pinChooserBoxOptions(listOf(chat("c1", box = "slate"), chat("c2", box = "slate"), chat("c3"))))
    }

    @Test
    fun aBoxNarrowsTheRowsAndARoomCountsOnEachOfItsBoxes() {
        val chats = listOf(
            chat("c1", box = "slate"),
            chat("c2", box = "aspen"),
            chat("c3"),
            chat("c4", roomBoxes = listOf("aspen", "slate")),
        )
        assertEquals(listOf("c1", "c4"), pinChooserChoices(chats, "", "slate").map { it.id })
        assertEquals(listOf("c2", "c4"), pinChooserChoices(chats, "", "aspen").map { it.id })
        assertEquals("All", listOf("c1", "c2", "c3", "c4"), pinChooserChoices(chats, "", null).map { it.id })
    }

    @Test
    fun theBoxCombinesWithTheSearch() {
        val chats = listOf(
            chat("c1", title = "Deploy fix", box = "slate"),
            chat("c2", title = "Deploy notes", box = "aspen"),
            chat("c3", title = "Lunch", box = "slate"),
        )
        assertEquals(listOf("c1"), pinChooserChoices(chats, " deploy ", "slate").map { it.id })
        assertEquals(listOf("c1", "c2"), pinChooserChoices(chats, "deploy", null).map { it.id })
        assertEquals(emptyList<String>(), pinChooserChoices(chats, "lunch", "aspen").map { it.id })
    }

    @Test
    fun aSelectedBoxThatLeftTheCandidatesFallsBackToAll() {
        val options = pinChooserBoxOptions(listOf(chat("c1", box = "slate"), chat("c2", box = "aspen")))
        assertEquals("slate", activePinChooserBox("slate", options))
        assertNull(activePinChooserBox("lab-mac", options))
        assertNull(activePinChooserBox(null, options))
        assertNull("the filter itself is gone", activePinChooserBox("slate", pinChooserBoxOptions(listOf(chat("c1", box = "slate")))))
    }

    @Test
    fun tappingTheSelectedBoxAgainClearsIt() {
        assertEquals("slate", toggledPinChooserBox(null, "slate"))
        assertEquals("aspen", toggledPinChooserBox("slate", "aspen"))
        assertNull(toggledPinChooserBox("slate", "slate"))
    }
}
