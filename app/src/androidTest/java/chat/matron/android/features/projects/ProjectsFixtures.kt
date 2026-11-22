package chat.matron.android.features.projects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import chat.matron.android.chat.MediaService
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.TimelineFinishedPage
import chat.matron.android.journal.TimelineHistoryPage
import chat.matron.android.journal.ProjectsProviding
import chat.matron.android.journal.TimelineProviding
import chat.matron.android.models.ConversationMissionLink
import chat.matron.android.models.Milestone
import chat.matron.android.models.MilestoneKind
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionLastMilestone
import chat.matron.android.models.MissionState
import chat.matron.android.models.Project
import chat.matron.android.models.ProjectDetail
import chat.matron.android.models.ProjectFilesPage
import chat.matron.android.models.ProjectFile
import chat.matron.android.models.ProjectFileSource
import chat.matron.android.models.ProjectMilestone
import chat.matron.android.models.TimelineHistoryWeek
import chat.matron.android.models.FinishedProject
import chat.matron.android.models.StalledProject
import chat.matron.android.models.TimelineProjectRow
import chat.matron.android.journal.TimelineWeeksPage
import chat.matron.android.models.StalledList
import chat.matron.android.models.TimelineItemRef
import chat.matron.android.models.TimelineMarks
import chat.matron.android.models.TimelineMedia
import chat.matron.android.models.TimelineProjectList
import chat.matron.android.models.TimelineProjectPage
import chat.matron.android.models.TimelineProjectRef
import chat.matron.android.models.TimelineStory
import chat.matron.android.models.TimelineWeek
import chat.matron.android.models.TimelineWeekProject
import chat.matron.android.models.TimelineWeekProjectsPage
import chat.matron.android.models.TimelineWeekSummary
import chat.matron.android.viewmodels.KeyValueStore
import java.io.ByteArrayOutputStream
import java.time.Instant

/// Made-up projects for the Projects screen tests: no server, no account.
object ProjectsFixtures {
    val docs = TimelineProjectRef("pj_docs", 12, "Docs site launch")
    val promo = TimelineProjectRef("pj_promo", 14, "Promo launch")
    val app = TimelineProjectRef("pj_app", 16, "App store listing")
    val pricing = TimelineProjectRef("pj_pricing", 17, "Pricing page refresh")
    val onboarding = TimelineProjectRef("pj_onboard", 18, "Onboarding emails")
    val support = TimelineProjectRef("pj_support", 19, "Help centre clean-up")

    private fun at(day: String) = Instant.parse("${day}T00:00:00Z")
    private fun summary(key: String, moved: Int) = TimelineWeekSummary(key, at(key), at(key).plusSeconds(7 * 86_400), moved = moved)

    val weeks = listOf(
        summary("2026-11-16", 6), summary("2026-11-09", 4), summary("2026-11-02", 3), summary("2026-10-26", 2),
    )

    val thisWeek = TimelineWeek(
        week = "2026-11-16", start = at("2026-11-16"), end = at("2026-11-23"), final = false,
        stories = listOf(
            TimelineStory.of(
                docs, sentence = "The docs site went live, with a getting-started guide and the API reference.",
                media = TimelineMedia("bl_docs", "image/png", "docs-home.png"),
                marks = TimelineMarks(forYou = 1, done = 4, next = true),
            ),
            TimelineStory.of(
                promo, sentence = "Launch banner and the email to existing users are drafted for review.",
                media = TimelineMedia("bl_promo", "video/mp4", "promo-teaser.mp4"),
                needsYou = listOf(TimelineItemRef("it_1", 41, "Approve the banner copy")),
                marks = TimelineMarks(forYou = 2, done = 1),
            ),
            TimelineStory.of(app, sentence = "New screenshots uploaded for phones and tablets.", marks = TimelineMarks(done = 2, next = true)),
            TimelineStory.of(pricing, sentence = null),
        ),
        also = listOf(onboarding, support),
    )

    val lastWeek = TimelineWeek(
        week = "2026-11-09", start = at("2026-11-09"), end = at("2026-11-16"), final = true,
        stories = listOf(
            TimelineStory.of(docs, sentence = "Navigation and search were wired up on the staging site.", marks = TimelineMarks(done = 3)),
            TimelineStory.of(onboarding, sentence = "The welcome email was rewritten and sent to bob for a read.", marks = TimelineMarks(forYou = 1)),
        ),
    )

    val weekProjects = TimelineWeekProjectsPage(
        "2026-11-16", 2, 0, null,
        listOf(
            TimelineWeekProject(onboarding, moved = 3, sentence = "Second email drafted.", marks = TimelineMarks(done = 1)),
            TimelineWeekProject(support, moved = 1, sentence = "Old articles archived.", media = TimelineMedia("bl_help", "image/png", "help.png")),
        ),
    )

