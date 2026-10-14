package chat.matron.android.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemState
import chat.matron.android.models.ItemResolution
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommentAuthorCaptionTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    @Test
    fun onlyLocalConversationsOfferATapAndUpdateLive() {
        val known = mutableStateOf(emptySet<String>())
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                AuthorCaption("box-a", now, now, TrackerComment.AuthorConversation("c1", "Audit"), known.value, opened::add)
            }
        }
        val title = compose.onNodeWithContentDescription("Conversation: Audit")
        title.assertHasNoClickAction()
        compose.runOnIdle { known.value = setOf("c1") }
        title.assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(listOf("c1"), opened)
            known.value = emptySet()
        }
        title.assertHasNoClickAction()
    }

    @Test
    fun longTitleTruncatesBeforeTheBoxAndTime() = checkNarrowCaption(fontScale = 1f)

    @Test
    fun largerTextStillKeepsTheBoxAndTime() = checkNarrowCaption(fontScale = 1.3f)

    private fun checkNarrowCaption(fontScale: Float) {
        val title = "Review the parser and all of the pending validation results"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(280.dp)) {
                        AuthorCaption("box-a", now.minusSeconds(300), now, TrackerComment.AuthorConversation("c1", title))
                    }
                }
            }
        }
        fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        val box = layout(compose.onNodeWithText("box-a"))
        val time = layout(compose.onNodeWithText("· 5 min ago"))
        val conversation = layout(compose.onNodeWithContentDescription("Conversation: $title"))
        for (label in listOf(box, time)) {
            assertEquals(1, label.lineCount)
            assertFalse(label.isLineEllipsized(0))
            assertEquals(label.layoutInput.text.length, label.getLineEnd(0))
            assertTrue("the entire label fits its measured width", label.getLineRight(0) <= label.size.width)
        }
        assertEquals(1, conversation.lineCount)
        assertTrue(conversation.isLineEllipsized(0))
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp")
    fun threadWiresRegularAndStatusCommentCaptionsToNavigation() {
        val opened = mutableListOf<String>()
        val regular = TrackerComment(
            "ic_1", "it_1", ItemAuthor.AGENT, body = "The parser checks pass.", createdAt = now.minusSeconds(300),
            deviceName = "box-a", convoID = "c1", convoTitle = "Review the parser and all of the pending validation results",
        )
        val status = TrackerComment(
            "ic_2", "it_1", ItemAuthor.AGENT, kind = TrackerComment.Kind.STATUS, body = "The review is complete.",
            createdAt = now.minusSeconds(240), deviceName = "box-b", convoID = "c2", convoTitle = "Release review",
            statusTo = TrackerItem.StatusSnapshot(ItemState.CLOSED, ItemResolution.DONE, null),
        )
        val unavailable = regular.copy(id = "ic_3", deviceName = "box-c", convoID = "c3", convoTitle = "Follow-up checks", body = "The results are ready.")
        val old = TrackerComment("ic_4", "it_1", ItemAuthor.AGENT, body = "An earlier update.", createdAt = now)
        compose.setContent {
            MatronTheme(appearance = MatronAppearance.Light) {
                Surface {
                    ItemDetailView(
                        model = ItemDetailModel(
                            item = TrackerItem("it_1", 1, ItemKind.TASK, title = "Review the parser", body = "Check the validation results.", originConvoID = "c1", createdAt = now),
                            comments = listOf(regular, status, unavailable, old), pending = emptyList(),
                            context = ItemContext(null, null, null), availableResolutions = emptyList(), isBusy = false,
                            loadedCommentCount = 4, openableConvoIDs = setOf("c1", "c2"),
                        ),
                        draft = "", onDraftChange = {}, image = { null }, onOpenAttachment = {}, onOpenLink = {},
                        onOpenConversation = opened::add, onSubmit = {}, onAttach = {}, onVoiceNote = {}, now = now,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Conversation: ${regular.convoTitle}").performClick()
        compose.onNodeWithContentDescription("Conversation: ${status.convoTitle}").performClick()
        compose.onNodeWithContentDescription("Conversation: ${unavailable.convoTitle}").assertHasNoClickAction()
        compose.runOnIdle { assertEquals(listOf("c1", "c2"), opened) }
        val screenshot = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        val output = File("build/reports/comment-authors.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun missingConversationLeavesTheOriginalCaption() {
        compose.setContent { MaterialTheme { AuthorCaption("Agent", now, now) } }
        compose.onNodeWithText("Agent").assertExists()
        compose.onNodeWithText("· just now").assertExists()
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
    }
}
