package chat.matron.android.journal

import chat.matron.android.models.FinishedProject
import chat.matron.android.models.MatronDebug
import chat.matron.android.models.StalledList
import chat.matron.android.models.TimelineHistoryWeek
import chat.matron.android.models.TimelineProjectList
import chat.matron.android.models.TimelineProjectPage
import chat.matron.android.models.TimelineWeek
import chat.matron.android.models.TimelineWeekProjectsPage
import chat.matron.android.models.TimelineWeekSummary
import kotlinx.serialization.json.JsonObject

// The Projects weekly timeline reads (`GET /timeline/...`). Ported from
// matron-apple's `Journal/JournalAPI+Timeline.swift`; the routes live on
// [JournalApi] (its request plumbing is private), which implements
// [TimelineProviding]. A journal without the routes answers 404
// ([JournalApiError.NotFound]) on the weeks route; callers read that as
// "unsupported".

/// One page of `GET /timeline/weeks`, newest first.
data class TimelineWeeksPage(val weeks: List<TimelineWeekSummary>, val nextBefore: String?)

/// One page of `GET /timeline/finished`, newest finish first.
data class TimelineFinishedPage(val rows: List<FinishedProject>, val nextBefore: String?)

/// One page of `GET /timeline/projects/:id/history`, newest week first.
data class TimelineHistoryPage(val weeks: List<TimelineHistoryWeek>, val nextBefore: String?)

interface TimelineProviding {
    /// Weeks newest first. [before] is a week key (a page's `nextBefore`);
    /// [limit] null takes the journal's default.
    suspend fun timelineWeeks(before: String? = null, limit: Int? = null): TimelineWeeksPage
    suspend fun timelineWeek(week: String): TimelineWeek
    /// The projects that moved in [week] beyond its stories, most active
    /// first, from [offset]. A journal without the route answers 404: the
    /// week page then keeps its "Also moved" line.
    suspend fun timelineWeekProjects(week: String, offset: Int, limit: Int? = null): TimelineWeekProjectsPage
    suspend fun wentQuiet(): StalledList
    suspend fun openLong(): StalledList
    suspend fun finishedProjects(before: String? = null, limit: Int? = null): TimelineFinishedPage
    /// Every project, the most recently moved first.
    suspend fun timelineProjects(before: String? = null, limit: Int? = null): TimelineProjectList
    /// One project's page; 404 for a project the journal doesn't have.
    suspend fun timelineProject(id: String): TimelineProjectPage
    suspend fun timelineProjectHistory(id: String, before: String? = null, limit: Int? = null): TimelineHistoryPage
}

/// The response decoders, separate from the HTTP plumbing so the tests can
/// pin each shape without a server. Every timeline route is a read, never an
/// authoritative replace, so a row that fails to decode is dropped and
/// logged; a response missing its top-level array or object is a
/// [JournalApiError.Transport].
object TimelineDecoding {
    private fun <T> rows(raw: List<JsonObject>, what: String, make: (JsonObject) -> T?): List<T> = raw.mapNotNull { row ->
        make(row) ?: run {
            val id = row.stringOrNull("week") ?: row.objectOrNull("project")?.stringOrNull("id") ?: row.stringOrNull("id")
            MatronDebug.breadcrumb("TimelineApi: dropped malformed $what row id=${id ?: "?"}")
            null
        }
    }

    private fun malformed(what: String) = JournalApiError.Transport("malformed timeline $what response")

    fun weeks(obj: JsonObject): TimelineWeeksPage {
        val raw = obj.arrayOrNull("weeks") ?: throw malformed("weeks")
        return TimelineWeeksPage(rows(raw.objects(), "week", TimelineWeekSummary::fromJson), obj.stringOrNull("next_before"))
    }

    fun week(obj: JsonObject): TimelineWeek {
        val week = TimelineWeek.fromJson(obj) ?: throw malformed("week")
        val sent = (obj.arrayOrNull("stories")?.size ?: 0) + (obj.arrayOrNull("also")?.size ?: 0)
        val kept = week.stories.size + week.also.size
        if (kept < sent) MatronDebug.breadcrumb("TimelineApi: dropped ${sent - kept} malformed row(s) in week ${week.week}")
        return week
    }

    fun weekProjects(obj: JsonObject): TimelineWeekProjectsPage =
        TimelineWeekProjectsPage.fromJson(obj) ?: throw malformed("week projects")

    fun stalled(obj: JsonObject): StalledList = StalledList.fromJson(obj) ?: throw malformed("list")

    fun finished(obj: JsonObject): TimelineFinishedPage {
        val raw = obj.arrayOrNull("rows") ?: throw malformed("finished")
        return TimelineFinishedPage(rows(raw.objects(), "finished", FinishedProject::fromJson), obj.stringOrNull("next_before"))
    }

    fun projects(obj: JsonObject): TimelineProjectList = TimelineProjectList.fromJson(obj) ?: throw malformed("projects")

    fun project(obj: JsonObject): TimelineProjectPage = TimelineProjectPage.fromJson(obj) ?: throw malformed("project")

    fun history(obj: JsonObject): TimelineHistoryPage {
        val raw = obj.arrayOrNull("weeks") ?: throw malformed("history")
        return TimelineHistoryPage(rows(raw.objects(), "history week", TimelineHistoryWeek::fromJson), obj.stringOrNull("next_before"))
    }

    /// `?before=&limit=` for the paged reads.
    fun pageQuery(before: String?, limit: Int?): List<Pair<String, String>> = buildList {
        before?.let { add("before" to it) }
        limit?.let { add("limit" to it.toString()) }
    }

    /// `?offset=&limit=` for the week's projects: the offset never below 0,
    /// the limit kept to the journal's 1..50.
    fun weekProjectsQuery(offset: Int, limit: Int?): List<Pair<String, String>> = buildList {
        add("offset" to maxOf(0, offset).toString())
        limit?.let { add("limit" to it.coerceIn(1, 50).toString()) }
    }
}
