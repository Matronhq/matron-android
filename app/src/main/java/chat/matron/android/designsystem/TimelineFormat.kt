package chat.matron.android.designsystem

import chat.matron.android.models.TimelineMarks
import chat.matron.android.models.TimelineProjectRef
import chat.matron.android.models.TimelineStory
import chat.matron.android.models.TimelineWeek
import chat.matron.android.models.TimelineWeekExtras
import chat.matron.android.models.TimelineWeekSummary
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/// The Projects timeline's words: week names, a card's sentence and marks.
/// Ported from matron-apple's `TimelineFormat`.
object TimelineFormat {
    const val PROJECTS = "Projects"
    const val EARLIER_WEEKS = "Earlier weeks"
    /// A week's page when no project moved in it.
    const val NOTHING_MOVED = "Nothing moved this week."
    const val NOTHING_YET = "Nothing has moved yet"
    const val NOTHING_YET_DETAIL = "Each week, the projects that moved show here."
    const val FAILED = "Couldn't load this"
    const val FAILED_DETAIL = "The journal didn't answer. Check the connection and try again."
    const val TRY_AGAIN = "Try again"
    /// The mark of a card whose project says what comes next.
    const val NEXT_MARK = "→ Next"
    /// Cards on a week's page from a journal that doesn't page the rest;
    /// projects beyond them sit behind the "Also moved" line.
    const val MAX_CARDS = 8
    /// A story the journal gave no sentence (the project moved without a milestone).
    const val MOVED_SENTENCE = "Moved this week."
    const val LAYOUT_SWITCH_LABEL = "Show projects as"
    const val LOADING_MORE_PROJECTS = "Loading more projects"
    const val MORE_PROJECTS_FAILED = "Couldn't load the other projects."
    const val RETRY = "Retry"
    const val THIS_WEEK = "This week"
    const val LAST_WEEK = "Last week"

    private val month: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.UK)
    private val monthShort: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    // Weeks

    /// "2 – 8 Nov", "26 Oct – 1 Nov"; null for a key that is not a date.
    fun shortRange(week: String): String? = range(week, monthShort)

    /// "2 – 8 November": the page's dates.
    fun longRange(week: String): String? = range(week, month)

    /// Week keys are the Monday's date in the user's zone, so the days are
    /// read off the key itself, never shifted by this device's zone.
    private fun range(week: String, full: DateTimeFormatter): String? {
        val start = try {
            LocalDate.parse(week)
        } catch (_: DateTimeParseException) {
            return null
        }
        val end = start.plusDays(6)
        val first = if (start.month == end.month) start.dayOfMonth.toString() else start.format(full)
        return "$first – ${end.format(full)}"
    }

    /// A week's name in the menu, from its dates: the week [now] falls in is
    /// this week, the one before it last week, any other its dates — never
    /// from its place in the list, which a dropped row would shift.
    fun menuTitle(week: TimelineWeekSummary, now: Instant): String = when {
        !now.isBefore(week.start) && now.isBefore(week.end) -> THIS_WEEK
        !now.isBefore(week.end) && now.isBefore(week.end.plus(Duration.ofDays(7))) -> LAST_WEEK
        else -> shortRange(week.week) ?: week.week
    }

    /// The page title: the week still being written is this week, the one
    /// that ended within the last seven days last week, any other its dates.
    fun pageTitle(week: TimelineWeek, now: Instant): String = when {
        !week.final -> THIS_WEEK
        endedLastWeek(week, now) -> LAST_WEEK
        else -> longRange(week.week) ?: week.week
    }

    /// The dates beside a "This week" / "Last week" title; null when the
    /// title is the dates.
    fun pageDates(week: TimelineWeek, now: Instant): String? =
        if (!week.final || endedLastWeek(week, now)) longRange(week.week) else null

    private fun endedLastWeek(week: TimelineWeek, now: Instant): Boolean =
        week.final && now.isBefore(week.end.plus(Duration.ofDays(7)))

    /// No project moved that week.
    fun movedNothing(week: TimelineWeek): Boolean = week.stories.isEmpty() && week.also.isEmpty()

    /// The week page's cards: every story and the projects paged in past
    /// them when the journal pages them, else the first [MAX_CARDS] stories.
    fun cards(week: TimelineWeek, extras: TimelineWeekExtras?): List<TimelineStory> {
        if (extras == null || extras.status == TimelineWeekExtras.Status.UNSUPPORTED) return week.stories.take(MAX_CARDS)
        return week.stories + extras.projects.map { it.story }
    }

    /// Whether the other projects are paged in rather than counted in a line.
    fun pagesProjects(extras: TimelineWeekExtras?): Boolean =
        extras != null && extras.status != TimelineWeekExtras.Status.UNSUPPORTED

    /// The projects behind the foot line: those of the stories past the last
    /// card, then the ones the journal lists as also moving.
    fun alsoMovedProjects(week: TimelineWeek): List<TimelineProjectRef> =
        week.stories.drop(MAX_CARDS).map { it.project } + week.also

    /// "Also moved: 7 projects"; null when there are none.
    fun alsoMoved(count: Int): String? = if (count > 0) "Also moved: $count project${if (count == 1) "" else "s"}" else null

    // Stories

    fun sentence(story: TimelineStory): String = story.sentence?.trim()?.takeIf { it.isNotEmpty() } ?: MOVED_SENTENCE

    /// What TalkBack says for a card or row: the name, the sentence, the marks.
    fun rowLabel(story: TimelineStory): String =
        (listOf(story.project.title, sentence(story)) + listOfNotNull(marksLabel(story.marks)))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.removeSuffix(".") }
            .joinToString(". ")

    // Marks

    /// "2 for you": follows the red dot.
    fun forYouMark(count: Int): String = "$count for you"

    /// "✓ 5 done".
    fun doneMark(count: Int): String = "✓ $count done"

    /// The marks as they are spoken: "2 waiting for you, 5 done, has a next
    /// step", only those that apply; null when there are none.
    fun marksLabel(marks: TimelineMarks): String? {
        val parts = buildList {
            if (marks.forYou > 0) add("${marks.forYou} waiting for you")
            if (marks.done > 0) add("${marks.done} done")
            if (marks.next) add("has a next step")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}

/// How a week page lays out its projects: a card each, or a compact row
/// each. Kept per device under [STORAGE_KEY].
enum class TimelineWeekLayout(val wire: String, val title: String) {
    TILES("tiles", "Tiles"), LIST("list", "List");

    companion object {
        const val STORAGE_KEY = "projects.weekLayout"
        val DEFAULT = TILES

        fun fromWire(raw: String?): TimelineWeekLayout = entries.firstOrNull { it.wire == raw } ?: DEFAULT
    }
}
