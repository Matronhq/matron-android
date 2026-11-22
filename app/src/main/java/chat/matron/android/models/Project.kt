package chat.matron.android.models

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject

// Projects (journal `/projects`): a project groups missions, one project per
// mission or none, numbered from the same per-user counter as items,
// missions and milestones. Ported from matron-apple's `Models/Project.swift`,
// `ProjectFeed.swift` and `ConversationMissions.swift`, keeping only what the
// Projects tab and the mission and chat headers read.

private fun msInstant(ms: Long?): Instant? = ms?.let { Instant.ofEpochMilli(it) }

/// The rollup's `missions` object (every project route sends it); zero when absent.
data class ProjectMissionCounts(
    val running: Int = 0,
    val waiting: Int = 0,
    val idle: Int = 0,
    val quiet: Int = 0,
    val closed: Int = 0,
) {
    /// Every mission that is not closed.
    val open: Int get() = running + waiting + idle + quiet

    companion object {
        fun fromJson(json: JsonObject?): ProjectMissionCounts = ProjectMissionCounts(
            running = json?.intOrNull("running") ?: 0, waiting = json?.intOrNull("waiting") ?: 0,
            idle = json?.intOrNull("idle") ?: 0, quiet = json?.intOrNull("quiet") ?: 0,
            closed = json?.intOrNull("closed") ?: 0,
        )
    }
}

/// One project as `GET /projects` and `GET /projects/:id` send it.
data class Project(
    val id: String,
    val num: Int,
    val state: MissionState = MissionState.OPEN,
    val title: String,
    val body: String = "",
    /// The Coordinator's paragraph; null when unset or sieved.
    val status: String? = null,
    val closeSummary: String? = null,
    val closedAt: Instant? = null,
    /// Set when a merge closed this project: the project its missions moved to.
    val mergedInto: String? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
    val missions: ProjectMissionCounts = ProjectMissionCounts(),
    val needsYou: Int = 0,
    val openItems: Int = 0,
    val lastActivityAt: Instant? = null,
) {
    val label: String get() = "#$num $title"

    companion object {
        fun fromJson(json: JsonObject): Project? {
            val id = json.stringOrNull("id") ?: return null
            val num = json.intOrNull("num") ?: return null
            val state = MissionState.fromWire(json.stringOrNull("state")) ?: return null
            val title = json.stringOrNull("title") ?: return null
            val createdAt = msInstant(json.longOrNull("created_at")) ?: return null
            val updatedAt = msInstant(json.longOrNull("updated_at")) ?: return null
            return Project(
                id = id, num = num, state = state, title = title, body = json.stringOrNull("body") ?: "",
                status = json.stringOrNull("status")?.takeIf { it.isNotEmpty() },
                closeSummary = json.stringOrNull("close_summary"), closedAt = msInstant(json.longOrNull("closed_at")),
                mergedInto = json.stringOrNull("merged_into"),
                createdAt = createdAt, updatedAt = updatedAt,
                missions = ProjectMissionCounts.fromJson(json.objectOrNull("missions")),
                needsYou = json.intOrNull("needs_you") ?: 0, openItems = json.intOrNull("open_items") ?: 0,
                lastActivityAt = msInstant(json.longOrNull("last_activity_at")),
            )
        }
    }
}

/// `GET /projects/:id`: the project and its missions. For a merged project
/// the journal answers with the TARGET, so [project]'s id can differ from
/// the one asked for.
data class ProjectDetail(val project: Project, val missions: List<Mission>)

/// Where a file of a project was posted: an attachment on an item's
/// comment, or an image or file event in a conversation linked to one of
/// the project's missions.
sealed interface ProjectFileSource {
    data class Item(val num: Int) : ProjectFileSource
    data class Chat(val convoID: String, val seq: Long) : ProjectFileSource

    companion object {
        fun fromJson(json: JsonObject?): ProjectFileSource? {
            json ?: return null
            json.intOrNull("item_num")?.let { return Item(it) }
            val convoID = json.stringOrNull("convo_id") ?: return null
            val seq = json.longOrNull("seq") ?: return null
            return Chat(convoID, seq)
        }
    }
}

