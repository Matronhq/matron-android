package chat.matron.android.models

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The Projects weekly timeline (journal `GET /timeline/...`): one summary per
// week, one week's stories, the went-quiet / open-a-long-time / finished
// lists, every project, one project's page and its weeks. Dates arrive as ms
// since epoch. Every `fromJson` returns null for a row missing a required
// field; the parents drop such rows and keep the rest. Ported from
// matron-apple's `Models/Timeline.swift`.

private fun msInstant(ms: Long?): Instant? = ms?.let { Instant.ofEpochMilli(it) }

/// Where the Projects timeline is looking: one week's stories, every
/// project, or one of the three lists below the weeks.
sealed interface TimelinePlace {
    data class Week(val key: String) : TimelinePlace
    /// Every project, the most recently moved first.
    data object Projects : TimelinePlace
    /// Projects that went quiet.
    data object Quiet : TimelinePlace
    /// Projects open a long time.
    data object Long : TimelinePlace
    data object Finished : TimelinePlace
}

/// The compact project reference every timeline read carries.
data class TimelineProjectRef(val id: String, val num: Int, val title: String) {
    companion object {
        fun fromJson(json: JsonObject?): TimelineProjectRef? {
            json ?: return null
            val id = json.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
            val num = json.intOrNull("num") ?: return null
            return TimelineProjectRef(id, num, json.stringOrNull("title") ?: "")
        }
    }
}

/// An item a week's story names as needing the user: open (waiting), or
/// [done] (named by the story, since sorted out).
data class TimelineItemRef(val id: String, val num: Int, val title: String, val done: Boolean = false) {
    companion object {
        fun fromJson(json: JsonObject): TimelineItemRef? {
            val id = json.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
            val num = json.intOrNull("num") ?: return null
            return TimelineItemRef(id, num, json.stringOrNull("title") ?: "", json.boolOrNull("done") ?: false)
        }
    }
}

/// A picture or video a story shows, by journal blob.
data class TimelineMedia(val blobID: String, val contentType: String, val name: String) {
    val isVideo: Boolean get() = contentType.lowercase().startsWith("video/")

    companion object {
        fun fromJson(json: JsonObject?): TimelineMedia? {
            json ?: return null
            val blobID = json.stringOrNull("blob_id")?.takeIf { it.isNotEmpty() } ?: return null
            val contentType = json.stringOrNull("content_type") ?: return null
            return TimelineMedia(blobID, contentType, json.stringOrNull("name") ?: "")
        }
    }
}

/// What a card says about its project's week in counts and a word, never
/// item titles.
data class TimelineMarks(
    /// Items waiting on the user.
    val forYou: Int = 0,
    /// Items sorted out that week.
    val done: Int = 0,
    /// Whether the project says what comes next.
    val next: Boolean = false,
) {
    val isEmpty: Boolean get() = forYou == 0 && done == 0 && !next

    companion object {
        /// A missing count is 0 and a missing `next` false; null only for no object.
        fun fromJson(json: JsonObject?): TimelineMarks? {
            json ?: return null
            return TimelineMarks(json.intOrNull("for_you") ?: 0, json.intOrNull("done") ?: 0, json.boolOrNull("next") ?: false)
        }
    }
}

/// One row of `GET /timeline/weeks`.
data class TimelineWeekSummary(
    /// The Monday's local date, `YYYY-MM-DD`.
    val week: String,
    val start: Instant,
    val end: Instant,
    val sentence: String = "",
    /// How many projects moved that week.
    val moved: Int = 0,
) {
    companion object {
        fun fromJson(json: JsonObject): TimelineWeekSummary? {
            val week = json.stringOrNull("week")?.takeIf { it.isNotEmpty() } ?: return null
            val start = msInstant(json.longOrNull("start")) ?: return null
            val end = msInstant(json.longOrNull("end")) ?: return null
            return TimelineWeekSummary(week, start, end, json.stringOrNull("sentence") ?: "", json.intOrNull("moved") ?: 0)
        }
    }
}

