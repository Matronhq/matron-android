package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.FinishedProject
import chat.matron.android.models.StalledList
import chat.matron.android.models.StalledProject
import chat.matron.android.models.TimelinePlace
import chat.matron.android.models.TimelineProjectList
import chat.matron.android.viewmodels.TimelineListFormat
import java.time.Instant

/// The words of the lists below the weeks.
object TimelineListCopy {
    const val ALL_PROJECTS = "All projects"
    const val WORTH_A_LOOK = "Worth a look"
    const val WENT_QUIET = "Went quiet"
    const val OPEN_LONG = "Open a long time"
    const val FINISHED = "Finished"
    const val QUIET_LEDE = "These were moving, then stopped without being finished."
    const val LONG_LEDE = "These have been open for three weeks or more."
    const val NO_PROJECTS = "No projects yet."
    const val NOTHING_FINISHED = "Nothing finished yet."
    const val NOTHING_STALLED = "Nothing here right now."
    const val ASK_ACTION = "Ask the Coordinator what to do with it"
    const val ASKED_ACTION = "Asked the Coordinator"
    const val CHART_CAPTION = "last 7 weeks"
    const val MORE_FAILED = "Couldn't load the rest."

    fun title(place: TimelinePlace): String = when (place) {
        TimelinePlace.Projects -> ALL_PROJECTS
        TimelinePlace.Quiet -> WENT_QUIET
        TimelinePlace.Long -> OPEN_LONG
        TimelinePlace.Finished -> FINISHED
        is TimelinePlace.Week -> TimelineFormat.PROJECTS
    }

    /// The path segment a list is pushed under, and back.
    fun wire(place: TimelinePlace): String? = when (place) {
        TimelinePlace.Projects -> "projects"
        TimelinePlace.Quiet -> "quiet"
        TimelinePlace.Long -> "long"
        TimelinePlace.Finished -> "finished"
        is TimelinePlace.Week -> null
    }

    fun place(wire: String?): TimelinePlace? = when (wire) {
        "projects" -> TimelinePlace.Projects
        "quiet" -> TimelinePlace.Quiet
        "long" -> TimelinePlace.Long
        "finished" -> TimelinePlace.Finished
        else -> null
    }

    /// The rows at the foot of the week page: every project first, unless
    /// the journal has no all-projects list, then the "Worth a look" lists.
    fun footPlaces(showsAllProjects: Boolean): List<TimelinePlace> =
        (if (showsAllProjects) listOf(TimelinePlace.Projects) else emptyList()) +
            listOf(TimelinePlace.Quiet, TimelinePlace.Long, TimelinePlace.Finished)
}

/// "All projects", then "Worth a look": each row opens its list, with its
/// count when known (the journal sends no finished total, so that row has
/// none). Added to the foot of the week page.
fun LazyListScope.timelineFootRows(
    showsAllProjects: Boolean,
    counts: (TimelinePlace) -> Int?,
    onOpenList: (TimelinePlace) -> Unit,
) {
    val places = TimelineListCopy.footPlaces(showsAllProjects)
    places.forEachIndexed { index, place ->
        item(key = "foot:${TimelineListCopy.wire(place)}") {
            Column {
                if (place == TimelinePlace.Quiet) {
                    Text(
                        TimelineListCopy.WORTH_A_LOOK.uppercase(), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp).semantics { heading() },
                    )
                } else if (index == 0) {
                    Spacer(Modifier.height(16.dp))
                } else {
                    HorizontalDivider()
                }
                FootRow(place, counts(place), onOpenList)
            }
        }
    }
}

@Composable
private fun FootRow(place: TimelinePlace, count: Int?, onOpen: (TimelinePlace) -> Unit) {
    val icon: ImageVector = when (place) {
        TimelinePlace.Projects -> Icons.Outlined.Layers
        TimelinePlace.Quiet -> Icons.Outlined.Contrast
        TimelinePlace.Long -> Icons.Outlined.HourglassEmpty
        else -> Icons.Filled.Check
    }
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(place) }.padding(vertical = 12.dp).testTag("timeline.foot.${TimelineListCopy.wire(place)}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(TimelineListCopy.title(place), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        count?.let { Text("$it", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ListLede(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 14.dp))
}

/// The end of a paged list: composing it asks for the next page; after a
/// failed read it offers Retry instead, which asks again.
private fun LazyListScope.pageEnd(rowCount: Int, onLoadMore: (() -> Unit)?, failed: Boolean) {
    if (onLoadMore == null) return
    if (failed) {
        item(key = "retry:$rowCount") {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(TimelineListCopy.MORE_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onLoadMore, modifier = Modifier.testTag("timeline.list.retry")) { Text(TimelineFormat.RETRY) }
            }
        }
        return
    }
    item(key = "end:$rowCount") {
        LaunchedEffect(rowCount) { onLoadMore() }
        Spacer(Modifier.height(1.dp))
    }
}