/// A file or picture on the project's items or in its missions' chats
/// (`GET /projects/:id/feed?kind=files`).
data class ProjectFile(
    val blobID: String,
    val name: String? = null,
    val contentType: String? = null,
    val size: Long? = null,
    val caption: String? = null,
    val missionNum: Int? = null,
    val source: ProjectFileSource,
    val postedAt: Instant? = null,
) {
    /// One blob can be attached twice; the source tells the rows apart.
    val id: String
        get() = when (source) {
            is ProjectFileSource.Item -> "item:${source.num}:$blobID"
            is ProjectFileSource.Chat -> "chat:${source.convoID}:${source.seq}"
        }

    val isImage: Boolean get() = contentType?.startsWith("image/") == true
    val isVideo: Boolean get() = contentType?.lowercase()?.startsWith("video/") == true

    companion object {
        fun fromJson(json: JsonObject): ProjectFile? {
            val blobID = json.stringOrNull("blob_id") ?: return null
            val source = ProjectFileSource.fromJson(json.objectOrNull("source")) ?: return null
            return ProjectFile(
                blobID = blobID, name = json.stringOrNull("name"), contentType = json.stringOrNull("content_type"),
                size = json.longOrNull("size"), caption = json.stringOrNull("caption"),
                missionNum = json.intOrNull("mission_num"), source = source,
                postedAt = msInstant(json.longOrNull("posted_at")),
            )
        }
    }
}

/// A milestone on one of the project's missions, with its mission's number
/// (the mission may be closed, and so off the page's mission list).
data class ProjectMilestone(val milestone: Milestone, val missionNum: Int? = null) {
    val id: String get() = milestone.id

    companion object {
        fun fromJson(json: JsonObject): ProjectMilestone? {
            val milestone = Milestone.fromJson(json) ?: return null
            return ProjectMilestone(milestone, json.intOrNull("mission_num"))
        }
    }
}

/// One page of the project's files: [total] across every page,
/// [nextBefore] the cursor for the next (older) page, null on the last.
data class ProjectFilesPage(val total: Int = 0, val rows: List<ProjectFile> = emptyList(), val nextBefore: String? = null) {
    val hasMore: Boolean get() = nextBefore != null

    /// This page with [next]'s rows appended (any already here skipped) and
    /// [next]'s total and cursor.
    fun appending(next: ProjectFilesPage): ProjectFilesPage {
        val have = rows.map { it.id }.toSet()
        return ProjectFilesPage(next.total, rows + next.rows.filter { it.id !in have }, next.nextBefore)
    }

    companion object {
        /// Malformed rows are dropped; a missing `total` counts the rows.
        fun fromJson(json: JsonObject?): ProjectFilesPage? {
            val raw = json?.arrayOrNull("rows") ?: return null
            val rows = raw.objects().mapNotNull(ProjectFile::fromJson)
            return ProjectFilesPage(
                total = json.intOrNull("total") ?: rows.size, rows = rows,
                nextBefore = json.stringOrNull("next_before")?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/// One element of `GET /conversations/:id/missions`: a mission this
/// conversation is or was on.
data class ConversationMissionLink(
    val mission: Mission,
    /// Where `milestone_post` goes by default. At most one link is current.
    val isCurrent: Boolean = false,
    val isActive: Boolean = true,
    val joinedAt: Instant? = null,
    val endedAt: Instant? = null,
) {
    /// History: an ended link, or any link to a closed mission.
    val isEarlier: Boolean get() = !isActive || mission.state == MissionState.CLOSED

    companion object {
        /// The journal spreads the mission row flat into each element; a
        /// nested `mission` object is accepted too.
        fun fromJson(json: JsonObject): ConversationMissionLink? {
            val mission = Mission.fromJson(json.objectOrNull("mission") ?: json) ?: return null
            val ended = msInstant(json.longOrNull("ended_at"))
            return ConversationMissionLink(
                mission = mission, isCurrent = json.boolOrNull("current") ?: false,
                isActive = json.boolOrNull("active") ?: (ended == null),
                joinedAt = msInstant(json.longOrNull("joined_at")), endedAt = ended,
            )
        }
    }
}
