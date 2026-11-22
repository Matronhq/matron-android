package chat.matron.android.designsystem

import chat.matron.android.features.projects.projectsBarTitle
import chat.matron.android.features.projects.weekChoices
import chat.matron.android.models.TimelineMarks
import chat.matron.android.models.TimelineProjectRef
import chat.matron.android.models.TimelineStory
import chat.matron.android.models.TimelineWeek
import chat.matron.android.models.TimelineWeekExtras
import chat.matron.android.models.TimelineWeekProject
import chat.matron.android.models.TimelineWeekSummary
import chat.matron.android.viewmodels.TimelineState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineFormatTest {
    private val start = Instant.parse("2026-11-16T00:00:00Z")
    private val end = start.plusSeconds(7 * 86_400)
    private fun ref(n: Int) = TimelineProjectRef("pj_$n", n, "Project $n")
    private fun week(final: Boolean, stories: Int = 0, also: Int = 0) =
        TimelineWeek("2026-11-16", start, end, final, stories = (1..stories).map { TimelineStory.of(ref(it)) }, also = (1..also).map { ref(100 + it) })

    @Test
    fun rangesReadOffTheKey() {
        assertEquals("16 – 22 November", TimelineFormat.longRange("2026-11-16"))
        assertEquals("26 Oct – 1 Nov", TimelineFormat.shortRange("2026-10-26"))
        assertNull(TimelineFormat.shortRange("not a week"))
    }

    @Test
    fun titlesNameThisWeekLastWeekThenDates() {
        assertEquals("This week", TimelineFormat.pageTitle(week(final = false), start))
        assertEquals("Last week", TimelineFormat.pageTitle(week(final = true), end.plusSeconds(86_400)))
        assertEquals("16 – 22 November", TimelineFormat.pageTitle(week(final = true), end.plusSeconds(8 * 86_400)))
        assertNull(TimelineFormat.pageDates(week(final = true), end.plusSeconds(8 * 86_400)))
        assertEquals("16 – 22 November", TimelineFormat.pageDates(week(final = false), start))
        val summaries = listOf("2026-11-16", "2026-11-09", "2026-11-02").map { TimelineWeekSummary(it, start, end) }
        assertEquals(listOf("This week", "Last week", "2 – 8 Nov"), weekChoices(summaries).map { it.title })
        assertEquals("Last week", projectsBarTitle(TimelineState(weeks = summaries, selectedWeek = "2026-11-09")))
        assertEquals("Projects", projectsBarTitle(TimelineState()))
    }

    @Test
    fun cardsCapAtEightUnlessTheJournalPagesTheRest() {
        val busy = week(final = false, stories = 10, also = 2)
        assertEquals(8, TimelineFormat.cards(busy, null).size)
        assertEquals(listOf(9, 10, 101, 102), TimelineFormat.alsoMovedProjects(busy).map { it.num })
        assertEquals("Also moved: 4 projects", TimelineFormat.alsoMoved(4))
        assertEquals("Also moved: 1 project", TimelineFormat.alsoMoved(1))
        assertNull(TimelineFormat.alsoMoved(0))
        val paged = TimelineWeekExtras("2026-11-16", listOf(TimelineWeekProject(ref(101))), TimelineWeekExtras.Status.MORE)
        assertEquals(11, TimelineFormat.cards(busy, paged).size)
        assertTrue(TimelineFormat.pagesProjects(paged))
        assertEquals(8, TimelineFormat.cards(busy, paged.copy(status = TimelineWeekExtras.Status.UNSUPPORTED)).size)
        assertTrue(TimelineFormat.movedNothing(week(final = true)))
    }

    @Test
    fun marksAndLabelsAreCountsNeverTitles() {
        assertEquals("2 waiting for you, 5 done, has a next step", TimelineFormat.marksLabel(TimelineMarks(2, 5, true)))
        assertNull(TimelineFormat.marksLabel(TimelineMarks()))
        assertEquals("✓ 5 done", TimelineFormat.doneMark(5))
        val story = TimelineStory.of(ref(1).copy(title = "Docs site launch"), sentence = "The docs went live.", marks = TimelineMarks(forYou = 1))
        assertEquals("Docs site launch. The docs went live. 1 waiting for you", TimelineFormat.rowLabel(story))
        assertEquals("Moved this week.", TimelineFormat.sentence(TimelineStory.of(ref(2), sentence = "  ")))
    }

    @Test
    fun theLayoutChoiceFallsBackToTiles() {
        assertEquals(TimelineWeekLayout.LIST, TimelineWeekLayout.fromWire("list"))
        assertEquals(TimelineWeekLayout.TILES, TimelineWeekLayout.fromWire(null))
        assertEquals(TimelineWeekLayout.TILES, TimelineWeekLayout.fromWire("grid"))
    }
}