    /// A rich timeline answering every read from the fixtures above.
    class Timeline : TimelineProviding {
        override suspend fun timelineWeeks(before: String?, limit: Int?) =
            if (before == null) TimelineWeeksPage(weeks, "2026-10-26") else TimelineWeeksPage(emptyList(), null)
        override suspend fun timelineWeek(week: String): TimelineWeek = when (week) {
            thisWeek.week -> thisWeek
            lastWeek.week -> lastWeek
            else -> TimelineWeek(week, at(week), at(week).plusSeconds(7 * 86_400), true)
        }
        override suspend fun timelineWeekProjects(week: String, offset: Int, limit: Int?) = weekProjects
        override suspend fun wentQuiet() = quiet
        override suspend fun openLong() = long
        override suspend fun finishedProjects(before: String?, limit: Int?) = TimelineFinishedPage(finished, null)
        override suspend fun timelineProjects(before: String?, limit: Int?) = allProjects
        override suspend fun timelineProject(id: String): TimelineProjectPage =
            if (id == docs.id) docsPage else throw JournalApiError.NotFound
        override suspend fun timelineProjectHistory(id: String, before: String?, limit: Int?) = TimelineHistoryPage(docsHistory, null)
    }

    private val today = Instant.now()
    private fun daysAgo(days: Long) = today.minusSeconds(days * 86_400)

    val quiet = StalledList(3, listOf(
        StalledProject(pricing, lastMovedAt = daysAgo(19), weekly = listOf(2, 3, 4, 1, 0, 0, 0, 0), note = "Waiting on new plan names from alice."),
        StalledProject(TimelineProjectRef("pj_blog", 21, "Blog relaunch"), lastMovedAt = daysAgo(24), weekly = listOf(0, 1, 2, 2, 0, 0, 0, 0),
            note = "Two posts drafted; the theme is half done."),
    ))
    val long = StalledList(1, listOf(
        StalledProject(support, lastMovedAt = daysAgo(2), createdAt = daysAgo(40), weekly = listOf(1, 1, 0, 1, 2, 0, 1, 1), note = "Archiving old articles a few at a time."),
    ))
    val finished = listOf(
        FinishedProject(TimelineProjectRef("pj_status", 9, "Status page"), daysAgo(3)),
        FinishedProject(TimelineProjectRef("pj_logo", 7, "Logo refresh"), daysAgo(11)),
        FinishedProject(TimelineProjectRef("pj_faq", 5, "FAQ rewrite"), daysAgo(30)),
    )
    val allProjects = TimelineProjectList(7, listOf(
        TimelineProjectRow(docs, today.minusSeconds(3 * 3600), 2), TimelineProjectRow(promo, daysAgo(1), 1),
        TimelineProjectRow(app, daysAgo(2), 1), TimelineProjectRow(onboarding, daysAgo(4), 1),
        TimelineProjectRow(support, daysAgo(2), 1), TimelineProjectRow(pricing, daysAgo(19), 1),
        TimelineProjectRow(TimelineProjectRef("pj_blog", 21, "Blog relaunch"), daysAgo(24), 2),
    ))

    val docsPage = TimelineProjectPage(
        project = docs, isLive = true, state = "open",
        sentence = "The docs site is live on example.com; search is next.",
        next = "Add search across the guides and the API reference.",
        needsYou = listOf(TimelineItemRef("it_31", 31, "Pick a colour theme for code samples"), TimelineItemRef("it_32", 32, "Approve the getting-started copy")),
        needsYouTotal = 4,
        media = TimelineMedia("bl_docs", "image/png", "docs-home.png"),
        weeks = 6, missionsOpen = 2, missionsDone = 3, files = 7,
    )

    private fun milestone(num: Int, title: String, day: String, mission: Int) = ProjectMilestone(
        Milestone(id = "ml_$num", missionID = "ms_$mission", num = num, kind = MilestoneKind.PROGRESS, title = title,
            convoID = "c$mission", seq = num.toLong(), createdAt = Instant.parse("${day}T10:00:00Z")),
        mission,
    )

    val docsHistory = listOf(
        TimelineHistoryWeek(
            "2026-11-16", at("2026-11-16"), at("2026-11-23"), sentence = "The docs site went live.",
            media = TimelineMedia("bl_docs", "image/png", "docs-home.png"),
            milestones = listOf(milestone(51, "Deployed the site to production", "2026-11-18", 20), milestone(50, "API reference generated", "2026-11-17", 21)),
            moreMilestones = 2,
        ),
        TimelineHistoryWeek(
            "2026-11-09", at("2026-11-09"), at("2026-11-16"), sentence = "Navigation and search were wired up on staging.",
            milestones = listOf(milestone(44, "Sidebar navigation done", "2026-11-12", 20)),
        ),
        TimelineHistoryWeek(
            "2026-11-02", at("2026-11-02"), at("2026-11-09"),
            milestones = listOf(milestone(38, "Picked the static site generator", "2026-11-03", 20)),
        ),
    )

    private val now = Instant.parse("2026-11-20T12:00:00Z")

