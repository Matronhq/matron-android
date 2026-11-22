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
import chat.matron.android.journal.TimelineProviding
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
    /// The clock the week page reads in tests: mid-week of the fixtures' newest week.
    val now: Instant = Instant.parse("2026-11-18T12:00:00Z")

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
        override suspend fun wentQuiet() = StalledList(0, emptyList())
        override suspend fun openLong() = StalledList(0, emptyList())
        override suspend fun finishedProjects(before: String?, limit: Int?) = TimelineFinishedPage(emptyList(), null)
        override suspend fun timelineProjects(before: String?, limit: Int?) = TimelineProjectList(0, emptyList())
        override suspend fun timelineProject(id: String): TimelineProjectPage = throw JournalApiError.NotFound
        override suspend fun timelineProjectHistory(id: String, before: String?, limit: Int?) = TimelineHistoryPage(emptyList(), null)
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
