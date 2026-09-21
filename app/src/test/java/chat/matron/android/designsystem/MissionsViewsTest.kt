package chat.matron.android.designsystem

import chat.matron.android.events.MilestoneMarkerEvent
import chat.matron.android.events.MissionMarkerEvent
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.Milestone
import chat.matron.android.models.MilestoneKind
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionState
import chat.matron.android.models.SessionTagInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Pins the missions views' pure UI rules (row labels, list states, card
/// copy, close confirmation). Apple pins the same via `MissionsSnapshotTests`
/// baselines; this project's conventions replace snapshots with
/// pure-function tests.
class MissionsViewsTest {
    private fun mission(needsYou: Int = 0, state: MissionState = MissionState.OPEN, closedOver: Int = 0) =
        Mission(id = "ms_1", num = 61, state = state, title = "Missions & milestones", originConvoID = "c1", needsYou = needsYou, closedOverOpenItems = closedOver)

    private fun milestoneMarker(kind: MilestoneKind = MilestoneKind.USER_INPUT, missionTitle: String? = "Missions & milestones") =
        MilestoneMarkerEvent("ml_1", 63, kind, "Dan asked", body = "the brief", missionID = "ms_1", missionNum = 61, missionTitle = missionTitle)

    @Test
    fun rowLabelSpeaksTheNeedsYouCountGrammatically() {
        assertEquals("Mission 61, Missions & milestones", missionRowAccessibilityLabel(mission()))
        assertEquals("Mission 61, Missions & milestones, 1 item needs you", missionRowAccessibilityLabel(mission(needsYou = 1)))
        assertEquals("Mission 61, Missions & milestones, 3 items need you", missionRowAccessibilityLabel(mission(needsYou = 3)))
    }

    /// The number must survive every state: a closed mission with no
    /// milestone still gets its `#num ·` meta line (apple #213).
    @Test
    fun rowMetaWithoutAMilestoneFollowsTheState() {
        assertEquals("No milestones yet", missionRowEmptyMeta(mission()))
        assertEquals("Closed", missionRowEmptyMeta(mission(state = MissionState.CLOSED)))
    }

    @Test
    fun listStatePrecedence() {
        val empty = MissionsListModel(open = emptyList(), closed = emptyList(), isSupported = true, isRefreshing = false)
        assertEquals(MissionsListState.EMPTY, missionsListState(empty))
        assertEquals("unsupported wins over empty", MissionsListState.UNSUPPORTED, missionsListState(empty.copy(isSupported = false)))
        assertEquals("an unknown support state shows the list", MissionsListState.EMPTY, missionsListState(empty.copy(isSupported = null)))
        val populated = empty.copy(closed = listOf(mission(state = MissionState.CLOSED)))
        assertEquals("a closed-only list is still populated", MissionsListState.POPULATED, missionsListState(populated))
        assertEquals(MissionsListState.UNSUPPORTED, missionsListState(populated.copy(isSupported = false)))
        assertEquals("Closed (2)", missionsClosedHeaderText(2))
        assertEquals("Show closed missions", missionsClosedToggleLabel(false))
        assertEquals("Hide closed missions", missionsClosedToggleLabel(true))
    }

    @Test
    fun milestoneCardSubtitleNamesTheKindAndFallsBackToTheNumber() {
        assertEquals("Your input · Missions & milestones", milestoneCardSubtitle(milestoneMarker()))
        assertEquals("Progress · #61", milestoneCardSubtitle(milestoneMarker(kind = MilestoneKind.PROGRESS, missionTitle = null)))
        assertEquals("Milestone 63, Dan asked. Your input · Missions & milestones. Opens the mission", milestoneCardAccessibilityLabel(milestoneMarker()))
    }

