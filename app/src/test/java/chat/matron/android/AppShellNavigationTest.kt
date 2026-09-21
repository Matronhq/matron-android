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
        override fun pushChat(tab: AppTab, roomID: String) { commands += "pushChat:${tab.name}:$roomID" }
        override fun replaceTopChat(tab: AppTab, current: String, sibling: String) { commands += "replaceTop:${tab.name}:$current>$sibling" }
        override fun pushDecision(itemID: String) { commands += "pushDecision:$itemID" }
        override fun pushMission(tab: AppTab, missionID: String) { commands += "pushMission:${tab.name}:$missionID" }
        override fun replaceMissions(missionID: String) { commands += "replaceMissions:$missionID" }
        override fun pushMissionItem(itemID: String) { commands += "pushMissionItem:$itemID" }
        override fun popToRoot(tab: AppTab) { commands += "popToRoot:${tab.name}" }
        override fun popChats(count: Int) { commands += "popChats:$count" }
    }

    @Test
    fun itemRouteAndPathValues() {
        assertEquals("item/it_1", AppShellNavigation.itemRoute("it_1"))
        val args = mapOf("convoID" to "!r:s", "itemID" to "it_1")
        assertNull("a tab root mirrors as nothing", AppShellNavigation.pathValue("chats") { args[it] })
        assertNull(AppShellNavigation.pathValue("decisions/list") { args[it] })
        assertEquals("!r:s", AppShellNavigation.pathValue("chat/{convoID}") { args[it] })
        assertEquals("item/it_1", AppShellNavigation.pathValue("item/{itemID}") { args[it] })
        assertEquals("item detail mirrors alike on every tab", "item/it_1", AppShellNavigation.pathValue("decisions/item/{itemID}") { args[it] })
        assertEquals("item/it_1", AppShellNavigation.pathValue("coordinator/item/{itemID}") { args[it] })
        assertEquals("mission/ms_1", AppShellNavigation.missionRoute("ms_1"))
        assertEquals("a mission page mirrors alike on every tab", "mission/ms_1", AppShellNavigation.pathValue("mission/{missionID}") { mapOf("missionID" to "ms_1")[it] })
        assertEquals("mission/ms_1", AppShellNavigation.pathValue("missions/mission/{missionID}") { mapOf("missionID" to "ms_1")[it] })
        assertEquals("item/it_1", AppShellNavigation.pathValue("missions/item/{itemID}") { args[it] })
        assertNull(AppShellNavigation.pathValue("missions/list") { args[it] })
        assertNull(AppShellNavigation.pathValue("coordinator/root") { args[it] })
        assertEquals("the Coordinator tab's own chat route mirrors as the bare room id", "!r:s", AppShellNavigation.pathValue("coordinator/chat/{convoID}") { args[it] })
        assertEquals("items/!r:s", AppShellNavigation.pathValue("items/{convoID}") { args[it] })
        assertEquals("items/!r:s", AppShellNavigation.pathValue("coordinator/items/{convoID}") { args[it] })
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
        assertEquals("switch first, then push, so the push lands in the visible stack", listOf("switch:CONVERSATIONS", "pushChat:CONVERSATIONS:!r:s"), host.commands)
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
    /// Bar order since apple #209: Coordinator · Missions · Decisions ·
    /// Conversations, with Missions present until a 404 proves it
    /// unsupported (the default, like iOS, is shown).
    @Test
    fun rootSwipeLeftGoesToTheNextTabAndRightComesBack() {
        val host = RecordingHost()
        val nav = AppShellNavigation(host)
        assertFalse("nothing to the right of the last tab", nav.swipeRoot(dx = -120f, dy = 10f))
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        assertTrue(nav.swipeRoot(dx = 120f, dy = 10f))
        assertEquals(AppTab.DECISIONS, nav.tab.value)
        assertTrue(nav.swipeRoot(dx = 120f, dy = 10f))
        assertEquals(AppTab.MISSIONS, nav.tab.value)
        assertTrue("Coordinator sits to the left of Missions", nav.swipeRoot(dx = 120f, dy = 10f))
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertFalse("nothing to the left of the first tab", nav.swipeRoot(dx = 120f, dy = 10f))
        assertTrue(nav.swipeRoot(dx = -120f, dy = 10f))
        assertEquals(AppTab.MISSIONS, nav.tab.value)
        assertEquals(listOf("switch:DECISIONS", "switch:MISSIONS", "switch:COORDINATOR", "switch:MISSIONS"), host.commands)
    }

    // MARK: - Coordinator tab (apple #197)

    @Test
    fun pushChatPushesOnTheSelectedTabIdempotently() {
        val host = RecordingHost()
        val nav = AppShellNavigation(host)
        nav.pushChat("!child:s")
        nav.pushChat("!child:s")
        assertEquals(listOf("!child:s"), nav.chatPath)
        assertEquals(listOf("pushChat:CONVERSATIONS:!child:s"), host.commands)
        nav.noteDestination(AppTab.COORDINATOR, "c0", null)
        nav.pushChat("!sub:s")
        assertEquals("a sub-chat opened from the coordinator pushes on coordinatorPath", listOf("!sub:s"), nav.coordinatorPath)
        assertEquals("…not on the Conversations stack", listOf("!child:s"), nav.chatPath)
        assertEquals("pushChat:COORDINATOR:!sub:s", host.commands.last())
        nav.noteDestination(AppTab.DECISIONS, "d0", null)
        nav.pushChat("!r:s")
        assertEquals("from Decisions a chat push opens the conversation", AppTab.CONVERSATIONS, nav.tab.value)
        assertEquals(listOf("!child:s", "!r:s"), nav.chatPath)
    }

    /// Bugbot, apple #197: the coordinator conversation must never be
    /// mounted in Conversations as well — every route to it lands on its tab.
    @Test
    fun coordinatorConversationAlwaysRoutesToItsOwnTab() {
        val host = RecordingHost()
        val nav = AppShellNavigation(host)
        nav.coordinatorConvoID = "!coord:s"
        nav.coordinatorPath = listOf("!child:s")
        nav.openChat("!coord:s")
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertEquals("a deep link lands at the coordinator root", emptyList<String>(), nav.coordinatorPath)
        assertEquals(emptyList<String>(), nav.chatPath)
        assertEquals(listOf("popToRoot:COORDINATOR", "switch:COORDINATOR"), host.commands)
        nav.noteDestination(AppTab.DECISIONS, "d0", null)
        nav.openConversationFromDecisions("!coord:s")
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertEquals(emptyList<String>(), nav.chatPath)
        // A pushed coordinator id is redirected on the way in, never stored.
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        nav.noteDestination(AppTab.CONVERSATIONS, "e1", "!other:s")
        host.commands.clear()
        nav.pushChat("!coord:s")
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertEquals("the chat beneath stays where it was", listOf("!other:s"), nav.chatPath)
        assertEquals(listOf("switch:COORDINATOR"), host.commands)
        // A chat-list row push that reached the controller anyway hands off.
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        nav.chatPath = listOf("!coord:s")
        host.commands.clear()
        assertTrue(nav.redirectCoordinatorPush())
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertEquals(emptyList<String>(), nav.chatPath)
        assertEquals(listOf("popChats:1", "switch:COORDINATOR"), host.commands)
        // An origin link from an open chat appends it on top: pop just that
        // entry and hand off, leaving the chat beneath where it was.
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        nav.chatPath = listOf("!other:s", "!coord:s")
        assertTrue(nav.redirectCoordinatorPush())
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertEquals(listOf("!other:s"), nav.chatPath)
        // Any other push is left alone.
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        nav.chatPath = listOf("!other:s")
        assertFalse(nav.redirectCoordinatorPush())
        assertEquals(listOf("!other:s"), nav.chatPath)
        nav.coordinatorConvoID = null
        nav.chatPath = listOf("!coord:s")
        assertFalse("no coordinator set: it is an ordinary chat", nav.redirectCoordinatorPush())
    }

    /// Bugbot (apple #197, High): assigning the coordinator to a chat
    /// already on the Conversations stack — even beneath an item detail —
    /// evicts that chat, and a second copy on the Coordinator stack pops to
    /// the root.
    @Test
    fun coordinatorIsNeverMountedTwice() {
        val host = RecordingHost()
        val nav = AppShellNavigation(host)
        nav.chatPath = listOf("!other:s", "!coord:s", "item/abc")
        nav.coordinatorConvoID = "!coord:s"
        assertEquals("assigning an open chat hands off to its tab", AppTab.COORDINATOR, nav.tab.value)
        assertEquals("the chat and everything above it leave the Conversations stack", listOf("!other:s"), nav.chatPath)
        assertEquals(emptyList<String>(), nav.coordinatorPath)
        assertEquals(listOf("popChats:2", "switch:COORDINATOR"), host.commands)
        // Re-assigning the same id is a no-op.
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        host.commands.clear()
        nav.coordinatorConvoID = "!coord:s"
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        assertTrue(host.commands.isEmpty())
        // A second copy pushed onto the Coordinator stack pops to the root.
        nav.noteDestination(AppTab.COORDINATOR, "c0", null)
        nav.coordinatorPath = listOf("item/abc", "!coord:s")
        assertTrue(nav.redirectCoordinatorPush())
        assertEquals(emptyList<String>(), nav.coordinatorPath)
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertEquals(listOf("popToRoot:COORDINATOR"), host.commands)
        // Other chats on the Coordinator stack are fine.
        nav.coordinatorPath = listOf("!other:s")
        assertFalse(nav.redirectCoordinatorPush())
        assertEquals(listOf("!other:s"), nav.coordinatorPath)
        // A NEW coordinator starts at its root — anything pushed under the
        // old one is gone.
        host.commands.clear()
        nav.coordinatorConvoID = "!next:s"
        assertEquals(emptyList<String>(), nav.coordinatorPath)
        assertEquals(listOf("popToRoot:COORDINATOR"), host.commands)
    }

    @Test
    fun deepLinkLeavesTheCoordinatorStackAlone() {
        val nav = AppShellNavigation()
        nav.noteDestination(AppTab.COORDINATOR, "c0", null)
        nav.coordinatorPath = listOf("!child:s")
        nav.openChat("!r:s")
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        assertEquals(listOf("!child:s"), nav.coordinatorPath)
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

    /// A host that behaves like the controller: every navigation reports
    /// its new destination back through `noteDestination` synchronously,
    /// as `NavController` does from inside `navigate()`.
    /// A host that behaves like the controller: it keeps a stack per tab,
    /// every navigation reports its new destination back through
    /// `noteDestination` synchronously (as `NavController` does from inside
    /// `navigate()`), a tab switch restores that tab's saved stack, and
    /// `popChats` restores Conversations first when another tab shows.
    private class SimulatedControllerHost : AppShellNavigation.Host {
        lateinit var nav: AppShellNavigation
        val commands = mutableListOf<String>()
        var current = AppTab.CONVERSATIONS
        private var nextID = 0
        private val stacks: Map<AppTab, MutableList<Pair<String, String?>>> =
            AppTab.entries.associateWith { mutableListOf<Pair<String, String?>>("root-${it.name}" to null) }
        fun stack(tab: AppTab): List<String?> = stacks.getValue(tab).map { it.second }
        private fun report(tab: AppTab) { val top = stacks.getValue(tab).last(); nav.noteDestination(tab, top.first, top.second) }
        private fun push(tab: AppTab, value: String) { current = tab; stacks.getValue(tab).add("e${nextID++}" to value); report(tab) }
        /// A push the model did not drive (a chat-list row's own navigate).
        fun externalPush(tab: AppTab, value: String) = push(tab, value)
        override fun switchTab(tab: AppTab) { commands += "switch:${tab.name}"; current = tab; report(tab) }
        override fun replaceChats(roomID: String) {
            commands += "replace:$roomID"
            val s = stacks.getValue(AppTab.CONVERSATIONS); while (s.size > 1) s.removeAt(s.size - 1)
            push(AppTab.CONVERSATIONS, roomID)
        }
        override fun pushChat(tab: AppTab, roomID: String) { commands += "pushChat:${tab.name}:$roomID"; push(tab, roomID) }
        override fun replaceTopChat(tab: AppTab, current: String, sibling: String) {
            commands += "replaceTop:${tab.name}:$current>$sibling"
            val s = stacks.getValue(tab); if (s.last().second == current) s.removeAt(s.size - 1)
            push(tab, sibling)
        }
        override fun pushDecision(itemID: String) { commands += "pushDecision:$itemID"; push(AppTab.DECISIONS, "item/$itemID") }
        override fun pushMission(tab: AppTab, missionID: String) { commands += "pushMission:${tab.name}:$missionID"; push(tab, "mission/$missionID") }
        override fun replaceMissions(missionID: String) {
            commands += "replaceMissions:$missionID"
            val s = stacks.getValue(AppTab.MISSIONS); while (s.size > 1) s.removeAt(s.size - 1)
            push(AppTab.MISSIONS, "mission/$missionID")
        }
        override fun pushMissionItem(itemID: String) { commands += "pushMissionItem:$itemID"; push(AppTab.MISSIONS, "item/$itemID") }
        override fun popToRoot(tab: AppTab) {
            commands += "popToRoot:${tab.name}"
            if (current != tab) return // a saved stack is not on the controller's back stack
            val s = stacks.getValue(tab); while (s.size > 1) s.removeAt(s.size - 1)
            report(tab)
        }
        override fun popChats(count: Int) {
            commands += "popChats:$count"
            if (current != AppTab.CONVERSATIONS) switchTab(AppTab.CONVERSATIONS)
            repeat(count) {
                val s = stacks.getValue(AppTab.CONVERSATIONS); if (s.size > 1) s.removeAt(s.size - 1)
                report(AppTab.CONVERSATIONS)
            }
        }
    }

    /// Bugbot (#76): evicting the coordinator drives the controller through
    /// a restore and pops that each re-enter the listener AFTER the model
    /// already trimmed its mirror — without the guard the re-entered
    /// redirect re-appended the room and the outer pops over-popped.
    @Test
    fun coordinatorEvictionNeitherOverPopsNorReappendsWhileTheControllerCatchesUp() {
        val host = SimulatedControllerHost()
        val nav = AppShellNavigation(host).also { host.nav = it }
        nav.noteDestination(AppTab.CONVERSATIONS, "root-CONVERSATIONS", null)
        nav.pushChat("!other:s")
        nav.pushChat("!coord:s")
        // Assignment from Settings while the chat is open (another tab may show).
        nav.selectTab(AppTab.DECISIONS)
        nav.assignCoordinator("!coord:s") { }
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertEquals(AppTab.COORDINATOR, host.current)
        assertEquals("only the coordinator left the Conversations stack", listOf("!other:s"), nav.chatPath)
        assertEquals(listOf(null, "!other:s"), host.stack(AppTab.CONVERSATIONS))
        assertEquals(emptyList<String>(), nav.coordinatorPath)
        // A chat-list row's own push of the coordinator (not model-driven).
        nav.selectTab(AppTab.CONVERSATIONS)
        assertEquals(listOf("!other:s"), nav.chatPath)
        host.externalPush(AppTab.CONVERSATIONS, "!coord:s")
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertEquals(listOf("!other:s"), nav.chatPath)
        assertEquals(listOf(null, "!other:s"), host.stack(AppTab.CONVERSATIONS))
        assertEquals(listOf<String?>(null), host.stack(AppTab.COORDINATOR))
        // Back in Conversations still lines up with the controller.
        nav.selectTab(AppTab.CONVERSATIONS)
        nav.noteDestination(AppTab.CONVERSATIONS, "root-CONVERSATIONS", null)
        assertTrue(nav.isAtRoot)
    }

    /// Bugbot (#76): the sub-chat switcher must not grow the stack. A pushed
    /// child is replaced in place (and the mirror sees a replace, not a
    /// push); the coordinator root cannot be replaced, so its sibling is
    /// pushed once and switches from there replace.
    @Test
    fun switchSubChatReplacesInPlaceOrPushesOnceFromTheCoordinatorRoot() {
        val host = SimulatedControllerHost()
        val nav = AppShellNavigation(host).also { host.nav = it }
        nav.noteDestination(AppTab.CONVERSATIONS, "root-CONVERSATIONS", null)
        nav.pushChat("!parent:s")
        nav.pushChat("!a:s")
        nav.switchSubChat("!a:s", "!b:s")
        assertEquals("replaced in place, mirrored once", listOf("!parent:s", "!b:s"), nav.chatPath)
        assertEquals(listOf(null, "!parent:s", "!b:s"), host.stack(AppTab.CONVERSATIONS))
        assertEquals("replaceTop:CONVERSATIONS:!a:s>!b:s", host.commands.last())
        host.commands.clear()
        nav.switchSubChat("!b:s", "!b:s")
        assertTrue("switching to the current child is a no-op", host.commands.isEmpty())
        // The coordinator is itself a sub-chat: its tab's root (a chooser
        // pick assigns AND lands there; a Settings assignment alone does
        // not navigate).
        nav.chooseCoordinator("!coord:s") { }
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        nav.switchSubChat("!coord:s", "!sibA:s")
        assertEquals("pushed once from the root", listOf("!sibA:s"), nav.coordinatorPath)
        assertEquals(listOf(null, "!sibA:s"), host.stack(AppTab.COORDINATOR))
        nav.switchSubChat("!sibA:s", "!sibB:s")
        assertEquals("further switches replace — the stack never grows", listOf("!sibB:s"), nav.coordinatorPath)
        assertEquals(listOf(null, "!sibB:s"), host.stack(AppTab.COORDINATOR))
        nav.switchSubChat("!sibB:s", "!coord:s")
        assertEquals("switching back to the coordinator lands on its root", emptyList<String>(), nav.coordinatorPath)
        assertEquals(listOf<String?>(null), host.stack(AppTab.COORDINATOR))
        assertEquals("the Conversations stack was never touched", listOf("!parent:s", "!b:s"), nav.chatPath)
    }

    /// Bugbot (#76): a chooser pick must mirror the new coordinator into
    /// the rules BEFORE opening it, or the open mounts it in Conversations
    /// (wiping that stack) and only a later effect evicts it.
    @Test
    fun chooseCoordinatorRedirectsBeforeOpening() {
        val host = SimulatedControllerHost()
        val nav = AppShellNavigation(host).also { host.nav = it }
        nav.noteDestination(AppTab.CONVERSATIONS, "root-CONVERSATIONS", null)
        nav.pushChat("!other:s")
        var persisted: String? = "unset"
        nav.chooseCoordinator("!coord:s") { persisted = it }
        assertEquals("!coord:s", persisted)
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertTrue("never mounted in Conversations", host.commands.none { it.startsWith("replace:") })
        assertEquals("the Conversations stack is untouched", listOf("!other:s"), nav.chatPath)
        assertEquals(listOf(null, "!other:s"), host.stack(AppTab.CONVERSATIONS))
        // Clear from Settings persists null and returns the tab to setup.
        nav.assignCoordinator(null) { persisted = it }
        assertNull(persisted)
        assertNull(nav.coordinatorConvoID)
    }

    /// Bugbot (#75): the rules update the path FIRST and the controller then
    /// reports the same destination as a new entry — the mirror must bind
    /// it, not append a duplicate, or the sole-open-chat check stops
    /// matching and a repeated deep link remounts the screen.
    @Test
    fun modelDrivenNavigationIsMirroredOnce_andARepeatedOpenChatIsANoOp() {
        val host = SimulatedControllerHost()
        val nav = AppShellNavigation(host).also { host.nav = it }
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        nav.openChat("!r:s")
        assertEquals("one entry, not [id, id]", listOf("!r:s"), nav.chatPath)
        assertEquals(listOf("replace:!r:s"), host.commands)
        host.commands.clear()
        nav.openChat("!r:s")
        assertTrue("the sheet's onCreated plus the newConversations echo must not remount the chat", host.commands.isEmpty())
        assertEquals(listOf("!r:s"), nav.chatPath)
        // Back pops cleanly to the list.
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        assertTrue(nav.isAtRoot)
    }

    @Test
    fun deepLinkOverAnExistingStackIsMirroredOnce() {
        val host = SimulatedControllerHost()
        val nav = AppShellNavigation(host).also { host.nav = it }
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        nav.noteDestination(AppTab.CONVERSATIONS, "old", "!old:s")
        nav.noteDestination(AppTab.CONVERSATIONS, "child", "!child:s")
        nav.openChat("!new:s")
        assertEquals(listOf("!new:s"), nav.chatPath)
        host.commands.clear()
        nav.openChat("!new:s")
        assertTrue(host.commands.isEmpty())
        // A later external push and a pop still line up with the controller's ids.
        nav.noteDestination(AppTab.CONVERSATIONS, "sub", "!sub:s")
        assertEquals(listOf("!new:s", "!sub:s"), nav.chatPath)
        nav.noteDestination(AppTab.CONVERSATIONS, "e0", "!new:s") // back
        assertEquals(listOf("!new:s"), nav.chatPath)
        // A model-driven push (sub-chat, spawn card, origin link) mirrors once too.
        host.commands.clear()
        nav.pushChat("!again:s")
        assertEquals(listOf("!new:s", "!again:s"), nav.chatPath)
        nav.pushChat("!again:s")
        assertEquals("a repeated push of the top chat is a no-op", listOf("!new:s", "!again:s"), nav.chatPath)
        assertEquals(listOf("pushChat:CONVERSATIONS:!again:s"), host.commands)
    }

    @Test
    fun decisionsHandOffAndPushDecisionAreMirroredOnce() {
        val host = SimulatedControllerHost()
        val nav = AppShellNavigation(host).also { host.nav = it }
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        nav.selectTab(AppTab.DECISIONS)
        nav.pushDecision("it_1")
        assertEquals(listOf("item/it_1"), nav.decisionsPath)
        nav.openConversationFromDecisions("!r:s")
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        assertEquals(listOf("!r:s"), nav.chatPath)
        assertEquals("the Decisions stack is left where it was", listOf("item/it_1"), nav.decisionsPath)
        host.commands.clear()
        nav.openConversationFromDecisions("!r:s")
        assertEquals("no duplicate push for the chat already on top", listOf("!r:s"), nav.chatPath)
        assertTrue(host.commands.isEmpty())
    }

    /// An expectation is one-shot and value-bound: a different destination
    /// arriving instead (the controller went somewhere else) is a real push.
    @Test
    fun expectationDoesNotSwallowAnUnrelatedDestination() {
        val nav = AppShellNavigation(RecordingHost())
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        nav.openChat("!r:s") // recording host: no mirror yet
        nav.noteDestination(AppTab.CONVERSATIONS, "e9", "settings")
        assertEquals(listOf("!r:s", "settings"), nav.chatPath)
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

    /// The same once-only mirror for a mission page pushed by a title tap
    /// on a chat: the controller's report binds to the entry the rule
    /// added, and a repeat tap for the page already on top is a no-op.
    @Test
    fun pushedMissionIsMirroredOnce_andARepeatTapIsANoOp() {
        val host = SimulatedControllerHost()
        val nav = AppShellNavigation(host).also { host.nav = it }
        nav.noteDestination(AppTab.CONVERSATIONS, "root", null)
        nav.openChat("!r:s")
        nav.pushMission("ms_1")
        assertEquals(listOf("!r:s", "mission/ms_1"), nav.chatPath)
        host.commands.clear()
        nav.pushMission("ms_1")
        assertTrue("a double title tap must not stack two identical pages", host.commands.isEmpty())
        assertEquals(listOf("!r:s", "mission/ms_1"), nav.chatPath)
    }
}
