package chat.matron.android.designsystem

import chat.matron.android.models.ConversationMissionLink
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionState
import chat.matron.android.models.Project
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMissionLineTest {
    private val t = Instant.parse("2026-11-20T12:00:00Z")
    private fun mission(id: String, num: Int, title: String, project: String? = null, state: MissionState = MissionState.OPEN, name: String? = null) =
        Mission(id = id, num = num, state = state, title = title, name = name, originConvoID = "c1", projectID = project, closedAt = if (state == MissionState.CLOSED) t else null)

    @Test
    fun theCurrentMissionLeadsAndTheOthersAreCounted() {
        val links = listOf(
            ConversationMissionLink(mission("ms_20", 20, "Docs search", project = "pj_docs"), isCurrent = true, joinedAt = t),
            ConversationMissionLink(mission("ms_21", 21, "API reference"), joinedAt = t.plusSeconds(60)),
            ConversationMissionLink(mission("ms_17", 17, "Pick a generator", state = MissionState.CLOSED), joinedAt = t.minusSeconds(86_400)),
        )
        val line = ChatMissionLine.from(links) { if (it == "pj_docs") "Docs site launch" else null }!!
        assertEquals("#20 Docs search +2", line.missionText)
        assertTrue(line.isCurrent)
        assertEquals("pj_docs", line.projectID)
        assertEquals("Docs site launch", line.projectTitle)
        assertEquals("Mission 20, Docs search, and 2 more. Opens the mission", line.missionLabel)
        assertEquals("Project: Docs site launch. Opens the project", line.projectLabel)
    }

    @Test
    fun withoutACurrentMissionTheNewestLiveOneLeadsThenTheLatestEarlierOne() {
        val live = ChatMissionLine.from(listOf(
            ConversationMissionLink(mission("ms_1", 1, "Older"), joinedAt = t),
            ConversationMissionLink(mission("ms_2", 2, "Newer", name = "New"), joinedAt = t.plusSeconds(60)),
        ))!!
        assertEquals("#2 New +1", live.missionText)
        assertFalse(live.isCurrent)
        val earlier = ChatMissionLine.from(listOf(ConversationMissionLink(mission("ms_3", 3, "Done", state = MissionState.CLOSED))))!!
        assertEquals("#3 Done", earlier.missionText)
        assertNull(earlier.projectLabel)
        assertNull(ChatMissionLine.from(emptyList()))
    }

    @Test
    fun moveToProjectOffersSomewhereElse() {
        val docs = Project(id = "pj_docs", num = 12, title = "Docs site launch")
        assertEquals("Move to project…", MoveToProjectCopy.label("pj_docs"))
        assertEquals("Add to a project…", MoveToProjectCopy.label(null))
        assertFalse(MoveToProjectCopy.hasTargets("pj_docs", listOf(docs)))
        assertTrue(MoveToProjectCopy.hasTargets(null, listOf(docs)))
    }
}
