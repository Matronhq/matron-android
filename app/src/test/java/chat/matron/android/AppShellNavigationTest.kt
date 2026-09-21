package chat.matron.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// App shell (spec §3): the shell's navigation rules are a plain object, so
/// every cross-tab rule is a pure function of it. Ported from matron-apple's
/// `AppShellNavigationTests`, plus the Android host-command and stack-mirror
/// seams (`Host`, `noteDestination`) that stand in for SwiftUI's path bindings.
class AppShellNavigationTest {
    private class RecordingHost : AppShellNavigation.Host {
        val commands = mutableListOf<String>()
        override fun switchTab(tab: AppTab) { commands += "switch:${tab.name}" }
        override fun replaceChats(roomID: String) { commands += "replace:$roomID" }
        override fun pushChat(roomID: String) { commands += "pushChat:$roomID" }
        override fun pushDecision(itemID: String) { commands += "pushDecision:$itemID" }
        override fun popToRoot(tab: AppTab) { commands += "popToRoot:${tab.name}" }
    }

    @Test
    fun itemRouteAndPathValues() {
        assertEquals("item/it_1", AppShellNavigation.itemRoute("it_1"))
        val args = mapOf("convoID" to "!r:s", "itemID" to "it_1")
        assertNull("a tab root mirrors as nothing", AppShellNavigation.pathValue("chats") { args[it] })
        assertNull(AppShellNavigation.pathValue("decisions/list") { args[it] })
        assertEquals("!r:s", AppShellNavigation.pathValue("chat/{convoID}") { args[it] })
        assertEquals("item/it_1", AppShellNavigation.pathValue("item/{itemID}") { args[it] })
        assertEquals("both item-detail routes mirror alike", "item/it_1", AppShellNavigation.pathValue("decision/{itemID}") { args[it] })
        assertEquals("items/!r:s", AppShellNavigation.pathValue("items/{convoID}") { args[it] })
        assertEquals("settings", AppShellNavigation.pathValue("settings") { args[it] })
        assertNull(AppShellNavigation.pathValue(null) { args[it] })
    }

    @Test
    fun deepLinkSwitchesToConversationsAndReplacesThePath() {
        val host = RecordingHost()
        val nav = AppShellNavigation(host)
        nav.noteDestination(AppTab.DECISIONS, "e0", null)
        nav.chatPath = listOf("!old:s", "!child:s")
        nav.openChat("!new:s")
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        assertEquals("a deep link collapses the stack to the target (Dan, 2026-08-06)", listOf("!new:s"), nav.chatPath)
        assertEquals(listOf("switch:CONVERSATIONS", "replace:!new:s"), host.commands)
    }

    @Test
    fun deepLinkIsIdempotentForTheOpenChat() {
        val host = RecordingHost()
        val nav = AppShellNavigation(host)
        nav.chatPath = listOf("!r:s")
        nav.openChat("!r:s")
        assertEquals(listOf("!r:s"), nav.chatPath)
        assertTrue("nothing to do, nothing sent to the controller", host.commands.isEmpty())
    }

    @Test
    fun openConversationFromDecisionsSwitchesTabThenAppends() {
        val host = RecordingHost()
        val nav = AppShellNavigation(host)
        nav.noteDestination(AppTab.DECISIONS, "e0", null)
        nav.decisionsPath = listOf("item/it_1")
        nav.openConversationFromDecisions("!r:s")
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        assertEquals(listOf("!r:s"), nav.chatPath)
        assertEquals("the Decisions stack is left where it was", listOf("item/it_1"), nav.decisionsPath)
        assertEquals("switch first, then push, so the push lands in the visible stack", listOf("switch:CONVERSATIONS", "pushChat:!r:s"), host.commands)
        nav.openConversationFromDecisions("!r:s")
        assertEquals("no duplicate push for the chat already on top", listOf("!r:s"), nav.chatPath)
        assertEquals(2, host.commands.size)
    }

    @Test
    fun pushDecisionAppendsToTheDecisionsStackWithoutChangingTab() {
        val host = RecordingHost()
        val nav = AppShellNavigation(host)
        nav.pushDecision("it_9")
        assertEquals(listOf("item/it_9"), nav.decisionsPath)
        assertEquals("pushing a decision never changes the tab", AppTab.CONVERSATIONS, nav.tab.value)
        assertEquals(listOf("pushDecision:it_9"), host.commands)
    }