    val docsMissions = listOf(
        Mission(id = "ms_20", num = 20, title = "Docs search", originConvoID = "c20", status = "Indexing the guides; the API pages are next.",
            projectID = docs.id, projectNum = docs.num, lastMilestoneAt = now, createdAt = now),
        Mission(id = "ms_21", num = 21, title = "API reference", originConvoID = "c21", projectID = docs.id, projectNum = docs.num,
            lastMilestone = MissionLastMilestone(50, "API reference generated", MilestoneKind.PROGRESS, now.minusSeconds(7200)),
            lastMilestoneAt = now.minusSeconds(7200), createdAt = now),
        Mission(id = "ms_17", num = 17, title = "Pick a site generator", state = MissionState.CLOSED, originConvoID = "c17",
            closeSummary = "Chose a static generator; builds in under a minute.", closedAt = now.minusSeconds(86_400 * 14), createdAt = now),
        Mission(id = "ms_18", num = 18, title = "Getting-started guide", state = MissionState.CLOSED, originConvoID = "c18",
            closeSummary = "Written, reviewed by bob, published.", closedAt = now.minusSeconds(86_400 * 3), createdAt = now),
        Mission(id = "ms_19", num = 19, title = "Domain and hosting", state = MissionState.CLOSED, originConvoID = "c19",
            closedAt = now.minusSeconds(86_400 * 7), createdAt = now),
    )

    val docsFiles = listOf(
        ProjectFile("bl_docs", "docs-home.png", "image/png", 245_760, missionNum = 20, source = ProjectFileSource.Chat("c20", 12), postedAt = today.minusSeconds(3 * 3600)),
        ProjectFile("bl_help", "search-results.png", "image/png", 198_000, missionNum = 20, source = ProjectFileSource.Item(41), postedAt = daysAgo(1)),
        ProjectFile("bl_tour", "docs-tour.mp4", "video/mp4", 8_912_896, missionNum = 21, source = ProjectFileSource.Chat("c21", 30), postedAt = daysAgo(2)),
        ProjectFile("bl_brief", "style-guide.pdf", "application/pdf", 1_258_291, missionNum = 18, source = ProjectFileSource.Item(33), postedAt = daysAgo(5)),
        ProjectFile("bl_nav", "navigation.png", "image/png", 120_000, missionNum = 20, source = ProjectFileSource.Chat("c20", 7), postedAt = daysAgo(9)),
        ProjectFile("bl_zip", "api-reference.zip", "application/zip", 3_000_000, missionNum = 21, source = ProjectFileSource.Item(36), postedAt = daysAgo(12)),
    )

    /// The projects routes, answering from the fixtures and recording writes.
    open class Projects : ProjectsProviding {
        val writes = mutableListOf<String>()
        override suspend fun listProjects(openOnly: Boolean) = listOf(
            Project(id = docs.id, num = docs.num, title = docs.title), Project(id = promo.id, num = promo.num, title = promo.title),
            Project(id = app.id, num = app.num, title = app.title), Project(id = pricing.id, num = pricing.num, title = pricing.title),
        )
        override suspend fun project(id: String) = ProjectDetail(Project(id = docs.id, num = docs.num, title = docs.title), docsMissions)
        override suspend fun projectFiles(id: String, before: String?, limit: Int?) = ProjectFilesPage(docsFiles.size, docsFiles, null)
        override suspend fun mergeProject(id: String, into: String) { writes += "merge" }
        override suspend fun renameProject(id: String, title: String) { writes += "rename" }
        override suspend fun closeProject(id: String, summary: String) { writes += "close" }
        override suspend fun setMissionProject(missionID: String, project: String?) =
            Mission(id = missionID, num = 20, title = "Docs search", originConvoID = "c20", projectID = project)
        override suspend fun conversationMissions(convoID: String) = emptyList<ConversationMissionLink>()
    }

    /// Pictures drawn on the device: a gradient per blob, nothing fetched.
    class Media : MediaService {
        override suspend fun image(url: String): ByteArray {
            val colours = when {
                url.endsWith("bl_docs") -> intArrayOf(0xFF3A7BD5.toInt(), 0xFF00D2FF.toInt())
                url.endsWith("bl_help") -> intArrayOf(0xFF56AB2F.toInt(), 0xFFA8E063.toInt())
                else -> intArrayOf(0xFFF7971E.toInt(), 0xFFFFD200.toInt())
            }
            val bitmap = Bitmap.createBitmap(960, 400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 960f, 400f, colours[0], colours[1], Shader.TileMode.CLAMP) })
            val white = Paint().apply { color = 0xCCFFFFFF.toInt() }
            canvas.drawRoundRect(80f, 80f, 520f, 140f, 20f, 20f, white)
            canvas.drawRoundRect(80f, 180f, 820f, 220f, 16f, 16f, white)
            canvas.drawRoundRect(80f, 250f, 700f, 290f, 16f, 16f, white)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
    }

    class Store : KeyValueStore {
        private val strings = mutableMapOf<String, String>()
        override fun getStringList(key: String): List<String>? = null
        override fun setStringList(key: String, value: List<String>) = Unit
        override fun getBoolean(key: String, default: Boolean) = default
        override fun setBoolean(key: String, value: Boolean) = Unit
        override fun getString(key: String) = strings[key]
        override fun setString(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { strings.remove(key) }
        override fun getIntOrNull(key: String): Int? = null
        override fun setInt(key: String, value: Int) = Unit
    }
}
