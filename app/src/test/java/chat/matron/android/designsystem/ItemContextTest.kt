package chat.matron.android.designsystem

import chat.matron.android.models.ItemKind
import chat.matron.android.models.Mission
import chat.matron.android.models.TrackerItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Pins the item detail's context block: the mission an
/// item belongs to and the conversation that owns it, under its "#N · Kind"
/// line. Pure, like the rest of the tracker views' logic — this project's
/// stand-in for Apple's snapshot baselines.
class ItemContextTest {
    private fun item(
        origin: String = "c1",
        originTitle: String? = null,
        missionID: String? = "ms_1",
        missionNum: Int? = 61,
    ) = TrackerItem(
        id = "it_1", num = 10563, kind = ItemKind.TASK, title = "Show the context",
        originConvoID = origin, originConvoTitle = originTitle, missionID = missionID, missionNum = missionNum,
    )

    private fun mission(name: String? = null) = Mission(id = "ms_1", num = 61, title = "Item detail context", name = name, originConvoID = "c1")

    // MARK: - Mission row

    @Test
    fun missionRowUsesTheNameThenTheTitle() {
        assertEquals("#61 Context", itemContext(item(), null, null, mission(name = "Context")).missionLabel)
        assertEquals("#61 Item detail context", itemContext(item(), null, null, mission()).missionLabel)
    }

    @Test
    fun missionRowFallsBackToTheNumberWhenTheMissionIsNotCached() {
        val context = itemContext(item(), null, null, null)
        assertEquals("Mission #61", context.missionLabel)
        assertEquals("still opens the mission page", "ms_1", context.missionID)
        assertEquals(
            "a cached row for ANOTHER mission is not this one",
            "Mission #61",
            itemContext(item(missionID = "ms_other"), null, null, mission(name = "Context")).missionLabel,
        )
    }

    @Test
    fun noMissionNoMissionRowButTheOwnerStays() {
        val context = itemContext(item(missionID = null, missionNum = null), null, "box-2 · Fix", null)
        assertNull(context.missionLabel)
        assertEquals(ItemContextConversation("c1", "box-2 · Fix"), context.owner)
    }

    // MARK: - Owner row

    @Test
    fun ownerRowIsAlwaysPresentWithFallbacks() {
        assertEquals(
            ItemContextConversation("c1", "box-2 · Fix login"),
            itemContext(item(originTitle = "Fix login (journal)"), null, "box-2 · Fix login", null).owner,
        )
        assertEquals(
            "not cached here: the journal's title",
            ItemContextConversation("c1", "Fix login"),
            itemContext(item(originTitle = "Fix login"), null, null, null).owner,
        )
        assertEquals(
            "named by nobody: a generic label, never a missing row",
            ItemContextConversation("c1", "Conversation"),
            itemContext(item(), null, null, null).owner,
        )
    }

    @Test
    fun ownerRowHiddenInsideThatConversationButTheMissionRowStays() {
        val context = itemContext(item(), "c1", "box-2 · Fix login", mission())
        assertNull(context.owner)
        assertEquals("#61 Item detail context", context.missionLabel)
        assertEquals(
            "inside ANOTHER conversation the owner still shows",
            "c1",
            itemContext(item(), "c9", "box-2 · Fix login", null).owner?.convoID,
        )
    }

    @Test
    fun grantedItemHasNoConversationRow() {
        val context = itemContext(item(origin = "", missionID = null, missionNum = null), null, null, null)
        assertNull(context.owner)
        assertTrue(context.isEmpty)
    }
}
