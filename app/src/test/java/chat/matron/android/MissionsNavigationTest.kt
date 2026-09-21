package chat.matron.android

import chat.matron.android.features.missions.MissionOpenConversationOutcome
import chat.matron.android.features.missions.missionOpenConversationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// The Missions tab's shell rules (apple #209 / #216), ported from
/// matron-apple's `MissionsNavigationTests`.
class MissionsNavigationTest {
    private class RecordingHost : AppShellNavigation.Host {
        val commands = mutableListOf<String>()
        override fun switchTab(tab: AppTab) { commands += "switch:${tab.name}" }
        override fun replaceChats(roomID: String) { commands += "replace:$roomID" }
        override fun pushChat(tab: AppTab, roomID: String) { commands += "pushChat:${tab.name}:$roomID" }
        override fun pushDecision(itemID: String) { commands += "pushDecision:$itemID" }
        override fun pushMission(tab: AppTab, missionID: String) { commands += "pushMission:${tab.name}:$missionID" }
        override fun replaceMissions(missionID: String) { commands += "replaceMissions:$missionID" }
        override fun pushMissionItem(itemID: String) { commands += "pushMissionItem:$itemID" }
        override fun popToRoot(tab: AppTab) { commands += "popToRoot:${tab.name}" }
        override fun popChats(count: Int) { commands += "popChats:$count" }
    }

    private fun nav(supported: Boolean = true): Pair<AppShellNavigation, RecordingHost> {
        val host = RecordingHost()
        return AppShellNavigation(host).also { it.missionsSupported = supported } to host
    }

    @Test
    fun tabOrderIsCoordinatorMissionsDecisionsConversations() {
        assertEquals(listOf(AppTab.COORDINATOR, AppTab.MISSIONS, AppTab.DECISIONS, AppTab.CONVERSATIONS), AppTab.entries)
        assertEquals(AppTab.entries, AppShellNavigation.tabs(missionsSupported = true))
        assertEquals(listOf(AppTab.COORDINATOR, AppTab.DECISIONS, AppTab.CONVERSATIONS), AppShellNavigation.tabs(missionsSupported = false))
    }

    @Test
    fun openMissionSelectsTheTabAndReplacesItsPath() {
        val (nav, host) = nav()
        nav.missionsPath = listOf("mission/ms_old", "item/it_1")
        nav.openMission("ms_1")
        assertEquals(AppTab.MISSIONS, nav.tab.value)
        assertEquals(listOf("mission/ms_1"), nav.missionsPath)
        assertEquals(listOf("switch:MISSIONS", "replaceMissions:ms_1"), host.commands)
    }

    @Test
    fun pushMissionAppendsOnTheSelectedTabWithoutChangingIt() {
        val (nav, host) = nav()
        nav.noteDestination(AppTab.MISSIONS, "e0", null)
        nav.openMission("ms_1")
        nav.pushMission("ms_2")
        assertEquals(listOf("mission/ms_1", "mission/ms_2"), nav.missionsPath)
        assertEquals(AppTab.MISSIONS, nav.tab.value)
        // A title tap on a Conversations chat pushes on THAT stack.
        nav.noteDestination(AppTab.CONVERSATIONS, "e1", "!r:s")
        nav.pushMission("ms_3")
        assertEquals(listOf("!r:s", "mission/ms_3"), nav.chatPath)
        assertTrue(host.commands.contains("pushMission:CONVERSATIONS:ms_3"))
    }

    /// Mirrors `ChatView.pushMission(_:onto:)`'s idempotence (Bugbot: a
    /// double title tap or a second tap of the same mission used to stack
    /// two identical pages, so Back didn't return to the chat).
    @Test
    fun pushMissionNoOpsWhenAlreadyOnTop() {
        val (nav, host) = nav()
        nav.noteDestination(AppTab.MISSIONS, "e0", null)
        nav.openMission("ms_1")
        nav.pushMission("ms_1")
        assertEquals("a repeat push of the top mission is a no-op", listOf("mission/ms_1"), nav.missionsPath)
        assertEquals(1, host.commands.count { it.startsWith("replaceMissions") || it.startsWith("pushMission") })
    }

    /// The mirror binds the controller's report of a pushed mission to the
    /// entry the rule already added rather than appending a duplicate.
    @Test
    fun pushMissionMirrorIsIdempotent() {
        val (nav, _) = nav()
        nav.noteDestination(AppTab.MISSIONS, "e0", null)
        nav.pushMission("ms_1")
        nav.noteDestination(AppTab.MISSIONS, "e1", "mission/ms_1")
        assertEquals(listOf("mission/ms_1"), nav.missionsPath)
        nav.pushMissionItem("it_1")
        nav.noteDestination(AppTab.MISSIONS, "e2", "item/it_1")
        assertEquals(listOf("mission/ms_1", "item/it_1"), nav.missionsPath)
        assertFalse(nav.isAtRoot)
    }