/// A story on a week's page: the Coordinator's written story when the
/// journal has one, else a project that moved. Build with [of], which keeps
/// the main project first in [projects] and derives [marks] when the journal
/// sends none.
data class TimelineStory(
    /// The main project (the story's first).
    val project: TimelineProjectRef,
    /// The Coordinator's title for the story; null when none was written.
    val title: String?,
    /// Every project the story covers, the main one first. Never empty.
    val projects: List<TimelineProjectRef>,
    /// The newest milestone title that week; null when it moved without one.
    val sentence: String?,
    val next: String?,
    val needsYou: List<TimelineItemRef>,
    val media: TimelineMedia?,
    val marks: TimelineMarks,
) {
    companion object {
        fun of(
            project: TimelineProjectRef,
            title: String? = null,
            projects: List<TimelineProjectRef>? = null,
            sentence: String? = null,
            next: String? = null,
            needsYou: List<TimelineItemRef> = emptyList(),
            media: TimelineMedia? = null,
            marks: TimelineMarks? = null,
        ) = TimelineStory(
            project = project, title = title,
            // The main project always leads, once, whatever the decoded list held.
            projects = listOf(project) + (projects ?: emptyList()).filter { it.id != project.id },
            sentence = sentence, next = next, needsYou = needsYou, media = media,
            marks = marks ?: TimelineMarks(forYou = needsYou.count { !it.done }, done = 0, next = next != null),
        )

        fun fromJson(json: JsonObject): TimelineStory? {
            val project = TimelineProjectRef.fromJson(json.objectOrNull("project")) ?: return null
            return of(
                project = project,
                title = json.stringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() },
                projects = json.arrayOrNull("projects")?.objects()?.mapNotNull { TimelineProjectRef.fromJson(it) },
                sentence = json.stringOrNull("sentence"),
                next = json.stringOrNull("next"),
                needsYou = json.arrayOrNull("needs_you")?.objects()?.mapNotNull(TimelineItemRef::fromJson) ?: emptyList(),
                media = TimelineMedia.fromJson(json.objectOrNull("media")),
                marks = TimelineMarks.fromJson(json.objectOrNull("marks")),
            )
        }
    }
}

/// `GET /timeline/weeks/:week`.
data class TimelineWeek(
    val week: String,
    val start: Instant,
    val end: Instant,
    /// False for the current week (and when the journal doesn't say).
    val final: Boolean,
    val lede: String? = null,
    val stories: List<TimelineStory> = emptyList(),
    /// Projects that moved beyond the stories the journal shows.
    val also: List<TimelineProjectRef> = emptyList(),
) {
    companion object {
        /// Stories and `also` rows that fail to decode are dropped; a week
        /// with no key or no bounds is null.
        fun fromJson(json: JsonObject): TimelineWeek? {
            val week = json.stringOrNull("week")?.takeIf { it.isNotEmpty() } ?: return null
            val start = msInstant(json.longOrNull("start")) ?: return null
            val end = msInstant(json.longOrNull("end")) ?: return null
            return TimelineWeek(
                week = week, start = start, end = end, final = json.boolOrNull("final") ?: false,
                lede = json.stringOrNull("lede"),
                stories = json.arrayOrNull("stories")?.objects()?.mapNotNull(TimelineStory::fromJson) ?: emptyList(),
                also = json.arrayOrNull("also")?.objects()?.mapNotNull { TimelineProjectRef.fromJson(it) } ?: emptyList(),
            )
        }
    }
}

/// A row of `GET /timeline/weeks/:week/projects`: one project that moved
/// that week beyond the week's stories, with what its card says.
data class TimelineWeekProject(
    val project: TimelineProjectRef,
    /// How many times it moved that week.
    val moved: Int = 0,
    val sentence: String? = null,
    val media: TimelineMedia? = null,
    val marks: TimelineMarks = TimelineMarks(),
) {
    /// The row as a card: the project's name, its sentence, media and marks.
    val story: TimelineStory get() = TimelineStory.of(project = project, sentence = sentence, media = media, marks = marks)

    companion object {
        fun fromJson(json: JsonObject): TimelineWeekProject? {
            val project = TimelineProjectRef.fromJson(json.objectOrNull("project")) ?: return null
            return TimelineWeekProject(
                project = project, moved = json.intOrNull("moved") ?: 0, sentence = json.stringOrNull("sentence"),
                media = TimelineMedia.fromJson(json.objectOrNull("media")),
                marks = TimelineMarks.fromJson(json.objectOrNull("marks")) ?: TimelineMarks(),
            )
        }
    }
}

