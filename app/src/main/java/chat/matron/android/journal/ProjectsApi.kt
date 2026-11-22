package chat.matron.android.journal

import chat.matron.android.models.ConversationMissionLink
import chat.matron.android.models.MatronDebug
import chat.matron.android.models.Mission
import chat.matron.android.models.Project
import chat.matron.android.models.ProjectDetail
import chat.matron.android.models.ProjectFilesPage
import kotlinx.serialization.json.JsonObject

// The `/projects` routes the apps use, and the per-conversation mission read
// for a chat's header. Ported from matron-apple's
// `Journal/JournalAPI+Projects.swift`; the routes live on [JournalApi],
// which implements [ProjectsProviding].

/// Why the journal refused to close a project.
sealed class ProjectCloseError(message: String) : Exception(message) {
    /// 409 `blocked_by: open_missions`: a project closes only once its
    /// missions are closed or moved.
    data object OpenMissions : ProjectCloseError("Close or move this project's open missions first.")
    /// 409 `blocked_by: closed`: it was closed already.
    data object AlreadyClosed : ProjectCloseError("This project is already closed.")
}

/// The reads and the writes the apps make on projects: rename, close, merge,
/// and filing a mission in one.
interface ProjectsProviding {
    /// `GET /projects?state=open`: the merge targets.
    suspend fun listProjects(openOnly: Boolean = true): List<Project>
    suspend fun project(id: String): ProjectDetail
    /// `GET /projects/:id/feed?kind=files`, older than [before].
    suspend fun projectFiles(id: String, before: String? = null, limit: Int? = null): ProjectFilesPage
    /// `POST /projects/:id/merge {into}`: every mission moves to [into] and
    /// this project closes.
    suspend fun mergeProject(id: String, into: String)
    /// `PATCH /projects/:id {title}`.
    suspend fun renameProject(id: String, title: String)
    /// `POST /projects/:id/close {summary}`; throws [ProjectCloseError] on
    /// the journal's 409s.
    suspend fun closeProject(id: String, summary: String)
    /// `PATCH /missions/:id {project}`; null takes the mission out of its project.
    suspend fun setMissionProject(missionID: String, project: String?): Mission
    /// `GET /conversations/:id/missions`.
    suspend fun conversationMissions(convoID: String): List<ConversationMissionLink>
}

object ProjectsDecoding {
    fun projects(obj: JsonObject): List<Project> {
        val rows = obj.arrayOrNull("projects") ?: throw JournalApiError.Transport("malformed projects response")
        return rows.objects().mapNotNull { row ->
            Project.fromJson(row) ?: run {
                MatronDebug.breadcrumb("ProjectsApi: dropped malformed project row id=${row.stringOrNull("id") ?: "?"}")
                null
            }
        }
    }

    fun detail(obj: JsonObject): ProjectDetail {
        val project = obj.objectOrNull("project")?.let(Project::fromJson)
            ?: throw JournalApiError.Transport("malformed project response")
        return ProjectDetail(project, obj.arrayOrNull("missions")?.objects()?.mapNotNull(Mission::fromJson) ?: emptyList())
    }

    /// A response for another kind than asked, or without `rows`, is a
    /// transport error; rows that fail to decode are dropped.
    fun files(obj: JsonObject): ProjectFilesPage {
        val kind = obj.stringOrNull("kind")
        if (kind != null && kind != "files") throw JournalApiError.Transport("project feed answered another kind")
        return ProjectFilesPage.fromJson(obj) ?: throw JournalApiError.Transport("malformed project feed response")
    }

    /// All or nothing: a header built from a partial list would name the
    /// wrong current mission.
    fun conversationMissions(obj: JsonObject): List<ConversationMissionLink> {
        val rows = obj.arrayOrNull("missions") ?: throw JournalApiError.Transport("malformed conversation missions response")
        return rows.objects().map { row ->
            ConversationMissionLink.fromJson(row) ?: throw JournalApiError.Transport("malformed conversation mission row")
        }
    }

    /// The close route's 409 body, as a [ProjectCloseError]; null for any other refusal.
    fun closeRefusal(status: Int, body: JsonObject?): ProjectCloseError? {
        if (status != 409) return null
        return when (body?.stringOrNull("blocked_by")) {
            "open_missions" -> ProjectCloseError.OpenMissions
            "closed" -> ProjectCloseError.AlreadyClosed
            else -> null
        }
    }
}