    @Test
    fun selectTabSwitchesOrPopsTheSelectedTabToRoot() {
        val host = RecordingHost()
        val nav = AppShellNavigation(host)
        nav.selectTab(AppTab.CONVERSATIONS)
        assertTrue("re-tapping the selected tab at its root does nothing", host.commands.isEmpty())
        nav.chatPath = listOf("!r:s")
        nav.selectTab(AppTab.CONVERSATIONS)
        assertEquals(listOf("popToRoot:CONVERSATIONS"), host.commands)
        nav.selectTab(AppTab.DECISIONS)
        assertEquals(AppTab.DECISIONS, nav.tab.value)
        assertEquals(listOf("popToRoot:CONVERSATIONS", "switch:DECISIONS"), host.commands)
    }

    // Dan, 2026-09-09: swipe between the conversation list and the
    // decisions list — a horizontal swipe at a tab's ROOT moves one tab in
    // bar order; deeper in a stack the chat / item detail own horizontal
    // drags, so a non-empty path ignores it.
    @Test
    fun rootSwipeLeftGoesToTheNextTabAndRightComesBack() {
        val host = RecordingHost()
        val nav = AppShellNavigation(host)
        assertTrue(nav.swipeRoot(dx = -120f, dy = 10f))
        assertEquals(AppTab.DECISIONS, nav.tab.value)
        assertFalse("nothing to the right of the last tab", nav.swipeRoot(dx = -120f, dy = 10f))
        assertEquals(AppTab.DECISIONS, nav.tab.value)
        assertTrue(nav.swipeRoot(dx = 120f, dy = 10f))
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        assertFalse("nothing to the left of the first tab", nav.swipeRoot(dx = 120f, dy = 10f))
        assertEquals(listOf("switch:DECISIONS", "switch:CONVERSATIONS"), host.commands)
    }

    @Test
    fun rootSwipeIgnoresShortOrVerticalDragsAndNonRootStacks() {
        val nav = AppShellNavigation()
        assertFalse("below the threshold", nav.swipeRoot(dx = -60f, dy = 0f))
        assertFalse("a list scroll", nav.swipeRoot(dx = -120f, dy = 200f))
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        nav.chatPath = listOf("!r:s")
        assertFalse("inside a chat the pager owns the drag", nav.swipeRoot(dx = -120f, dy = 0f))
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        nav.chatPath = emptyList()
        nav.noteDestination(AppTab.DECISIONS, "e0", null)
        nav.decisionsPath = listOf("item/it_1")
        assertFalse("inside an item detail too", nav.swipeRoot(dx = 120f, dy = 0f))
        assertEquals(AppTab.DECISIONS, nav.tab.value)
    }

    /// The controller's destination changes mirror into the paths: a new
    /// entry id is a push, a known one a pop, a root empties the stack —
    /// and the tab follows the destination (system back out of Decisions).
    @Test
    fun noteDestinationMirrorsPushesPopsAndRoots() {
        val nav = AppShellNavigation()
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        assertTrue(nav.isAtRoot)
        nav.noteDestination(AppTab.CONVERSATIONS, "e1", "!parent:s")
        nav.noteDestination(AppTab.CONVERSATIONS, "e2", "!child:s")
        nav.noteDestination(AppTab.CONVERSATIONS, "e3", "item/it_1")
        assertEquals(listOf("!parent:s", "!child:s", "item/it_1"), nav.chatPath)
        assertFalse(nav.isAtRoot)
        nav.noteDestination(AppTab.CONVERSATIONS, "e1", "!parent:s") // back, twice
        assertEquals("a pop truncates after the surviving entry", listOf("!parent:s"), nav.chatPath)
        nav.noteDestination(AppTab.DECISIONS, "d1", "item/it_2")
        assertEquals(AppTab.DECISIONS, nav.tab.value)
        assertEquals(listOf("item/it_2"), nav.decisionsPath)
        assertEquals("the other tab's stack is untouched", listOf("!parent:s"), nav.chatPath)
        nav.noteDestination(AppTab.DECISIONS, "droot", null)
        assertTrue(nav.decisionsPath.isEmpty())
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        assertTrue(nav.isAtRoot)
    }
}
