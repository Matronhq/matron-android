package chat.matron.android.designsystem

import chat.matron.android.models.MilestoneKind
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionLastMilestone
import chat.matron.android.models.TimelineHistoryWeek
import chat.matron.android.models.TimelineItemRef
import chat.matron.android.models.TimelineProjectPage
import chat.matron.android.models.TimelineProjectRef
import chat.matron.android.features.projects.ProjectScreenContent
import chat.matron.android.viewmodels.ProjectPageState
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPageFormatTest {
    private val docs = TimelineProjectRef("pj_docs", 12, "Docs site launch")

    @Test
    fun sectionRowsCountWhatIsBehindThem() {
        val page = TimelineProjectPage(docs, weeks = 1, missionsOpen = 2, missionsDone = 3, files = 0)
        assertEquals("1 week", ProjectFormat.sectionCount(ProjectSection.HISTORY, page))
        assertEquals("2 open, 3 done", ProjectFormat.sectionCount(ProjectSection.MISSIONS, page))
        assertTrue(ProjectFormat.sectionIsEmpty(ProjectSection.FILES, page))
        assertFalse(ProjectFormat.sectionIsEmpty(ProjectSection.HISTORY, page))
        assertEquals(ProjectSection.FILES, ProjectSection.fromWire("files"))
        assertNull(ProjectSection.fromWire("nope"))
    }

    @Test
    fun waitingListCountsWhatItDoesNotList() {
        val items = listOf(TimelineItemRef("it_1", 31, "Pick a theme"))
        assertEquals("and 2 more", ProjectFormat.waitingList(TimelineProjectPage(docs, needsYou = items, needsYouTotal = 3)).more)
        assertNull(ProjectFormat.waitingList(TimelineProjectPage(docs, needsYou = items)).more)
    }

    @Test
    fun historyWeeksNameThemselvesFromNow() {
        val start = Instant.parse("2026-11-16T00:00:00Z")
        val week = TimelineHistoryWeek("2026-11-16", start, start.plusSeconds(604_800))
        assertEquals("This week", ProjectFormat.historyWeekTitle(week, start.plusSeconds(3600)))
        assertEquals("Last week", ProjectFormat.historyWeekTitle(week, start.plusSeconds(8 * 86_400)))
        assertEquals("16 – 22 Nov", ProjectFormat.historyWeekTitle(week, start.plusSeconds(20 * 86_400)))
        assertEquals("Tuesday", ProjectFormat.weekday(Instant.parse("2026-11-17T09:00:00Z"), ZoneOffset.UTC))
        assertEquals("4 more", ProjectFormat.moreMilestones(4))
        assertNull(ProjectFormat.moreMilestones(0))
    }

    @Test
    fun missionLinesPreferTheStatus() {
        val now = Instant.parse("2026-11-20T12:00:00Z")
        val base = Mission(id = "ms_1", num = 20, title = "Docs search", originConvoID = "c1")
        assertEquals("Indexing the API pages.", ProjectFormat.missionLine(base.copy(status = "Indexing\n the API pages."), now))
        assertEquals(
            "No status · last milestone 2h: “Index built”",
            ProjectFormat.missionLine(base.copy(lastMilestone = MissionLastMilestone(40, "Index built", MilestoneKind.PROGRESS, now.minusSeconds(7200))), now),
        )
        assertEquals("No status yet", ProjectFormat.missionLine(base, now))
        assertEquals("Closed", ProjectFormat.closedMissionLine(base.copy(closeSummary = "  ")))
        assertEquals("Shipped it.", ProjectFormat.closedMissionLine(base.copy(closeSummary = "Shipped it.")))
    }

    @Test
    fun aMissingProjectWinsOverAStalePage() {
        val page = TimelineProjectPage(docs)
        assertEquals(ProjectScreenContent.Missing, ProjectScreenContent.of(ProjectPageState(page = page, isMissing = true)))
        assertEquals(ProjectScreenContent.Page, ProjectScreenContent.of(ProjectPageState(page = page, error = "offline")))
        assertEquals(ProjectScreenContent.Failed, ProjectScreenContent.of(ProjectPageState(error = "offline")))
    }

    @Test
    fun aSectionThatFailedBeforeLoadingOffersTheRetryNotAnAlert() {
        val failed = ProjectPageState(page = TimelineProjectPage(docs), error = "offline")
        assertFalse(chat.matron.android.features.projects.sectionLoaded(failed, ProjectSection.HISTORY))
        assertTrue(chat.matron.android.features.projects.sectionLoaded(failed.copy(historyLoaded = true), ProjectSection.HISTORY))
        assertTrue(chat.matron.android.features.projects.sectionLoaded(failed.copy(openMissions = emptyList()), ProjectSection.MISSIONS))
    }

    @Test
    fun theMissionChipNamesItsProject() {
        val mission = Mission(id = "ms_1", num = 20, title = "Docs search", originConvoID = "c1", projectID = "pj_docs", projectNum = 12)
        assertEquals(MissionProjectChip("pj_docs", "Docs site launch"), missionProjectChip(mission, "Docs site launch"))
        assertEquals("Project #12", missionProjectChip(mission, null)?.title)
        assertNull(missionProjectChip(mission.copy(projectID = null), "Docs site launch"))
        assertEquals("Project: Docs site launch. Opens the project", missionProjectChip(mission, "Docs site launch")?.accessibilityLabel)
    }
}
