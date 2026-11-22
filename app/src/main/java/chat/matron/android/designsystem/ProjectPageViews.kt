package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.Mission
import chat.matron.android.models.ProjectMilestone
import chat.matron.android.models.TimelineHistoryWeek
import chat.matron.android.models.TimelineItemRef
import chat.matron.android.models.TimelineProjectPage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/// What a project's page opens one tap down.
enum class ProjectSection(val wire: String) {
    HISTORY("history"), MISSIONS("missions"), FILES("files");

    companion object {
        fun fromWire(raw: String?): ProjectSection? = entries.firstOrNull { it.wire == raw }
    }
}

/// The rows under "Needs you" on a project's page: the newest items
/// waiting, and "and N more" for the rest.
data class ProjectWaitingList(val rows: List<TimelineItemRef>, val more: String?)

/// A project page's words. Ported from matron-apple's `TimelineFormat`
/// (project page part) and `ProjectsFormat`.
object ProjectFormat {
    const val NEXT_HEADING = "Next"
    const val NEEDS_YOU_HEADING = "Needs you"
    const val DONE_HEADING = "Done"
    const val MISSING = "This project is gone"
    const val MISSING_DETAIL = "It may have been merged into another, or you no longer have access."

    fun sectionTitle(section: ProjectSection): String = when (section) {
        ProjectSection.HISTORY -> "This week and before"
        ProjectSection.MISSIONS -> "Missions"
        ProjectSection.FILES -> "Files"
    }

    /// What a section's row says is behind it: "6 weeks", "3 open, 4 done", "7".
    fun sectionCount(section: ProjectSection, page: TimelineProjectPage): String = when (section) {
        ProjectSection.HISTORY -> "${page.weeks} week${if (page.weeks == 1) "" else "s"}"
        ProjectSection.MISSIONS -> "${page.missionsOpen} open, ${page.missionsDone} done"
        ProjectSection.FILES -> "${page.files}"
    }

    /// Nothing is behind the row, so it can't be opened.
    fun sectionIsEmpty(section: ProjectSection, page: TimelineProjectPage): Boolean = when (section) {
        ProjectSection.HISTORY -> page.weeks <= 0
        ProjectSection.MISSIONS -> page.missionsOpen + page.missionsDone <= 0
        ProjectSection.FILES -> page.files <= 0
    }

    /// The page's waiting rows and "and 2 more" for those it only counts.
    fun waitingList(page: TimelineProjectPage): ProjectWaitingList {
        val more = page.needsYouTotal - page.needsYou.size
        return ProjectWaitingList(page.needsYou, if (more > 0) "and $more more" else null)
    }

    /// A week of a project's history: the week [now] falls in is this week,
    /// the one that ended within the last seven days last week, any other
    /// its dates ("19 – 25 Oct").
    fun historyWeekTitle(week: TimelineHistoryWeek, now: Instant): String = when {
        now.isBefore(week.end) -> TimelineFormat.THIS_WEEK
        now.isBefore(week.end.plus(Duration.ofDays(7))) -> TimelineFormat.LAST_WEEK
        else -> TimelineFormat.shortRange(week.week) ?: week.week
    }

    /// "4 more": a week's milestones beyond the ones listed; null when none.
    fun moreMilestones(count: Int): String? = if (count > 0) "$count more" else null

    /// "Tuesday": the day beside a milestone of a project's history.
    fun weekday(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        at.atZone(zone).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK)

    /// An open mission row's second line: its status, else its latest milestone.
    fun missionLine(mission: Mission, now: Instant): String {
        mission.status?.let { return oneLine(it) }
        mission.lastMilestone?.let { return "No status · last milestone ${RelativeMinuteTime.format(it.createdAt, now)}: “${it.title}”" }
        return "No status yet"
    }

    /// A finished mission row's second line: its close summary on one line,
    /// else "Closed".
    fun closedMissionLine(mission: Mission): String = mission.closeSummary?.let(::oneLine)?.takeIf { it.isNotEmpty() } ?: "Closed"

    fun oneLine(text: String): String = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /// The text, or null when it has no words.
    fun words(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }
}

/// A small grey heading over part of a project's page: "NEXT", "NEEDS YOU".
@Composable
fun ProjectPageHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.semantics { heading() },
    )
}