/// `GET /timeline/weeks/:week/projects`: one page of the projects that moved
/// that week beyond its stories, most active first, the full count and the
/// next page's offset (null on the last).
data class TimelineWeekProjectsPage(
    val week: String,
    val total: Int,
    val offset: Int,
    val nextOffset: Int?,
    val projects: List<TimelineWeekProject>,
) {
    companion object {
        /// Rows that fail to decode are dropped; null without a week or rows.
        fun fromJson(json: JsonObject): TimelineWeekProjectsPage? {
            val week = json.stringOrNull("week")?.takeIf { it.isNotEmpty() } ?: return null
            val rows = json.arrayOrNull("projects") ?: return null
            return TimelineWeekProjectsPage(
                week = week, total = json.intOrNull("total") ?: 0, offset = json.intOrNull("offset") ?: 0,
                nextOffset = json.intOrNull("next_offset"),
                projects = rows.objects().mapNotNull(TimelineWeekProject::fromJson),
            )
        }
    }
}

/// The projects a week page shows past its stories, as far as they have
/// been paged, and where the paging stands. Built by `TimelineViewModel`.
data class TimelineWeekExtras(
    val week: String,
    val projects: List<TimelineWeekProject> = emptyList(),
    val status: Status,
) {
    enum class Status {
        /// More to read: reaching the end of the page asks for it.
        MORE,
        LOADING,
        /// The last read failed: the page offers Retry.
        FAILED,
        /// Every project is here.
        DONE,
        /// The journal has no such read: the page keeps its "Also moved" line.
        UNSUPPORTED,
    }
}

