package chat.matron.android.designsystem

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.NumberKind
import chat.matron.android.journal.NumberLookup
import chat.matron.android.journal.TrackerLinkTitles
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// A message body with tracker links renders each link's chip once the
/// lookups answer: a bare link shows the title, a link with the right title
/// shows the kind, a wrong title and a missing number are flagged. With
/// TRACKER_LINK_SHOTS set to a directory, the rendered body is also written
/// there as PNGs (light and dark) for the pull request.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackerLinkChipsRenderTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val own = "https://chat.example/u/alice"
    private val body = listOf(
        "Filed the follow-ups: [#412]($own/412) for the backup check, and [#415 Rotate the API keys]($own/415).",
        "",
        "The merge question is [#418 Merge: nginx config]($own/418). The old runbook was [#9999]($own/9999).",
    ).joinToString("\n")

    private val titles = TrackerLinkTitles(
        journalBaseURL = "https://chat.example",
        sessionUser = "alice",
        lookup = { _, num ->
            when (num) {
                412 -> NumberLookup(NumberKind.ITEM, "a", num, "Verify last night's backup with a test restore")
                415 -> NumberLookup(NumberKind.ITEM, "b", num, "Rotate the API keys before Friday")
                418 -> NumberLookup(NumberKind.MISSION, "c", num, "Postgres upgrade dry-run")
                else -> throw JournalApiError.NotFound
            }
        },
    )

    private fun render(appearance: MatronAppearance, withTitles: Boolean = true) {
        compose.setContent {
            MatronTheme(appearance = appearance) {
                CompositionLocalProvider(LocalTrackerLinkTitles provides if (withTitles) titles else null) {
                    MarkdownText(
                        raw = body,
                        modifier = Modifier
                            .testTag("body")
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun eachLinkShowsWhatItPointsAt() {
        render(MatronAppearance.Light)
        settle()
        compose.onNodeWithText("item · Verify last night's backup with a test restore", substring = true).assertExists()
        compose.onNodeWithText(" item ", substring = true).assertExists()
        compose.onNodeWithText("⚠ mission · Postgres upgrade dry-run", substring = true).assertExists()
        compose.onNodeWithText("⚠ not found", substring = true).assertExists()
        shoot("light")
    }

    @Test
    fun theChipsReadInTheDarkTheme() {
        render(MatronAppearance.Dark)
        settle()
        compose.onNodeWithText("⚠ mission · Postgres upgrade dry-run", substring = true).assertExists()
        shoot("dark")
    }

    @Test
    fun withoutLookupsTheLinksRenderAsBefore() {
        render(MatronAppearance.Light, withTitles = false)
        compose.onNodeWithText("Filed the follow-ups: #412 for the backup check", substring = true).assertExists()
        compose.onNodeWithText("·", substring = true).assertDoesNotExist()
    }

    /// The lookups answer at once; let the chips' state land.
    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun shoot(name: String) {
        val dir = System.getenv("TRACKER_LINK_SHOTS") ?: return
        // Drawn straight from the view: captureToImage needs a real window.
        val view = compose.activity.window.decorView.rootView
        val whole = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(whole))
        val box = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInWindow
        val bitmap = Bitmap.createBitmap(whole, box.left.toInt(), box.top.toInt(), box.width.toInt(), box.height.toInt())
        File(dir).mkdirs()
        File(dir, "android-links-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