    /// From Decisions (no mission page of its own) a mission opens on the
    /// Missions tab.
    @Test
    fun pushMissionFromDecisionsOpensTheMissionsTab() {
        val (nav, _) = nav()
        nav.noteDestination(AppTab.DECISIONS, "e0", null)
        nav.pushMission("ms_1")
        assertEquals(AppTab.MISSIONS, nav.tab.value)
        assertEquals(listOf("mission/ms_1"), nav.missionsPath)
    }

    /// A milestone tap hands off to Conversations and pushes, exactly as a
    /// Decisions origin link does — so Back returns to the mission page.
    @Test
    fun openConversationFromMissionsSwitchesTabThenPushes() {
        val (nav, host) = nav()
        nav.noteDestination(AppTab.MISSIONS, "e0", null)
        nav.openConversationFromMissions("c1")
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        assertEquals(listOf("c1"), nav.chatPath)
        assertEquals(listOf("switch:CONVERSATIONS", "pushChat:CONVERSATIONS:c1"), host.commands)
        // Idempotent on the same target.
        nav.openConversationFromMissions("c1")
        assertEquals(listOf("c1"), nav.chatPath)
    }

    /// The coordinator conversation always goes to its own tab, whoever
    /// asked — otherwise two chat screens share one cached view model.
    @Test
    fun openConversationFromMissionsRoutesTheCoordinatorToItsTab() {
        val (nav, _) = nav()
        nav.coordinatorConvoID = "c-coord"
        nav.openConversationFromMissions("c-coord")
        assertEquals(AppTab.COORDINATOR, nav.tab.value)
        assertTrue(nav.coordinatorPath.isEmpty())
        assertTrue(nav.chatPath.isEmpty())
    }

    @Test
    fun isAtRootCoversTheMissionsStack() {
        val (nav, _) = nav()
        nav.noteDestination(AppTab.MISSIONS, "e0", null)
        assertTrue(nav.isAtRoot)
        nav.pushMission("ms_1")
        assertFalse(nav.isAtRoot)
    }

    /// On an old journal the Missions tab is absent from the bar: the swipe
    /// must walk the three-tab order, never select a tag with no matching
    /// tab, and flipping unsupported while parked on Missions must clamp
    /// back to Conversations rather than leave a selection the bar can't
    /// render. Support starts unknown, which reads as "not in the bar".
    @Test
    fun swipeSkipsMissionsAndUnsupportedClampsOffIt() {
        val (nav, host) = nav(supported = false)
        assertFalse(AppShellNavigation().missionsSupported)
        nav.noteDestination(AppTab.COORDINATOR, "e0", null)
        assertTrue(nav.swipeRoot(dx = -120f, dy = 5f))
        assertEquals("Missions is skipped when unsupported", AppTab.DECISIONS, nav.tab.value)
        assertTrue(nav.swipeRoot(dx = 120f, dy = 5f))
        assertEquals(AppTab.COORDINATOR, nav.tab.value)

        nav.missionsSupported = true
        nav.noteDestination(AppTab.MISSIONS, "e1", null)
        nav.missionsSupported = false
        assertEquals("the false edge clamps a selected Missions tab off it", AppTab.CONVERSATIONS, nav.tab.value)
        assertEquals("switch:CONVERSATIONS", host.commands.last())
        // A flip while another tab shows changes nothing.
        nav.missionsSupported = true
        nav.missionsSupported = false
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
    }

    /// `openMission` must not select a tab the bar doesn't render.
    @Test
    fun openMissionNoOpsWhenUnsupported() {
        val (nav, host) = nav(supported = false)
        nav.openMission("ms_1")
        assertEquals(AppTab.CONVERSATIONS, nav.tab.value)
        assertTrue(nav.missionsPath.isEmpty())
        assertTrue(host.commands.isEmpty())
    }

    /// A mission page pushed on a chat tab's stack: a link back into the
    /// SAME room pops the page (never a no-op — the milestone jump has
    /// already fired underneath it); the coordinator's own room clears to
    /// its root; anything else pushes.
    @Test
    fun chatHostedMissionOpenConversationOutcome() {
        assertEquals(MissionOpenConversationOutcome.PopMission, missionOpenConversationOutcome("c-coord", current = null, coordinatorConvoID = "c-coord"))
        assertEquals(MissionOpenConversationOutcome.PopMission, missionOpenConversationOutcome("c-other", current = "c-other", coordinatorConvoID = "c-coord"))
        assertEquals(MissionOpenConversationOutcome.ClearToRoot, missionOpenConversationOutcome("c-coord", current = "c-other", coordinatorConvoID = "c-coord"))
        assertEquals(MissionOpenConversationOutcome.Push("c-third"), missionOpenConversationOutcome("c-third", current = "c-other", coordinatorConvoID = "c-coord"))
        assertEquals(MissionOpenConversationOutcome.Push("c-third"), missionOpenConversationOutcome("c-third", current = "c-other", coordinatorConvoID = null))
    }
}