@Composable
private fun ProjectListRow(title: String, meta: String?, testTag: String, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp).testTag(testTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            meta?.let {
                Spacer(Modifier.width(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        HorizontalDivider()
    }
}

/// "All projects": every project, the most recently moved first, each with
/// when it last moved. Reaching the end loads the next page ([onLoadMore]
/// null when there is none).
@Composable
fun AllProjectsListView(list: TimelineProjectList, now: Instant, onOpenProject: (String) -> Unit, onLoadMore: (() -> Unit)?, modifier: Modifier = Modifier, pagingFailed: Boolean = false) {
    LazyColumn(modifier.fillMaxSize().testTag("timeline.projects"), contentPadding = PaddingValues(16.dp)) {
        if (list.rows.isEmpty()) item(key = "empty") { ListLede(TimelineListCopy.NO_PROJECTS) }
        items(list.rows, key = { it.project.id }) { row ->
            ProjectListRow(row.project.title, TimelineListFormat.moved(row.lastMovedAt, now), "timeline.projects.${row.project.num}") { onOpenProject(row.project.id) }
        }
        pageEnd(list.rows.size, onLoadMore, pagingFailed)
    }
}

/// "Finished": projects with nothing left open, newest first, each with when
/// it finished. Reaching the end loads the next page.
@Composable
fun FinishedListView(rows: List<FinishedProject>, now: Instant, onOpenProject: (String) -> Unit, onLoadMore: (() -> Unit)?, modifier: Modifier = Modifier, pagingFailed: Boolean = false) {
    LazyColumn(modifier.fillMaxSize().testTag("timeline.finished"), contentPadding = PaddingValues(16.dp)) {
        if (rows.isEmpty()) item(key = "empty") { ListLede(TimelineListCopy.NOTHING_FINISHED) }
        items(rows, key = { it.project.id }) { row ->
            ProjectListRow(row.project.title, TimelineListFormat.daysAgo(row.finishedAt, now), "timeline.finished.${row.project.num}") { onOpenProject(row.project.id) }
        }
        pageEnd(rows.size, onLoadMore, pagingFailed)
    }
}

/// "Went quiet" or "Open a long time": a short lede, then one row per project
/// with where it was left, when it last moved, a small chart of its last
/// seven weeks and one action, asking the Coordinator about it (when there
/// is one to ask). The row opens the project.
@Composable
fun StalledListView(
    lede: String,
    list: StalledList,
    now: Instant,
    asked: Set<String>,
    onOpenProject: (String) -> Unit,
    onAsk: ((StalledProject) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag("timeline.stalled"), contentPadding = PaddingValues(16.dp)) {
        item(key = "lede") { ListLede(lede) }
        if (list.rows.isEmpty()) item(key = "empty") { Text(TimelineListCopy.NOTHING_STALLED, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(list.rows, key = { it.project.id }) { row ->
            Column {
                HorizontalDivider()
                StalledRow(row, now, row.project.id in asked, onOpenProject, onAsk)
            }
        }
        TimelineListFormat.moreRows(list.total - list.rows.size)?.let { more ->
            item(key = "more") {
                HorizontalDivider()
                Text(more, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp))
            }
        }
    }
}

@Composable
private fun StalledRow(row: StalledProject, now: Instant, asked: Boolean, onOpen: (String) -> Unit, onAsk: ((StalledProject) -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Open project") { onOpen(row.project.id) }.padding(vertical = 18.dp).testTag("timeline.stalled.${row.project.num}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(row.project.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            ActivityChart(row.weekly)
        }
        row.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        TimelineListFormat.lastMoved(row.lastMovedAt, now)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            asked -> Text(TimelineListCopy.ASKED_ACTION, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onAsk != null -> Text(
                "${TimelineListCopy.ASK_ACTION} ›", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onAsk(row) }
                    .semantics { contentDescription = TimelineListCopy.ASK_ACTION }
                    .testTag("timeline.stalled.ask.${row.project.num}"),
            )
        }
    }
}

/// Seven weeks of moves as small bars, newest on the right; a week without
/// any is a short grey dash. Pale, so it reads as a hint.
@Composable
fun ActivityChart(weekly: List<Int>, modifier: Modifier = Modifier) {
    val bars = TimelineListFormat.chartBars(weekly)
    val peak = maxOf(bars.maxOrNull() ?: 0, 1)
    val label = "Moves in the last seven weeks, oldest first: ${bars.joinToString(", ")}"
    Column(modifier.clearAndSetSemantics { contentDescription = label }, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.height(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            bars.forEach { count ->
                if (count > 0) {
                    Box(Modifier.width(7.dp).height((18f * count / peak).coerceAtLeast(4f).dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
                } else {
                    Box(Modifier.width(7.dp).height(2.5.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
                }
            }
        }
        Text(TimelineListCopy.CHART_CAPTION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