/// A row of `GET /timeline/quiet` or `/timeline/long`.
data class StalledProject(
    val project: TimelineProjectRef,
    /// null for a project with no recorded move.
    val lastMovedAt: Instant? = null,
    /// Only the open-a-long-time list sends it.
    val createdAt: Instant? = null,
    /// Move counts for the last eight weeks, oldest first.
    val weekly: List<Int> = emptyList(),
    val note: String? = null,
) {
    companion object {
        fun fromJson(json: JsonObject): StalledProject? {
            val project = TimelineProjectRef.fromJson(json.objectOrNull("project")) ?: return null
            // Positional (oldest first): a non-number counts as 0 rather than shifting the weeks.
            val weekly = json.arrayOrNull("weekly")?.map { element ->
                (element as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.content?.toDoubleOrNull()?.toInt() ?: 0
            } ?: emptyList()
            return StalledProject(
                project = project, lastMovedAt = msInstant(json.longOrNull("last_moved_at")),
                createdAt = msInstant(json.longOrNull("created_at")), weekly = weekly, note = json.stringOrNull("note"),
            )
        }
    }
}

/// `GET /timeline/quiet` / `/timeline/long`: the top rows and the full count.
data class StalledList(val total: Int, val rows: List<StalledProject>) {
    companion object {
        fun fromJson(json: JsonObject): StalledList? {
            val total = json.intOrNull("total") ?: return null
            val rows = json.arrayOrNull("rows") ?: return null
            return StalledList(total, rows.objects().mapNotNull(StalledProject::fromJson))
        }
    }
}

/// A row of `GET /timeline/finished`.
data class FinishedProject(val project: TimelineProjectRef, val finishedAt: Instant) {
    companion object {
        fun fromJson(json: JsonObject): FinishedProject? {
            val project = TimelineProjectRef.fromJson(json.objectOrNull("project")) ?: return null
            val finishedAt = msInstant(json.longOrNull("finished_at")) ?: return null
            return FinishedProject(project, finishedAt)
        }
    }
}

/// A row of `GET /timeline/projects`.
data class TimelineProjectRow(
    val project: TimelineProjectRef,
    /// null for a project with no recorded move.
    val lastMovedAt: Instant? = null,
    val openMissions: Int = 0,
) {
    companion object {
        fun fromJson(json: JsonObject): TimelineProjectRow? {
            val project = TimelineProjectRef.fromJson(json.objectOrNull("project")) ?: return null
            return TimelineProjectRow(project, msInstant(json.longOrNull("last_moved_at")), json.intOrNull("open_missions") ?: 0)
        }
    }
}

/// `GET /timeline/projects`: one page of every project, the full count and
/// the cursor for the next page (null on the last).
data class TimelineProjectList(val total: Int, val rows: List<TimelineProjectRow>, val nextBefore: String? = null) {
    companion object {
        fun fromJson(json: JsonObject): TimelineProjectList? {
            val total = json.intOrNull("total") ?: return null
            val rows = json.arrayOrNull("rows") ?: return null
            return TimelineProjectList(total, rows.objects().mapNotNull(TimelineProjectRow::fromJson), json.stringOrNull("next_before"))
        }
    }
}

/// `GET /timeline/projects/:id`: one project's page.
data class TimelineProjectPage(
    val project: TimelineProjectRef,
    /// The project has at least one open mission. False when the journal
    /// doesn't say.
    val isLive: Boolean = false,
    /// The project's own state as the journal names it (`open`, `closed`).
    val state: String = "open",
    /// Where the project stands; null when nothing says.
    val sentence: String? = null,
    val next: String? = null,
    /// The first items waiting on the user; [needsYouTotal] counts them all.
    val needsYou: List<TimelineItemRef> = emptyList(),
    val needsYouTotal: Int = needsYou.size,
    val media: TimelineMedia? = null,
    /// How many weeks the project moved in.
    val weeks: Int = 0,
    val missionsOpen: Int = 0,
    val missionsDone: Int = 0,
    val files: Int = 0,
    /// The project asked for, when it was merged into this one.
    val mergedFromID: String? = null,
) {
    /// The project can still be changed: any other state is closed or finished.
    val isOpen: Boolean get() = state == "open"

    companion object {
        /// null without a project; bad items are dropped, a missing count is
        /// 0 and a missing `needs_you_total` counts the items sent.
        fun fromJson(json: JsonObject): TimelineProjectPage? {
            val raw = json.objectOrNull("project")
            val project = TimelineProjectRef.fromJson(raw) ?: return null
            val counts = json.objectOrNull("counts")
            fun count(key: String) = counts?.intOrNull(key) ?: 0
            val needsYou = json.arrayOrNull("needs_you")?.objects()?.mapNotNull(TimelineItemRef::fromJson) ?: emptyList()
            return TimelineProjectPage(
                project = project, isLive = raw?.boolOrNull("live") ?: false, state = raw?.stringOrNull("state") ?: "open",
                sentence = json.stringOrNull("sentence"), next = json.stringOrNull("next"),
                needsYou = needsYou, needsYouTotal = json.intOrNull("needs_you_total") ?: needsYou.size,
                media = TimelineMedia.fromJson(json.objectOrNull("media")),
                weeks = count("weeks"), missionsOpen = count("missions_open"), missionsDone = count("missions_done"),
                files = count("files"),
                mergedFromID = json.objectOrNull("merged_from")?.stringOrNull("id")?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/// One week of `GET /timeline/projects/:id/history`: the project's card that
/// week, when one was written, and the milestones posted in it.
data class TimelineHistoryWeek(
    /// The Monday's local date, `YYYY-MM-DD`.
    val week: String,
    val start: Instant,
    val end: Instant,
    /// The card's sentence; null for a week with no card.
    val sentence: String? = null,
    val media: TimelineMedia? = null,
    /// The week's first milestones; [moreMilestones] counts the rest.
    val milestones: List<ProjectMilestone> = emptyList(),
    val moreMilestones: Int = 0,
) {
    companion object {
        fun fromJson(json: JsonObject): TimelineHistoryWeek? {
            val week = json.stringOrNull("week")?.takeIf { it.isNotEmpty() } ?: return null
            val start = msInstant(json.longOrNull("start")) ?: return null
            val end = msInstant(json.longOrNull("end")) ?: return null
            val card = json.objectOrNull("card")
            return TimelineHistoryWeek(
                week = week, start = start, end = end, sentence = card?.stringOrNull("sentence"),
                media = TimelineMedia.fromJson(card?.objectOrNull("media")),
                milestones = json.arrayOrNull("milestones")?.objects()?.mapNotNull(ProjectMilestone::fromJson) ?: emptyList(),
                moreMilestones = json.intOrNull("more_milestones") ?: 0,
            )
        }
    }
}