    @Test
    fun missionNoticeCopyPerAction() {
        fun m(action: MissionMarkerEvent.Action, title: String? = "Missions & milestones", open: List<Int> = emptyList()) =
            MissionMarkerEvent("ms_1", 61, title, action, ItemAuthor.AGENT, open)
        assertEquals("🏁 Mission #61 started · Missions & milestones", missionNoticeText(m(MissionMarkerEvent.Action.CREATED)))
        assertEquals("🏁 Joined mission #61", missionNoticeText(m(MissionMarkerEvent.Action.JOINED, title = null)))
        assertEquals("🏁 Mission #61 renamed · Missions & milestones", missionNoticeText(m(MissionMarkerEvent.Action.UPDATED)))
        assertEquals("🏁 Mission #61 closed · Missions & milestones", missionNoticeText(m(MissionMarkerEvent.Action.CLOSED)))
        assertEquals("🏁 Mission #61 · Missions & milestones closed over #64, #70", missionNoticeText(m(MissionMarkerEvent.Action.CLOSED, open = listOf(64, 70))))
        assertEquals("an empty title reads as absent", "🏁 Mission #61 closed", missionNoticeText(m(MissionMarkerEvent.Action.CLOSED, title = "")))
    }

    @Test
    fun closeConfirmationCountsOpenItems() {
        assertEquals("Close this mission?", missionCloseConfirmationTitle(0))
        assertEquals("Close with 1 item still open?", missionCloseConfirmationTitle(1))
        assertEquals("Close with 2 items still open?", missionCloseConfirmationTitle(2))
        assertNull(missionClosedOverText(mission()))
        assertEquals("Closed over 1 open item.", missionClosedOverText(mission(closedOver = 1)))
        assertEquals("Closed over 3 open items.", missionClosedOverText(mission(closedOver = 3)))
        assertEquals("No milestones yet.", missionMilestonesEmptyText(false))
        assertEquals("No milestones from you yet.", missionMilestonesEmptyText(true))
    }

    /// The milestone row speaks box NAMES, never the visual run's letters,
    /// and says nothing about a conversation this device never synced.
    @Test
    fun milestoneRowLabelSpeaksBoxNames() {
        val ms = Milestone("ml_1", "ms_1", 63, MilestoneKind.USER_INPUT, "Dan asked", convoID = "c1", seq = 20)
        assertEquals(
            "Your input 63, Dan asked, dev-2, bc. Opens the conversation at this point",
            milestoneRowAccessibilityLabel(MilestoneRow(ms, SessionTagInputs("D", "dev-2", "bc"))),
        )
        assertEquals(
            "Your input 63, Dan asked, dev-1, dev-2, bc. Opens the conversation at this point",
            milestoneRowAccessibilityLabel(MilestoneRow(ms, SessionTagInputs("D", "dev-2", "bc", roomBoxNames = listOf("dev-1", "dev-2"), roomBoxShorts = listOf("1", "2")))),
        )
        assertEquals("Your input 63, Dan asked. Opens the conversation at this point", milestoneRowAccessibilityLabel(MilestoneRow(ms, null)))
    }

    @Test
    fun detailModelMapsSessionTagsPerMilestoneConversation() {
        val a = Milestone("ml_1", "ms_1", 62, MilestoneKind.PROGRESS, "landed", convoID = "c1", seq = 10)
        val b = Milestone("ml_2", "ms_1", 63, MilestoneKind.USER_INPUT, "Dan said", convoID = "c9", seq = 20)
        val model = MissionDetailModel.from(
            mission = mission(), milestones = listOf(a, b), sessionTags = mapOf("c1" to SessionTagInputs("D", "dev-2", "bc")),
            openItems = emptyList(), conversations = emptyList(), showOnlyUserInput = false, closeSummary = "", isBusy = false,
        )
        assertEquals("dev-2", model.milestones[0].sessionTag?.boxName)
        assertNull("an unsynced conversation's row carries no tag", model.milestones[1].sessionTag)
        assertEquals(listOf("ml_1", "ml_2"), model.milestones.map { it.id })
    }
}