/// A row of a project's page: its content, an optional count at the end,
/// and a chevron when it opens something.
@Composable
fun ProjectPageRow(
    modifier: Modifier = Modifier,
    detail: String? = null,
    opens: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f)) { content() }
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        if (opens) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/// A project's page, kept light: its picture (no area without one), its
/// name with [titleActions] beside it, one sentence on where it stands, what
/// is next, what waits on the user, then a row for each of [sections] that
/// opens it. A row with nothing behind it is dimmed and can't be opened. A
/// pure leaf. Ported from matron-apple's `ProjectSummaryView`.
@Composable
fun ProjectSummaryView(
    page: TimelineProjectPage,
    image: Any?,
    sections: List<ProjectSection>,
    onOpenItem: (String) -> Unit,
    onOpenSection: (ProjectSection) -> Unit,
    modifier: Modifier = Modifier,
    titleActions: @Composable () -> Unit = {},
) {
    LazyColumn(modifier.fillMaxSize().testTag("projects.summary"), contentPadding = PaddingValues(16.dp)) {
        page.media?.let { media ->
            item(key = "picture") {
                TimelineMediaView(
                    media, image,
                    Modifier.fillMaxWidth().aspectRatio(2.4f).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                )
                Spacer(Modifier.height(20.dp))
            }
        }
        item(key = "words") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        page.project.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    titleActions()
                }
                ProjectFormat.words(page.sentence)?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal) }
            }
        }
        ProjectFormat.words(page.next)?.let { next ->
            item(key = "next") {
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectPageHeading(ProjectFormat.NEXT_HEADING)
                    Text(next, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        val waiting = ProjectFormat.waitingList(page)
        if (waiting.rows.isNotEmpty()) {
            item(key = "waiting") {
                Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectPageHeading(ProjectFormat.NEEDS_YOU_HEADING)
                    waiting.rows.forEach { item ->
                        val title = ProjectFormat.oneLine(item.title)
                        ProjectPageRow(
                            Modifier.testTag("projects.summary.waiting.${item.num}").clearAndSetSemantics { contentDescription = "Waiting for you: $title" },
                            onClick = { onOpenItem(item.id) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(MatronRed))
                                Text(title, color = MatronRed, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    waiting.more?.let { more ->
                        ProjectPageRow(opens = false) { Text(more, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        item(key = "sections") {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sections.forEach { section ->
                    val empty = ProjectFormat.sectionIsEmpty(section, page)
                    ProjectPageRow(
                        Modifier.alpha(if (empty) 0.45f else 1f).testTag("projects.summary.${section.wire}"),
                        detail = ProjectFormat.sectionCount(section, page),
                        onClick = if (empty) null else ({ onOpenSection(section) }),
                    ) { Text(ProjectFormat.sectionTitle(section), style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}

/// "This week and before": a project week by week, newest first. Each week
/// has its heading, that week's sentence and picture when the project had a
/// card, and its milestones with the day, then "4 more" for those not
/// listed. Reaching the end asks for older weeks while [hasMore]. Ported
/// from matron-apple's `ProjectHistoryView`.
@Composable
fun ProjectHistoryView(
    weeks: List<TimelineHistoryWeek>,
    now: Instant,
    images: Map<String, Any>,
    hasMore: Boolean,
    onOpenMilestone: (ProjectMilestone) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag("projects.history"), contentPadding = PaddingValues(16.dp)) {
        items(weeks, key = { it.week }) { week ->
            Column(Modifier.padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProjectPageHeading(ProjectFormat.historyWeekTitle(week, now))
                ProjectFormat.words(week.sentence)?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal) }
                week.media?.let { media ->
                    TimelineMediaView(
                        media, images[media.blobID],
                        Modifier.widthIn(max = 448.dp).fillMaxWidth().aspectRatio(2.4f).clip(RoundedCornerShape(12.dp)),
                    )
                }
                if (week.milestones.isNotEmpty() || week.moreMilestones > 0) {
                    Column {
                        week.milestones.forEach { milestone ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenMilestone(milestone) }.padding(vertical = 10.dp)
                                    .testTag("projects.history.milestone.${milestone.milestone.num}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    ProjectFormat.oneLine(milestone.milestone.title), style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.size(12.dp))
                                Text(ProjectFormat.weekday(milestone.milestone.createdAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                        ProjectFormat.moreMilestones(week.moreMilestones)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 9.dp))
                        }
                    }
                }
            }
        }
        if (hasMore) {
            item(key = "more:${weeks.size}") {
                LaunchedEffect(weeks.size) { onLoadMore() }
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

/// A project's missions: the open ones by name, each with one line on where
/// it stands, then the finished ones under "Done" with how they ended. A
/// row opens its mission. Ported from matron-apple's `ProjectMissionsListView`.
@Composable
fun ProjectMissionsListView(
    open: List<Mission>,
    closed: List<Mission>,
    now: Instant,
    onOpenMission: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag("projects.missions"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        items(open, key = { "open:${it.id}" }) { mission ->
            ProjectMissionRow(mission, ProjectFormat.missionLine(mission, now), onOpenMission)
        }
        if (closed.isNotEmpty()) {
            item(key = "done") { ProjectPageHeading(ProjectFormat.DONE_HEADING, Modifier.padding(top = if (open.isEmpty()) 0.dp else 28.dp, bottom = 10.dp)) }
            items(closed, key = { "closed:${it.id}" }) { mission ->
                ProjectMissionRow(mission, ProjectFormat.closedMissionLine(mission), onOpenMission)
            }
        }
    }
}

@Composable
private fun ProjectMissionRow(mission: Mission, line: String, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clickable { onOpen(mission.id) }.padding(vertical = 11.dp).testTag("projects.missions.${mission.num}"),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(mission.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider()
    }
}
