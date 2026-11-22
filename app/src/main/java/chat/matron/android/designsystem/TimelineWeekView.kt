package chat.matron.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.TimelineMarks
import chat.matron.android.models.TimelineMedia
import chat.matron.android.models.TimelineStory
import chat.matron.android.models.TimelineWeek
import chat.matron.android.models.TimelineWeekExtras
import coil.compose.AsyncImage
import java.time.Instant

/// Page widths from here up are wide: story cards two to a row.
private val WIDE_WIDTH = 700.dp
/// A card's media area, width over height: wide, so two cards side by side
/// keep their words in view.
private const val MEDIA_ASPECT = 2.4f

/// One week of the Projects timeline: its dates, the tiles / list switch,
/// then a card (or, in list layout, a compact row) for each project that
/// moved: the week's stories first, then the other projects, most active
/// first, paged in as the page reaches its end ([extras], with a loading row
/// or a Retry at the end). From a journal that does not page the other
/// projects the page shows eight cards at most and one quiet line for the
/// rest, which opens into a plain list of them. A week where nothing moved
/// says so. [footer] follows inside the scroll. A pure leaf: the host passes
/// the week, its paged projects, the loaded pictures by blob id (bytes Coil
/// decodes), the layout and the actions. Ported from matron-apple's
/// `TimelineWeekView`.
@Composable
fun TimelineWeekView(
    week: TimelineWeek,
    extras: TimelineWeekExtras?,
    now: Instant,
    images: Map<String, Any>,
    layout: TimelineWeekLayout,
    onLayoutChange: (TimelineWeekLayout) -> Unit,
    onOpenProject: ((String) -> Unit)?,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    footer: LazyListScope.() -> Unit = {},
) {
    val shownExtras = extras?.takeIf { it.week == week.week }
    val pages = TimelineFormat.pagesProjects(shownExtras)
    val stories = TimelineFormat.cards(week, shownExtras)
    var showsAlso by rememberSaveable(week.week) { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (maxWidth >= WIDE_WIDTH) 2 else 1
        LazyColumn(
            Modifier.fillMaxSize().testTag("timeline.week.${week.week}"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        ) {
            item(key = "top") { WeekTop(week, now, layout, onLayoutChange) }
            if (TimelineFormat.movedNothing(week)) {
                item(key = "empty") {
                    Text(
                        TimelineFormat.NOTHING_MOVED, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            when (layout) {
                TimelineWeekLayout.TILES -> {
                    val rows = stories.chunked(columns)
                    rows.forEachIndexed { index, row ->
                        item(key = "cards:$index") {
                            Row(
                                Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = if (index == 0) 4.dp else 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                row.forEach { story ->
                                    TimelineStoryCard(
                                        story, story.media?.let { images[it.blobID] }, onOpenProject,
                                        Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                                if (row.size < columns) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                TimelineWeekLayout.LIST -> {
                    stories.forEachIndexed { index, story ->
                        item(key = "row:$index") {
                            Column {
                                if (index > 0) HorizontalDivider(Modifier.padding(start = 68.dp))
                                TimelineWeekRow(story, story.media?.let { images[it.blobID] }, onOpenProject)
                            }
                        }
                    }
                }
            }
            if (pages && shownExtras != null) {
                item(key = "end:${shownExtras.projects.size}:${shownExtras.status}") { PageEnd(shownExtras, onLoadMore) }
            } else {
                val also = TimelineFormat.alsoMovedProjects(week)
                TimelineFormat.alsoMoved(also.size)?.let { line ->
                    item(key = "also") {
                        Text(
                            "$line ›",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClickLabel = line) { showsAlso = !showsAlso }
                                .padding(vertical = 16.dp)
                                .semantics { contentDescription = line },
                        )
                    }
                    if (showsAlso) {
                        also.forEachIndexed { index, project ->
                            item(key = "also:$index") {
                                Text(
                                    project.title, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(if (onOpenProject != null) Modifier.clickable { onOpenProject(project.id) } else Modifier)
                                        .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            footer()
        }
    }
}

/// The page's dates and the layout switch beside them (the week's name is
/// in the bar's menu).
@Composable
private fun WeekTop(week: TimelineWeek, now: Instant, layout: TimelineWeekLayout, onLayoutChange: (TimelineWeekLayout) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val dates = TimelineFormat.pageDates(week, now) ?: TimelineFormat.pageTitle(week, now)
        Text(
            dates, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (!TimelineFormat.movedNothing(week)) {
            SingleChoiceSegmentedButtonRow(Modifier.semantics { contentDescription = TimelineFormat.LAYOUT_SWITCH_LABEL }) {
                TimelineWeekLayout.entries.forEachIndexed { index, choice ->
                    SegmentedButton(
                        selected = layout == choice,
                        onClick = { onLayoutChange(choice) },
                        shape = SegmentedButtonDefaults.itemShape(index, TimelineWeekLayout.entries.size),
                        icon = {},
                        modifier = Modifier.testTag("timeline.week.layout.${choice.wire}"),
                    ) {
                        Icon(
                            if (choice == TimelineWeekLayout.TILES) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = choice.title,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/// The end of the paged projects: a loading row (composing it asks for the
/// next page), or Retry when the last read failed. It is a lazy item, so it
/// composes only when scrolled near.
@Composable
private fun PageEnd(extras: TimelineWeekExtras, onLoadMore: () -> Unit) {
    when (extras.status) {
        TimelineWeekExtras.Status.MORE, TimelineWeekExtras.Status.LOADING -> {
            LaunchedEffect(extras.week, extras.projects.size, extras.status) {
                if (extras.status == TimelineWeekExtras.Status.MORE) onLoadMore()
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
                Text(TimelineFormat.LOADING_MORE_PROJECTS, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TimelineWeekExtras.Status.FAILED -> Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(TimelineFormat.MORE_PROJECTS_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onLoadMore) { Text(TimelineFormat.RETRY) }
        }
        TimelineWeekExtras.Status.DONE, TimelineWeekExtras.Status.UNSUPPORTED -> Unit
    }
}

/// One project's week on a week page: its picture or video (no media area
/// when there is none), the project's name, one sentence on what was done,
/// and its marks. The whole card opens its project when [onOpen] is set.
@Composable
fun TimelineStoryCard(story: TimelineStory, image: Any?, onOpen: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val label = TimelineFormat.rowLabel(story)
    OutlinedCard(
        modifier = modifier
            .testTag("timeline.story.${story.project.num}")
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open project") { onOpen(story.project.id) } else Modifier)
            .clearAndSetSemantics { contentDescription = label },
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            story.media?.let { TimelineMediaView(it, image, Modifier.fillMaxWidth().aspectRatio(MEDIA_ASPECT)) }
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(story.project.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(TimelineFormat.sentence(story), style = MaterialTheme.typography.bodyLarge)
                if (!story.marks.isEmpty) TimelineMarksLine(story.marks, Modifier.padding(top = 2.dp))
            }
        }
    }
}

/// One project's week as a compact row in list layout: a small square of
/// its picture (a quiet glyph when it has none, a play mark for a video),
/// the name, the sentence in two lines at most, and the marks.
@Composable
fun TimelineWeekRow(story: TimelineStory, image: Any?, onOpen: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val label = TimelineFormat.rowLabel(story)
    Row(
        modifier
            .fillMaxWidth()
            .testTag("timeline.week.row.${story.project.num}")
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open project") { onOpen(story.project.id) } else Modifier)
            .clearAndSetSemantics { contentDescription = label }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            val media = story.media
            when {
                media == null -> Icon(Icons.Outlined.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                media.isVideo -> Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                image != null -> AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(story.project.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                TimelineFormat.sentence(story), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (!story.marks.isEmpty) TimelineMarksLine(story.marks, Modifier.padding(top = 2.dp))
        }
    }
}

/// A card's marks, in one line where it fits: a red dot and "2 for you",
/// "✓ 5 done", "→ Next"; only those that apply. Counts and a word, never an
/// item's title.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimelineMarksLine(marks: TimelineMarks, modifier: Modifier = Modifier) {
    val label = TimelineFormat.marksLabel(marks) ?: return
    FlowRow(
        modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val style = MaterialTheme.typography.bodyMedium
        if (marks.forYou > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(MatronRed))
                Text(TimelineFormat.forYouMark(marks.forYou), style = style, color = MatronRed)
            }
        }
        if (marks.done > 0) Text(TimelineFormat.doneMark(marks.done), style = style, color = MatronGreen)
        if (marks.next) Text(TimelineFormat.NEXT_MARK, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/// A story's one picture or video. A picture still loading is a quiet
/// placeholder of the same size; a video (no poster frames yet) is a dark
/// panel with a play mark and its name.
@Composable
fun TimelineMediaView(media: TimelineMedia, image: Any?, modifier: Modifier = Modifier) {
    val description = if (media.isVideo) "Video ${media.name}" else "Picture ${media.name}"
    Box(modifier.clearAndSetSemantics { contentDescription = description }) {
        when {
            media.isVideo -> Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
                Box(
                    Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White) }
                Text(
                    media.name, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.75f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
            image != null -> AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)))
        }
    }
}
