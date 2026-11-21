package chat.matron.android.designsystem

import android.os.Build
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// A long-press on an item card copies the whole comment, as a long-press
/// on a chat bubble copies the whole message.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ItemCardCopyTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private class FakeClipboard : ClipboardManager {
        var text: String? = null
        override fun getText() = text?.let(::AnnotatedString)
        override fun setText(annotatedString: AnnotatedString) { text = annotatedString.text }
    }

    @Test
    fun copyTextIsTheMarkdownWithoutAttachmentRefs() {
        assertEquals("**Done.** See below.", itemCardCopyText("  **Done.** See below.\n", emptyList()))
        // An attachment placed inline is not text: the parts round it are.
        assertEquals("Before\n\nAfter", itemCardCopyText("Before\n\n![chart](attachment:ref_1)\n\nAfter", listOf(attachment("ref_1"))))
        assertNull(itemCardCopyText("![chart](attachment:ref_1)", listOf(attachment("ref_1"))))
        assertNull(itemCardCopyText("", listOf(attachment("ref_1"))))
    }

    @Test
    fun ownConfirmationOnlyBeforeTheSystemShowsOne() {
        assertTrue(copyNeedsOwnConfirmation(Build.VERSION_CODES.S_V2))
        assertFalse(copyNeedsOwnConfirmation(Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    fun longPressBesideTheTextCopiesTheWholeComment() {
        val body = "First paragraph.\n\nSecond paragraph with a [link](https://example.com)."
        val clipboard = show(body)
        // Beside the text: the far right of the card's last line, past its words.
        compose.onNodeWithText("First paragraph.").onParent().performTouchInput {
            longClick(Offset(width - 4f, height / 2f))
        }
        compose.onNodeWithText("Copy").performClick()
        compose.runOnIdle { assertEquals(body, clipboard.text) }
    }

    @Test
    fun longPressOnTheCaptionCopiesAndATapDoesNothing() {
        val clipboard = show("Looks good to me.")
        compose.onNodeWithText("Agent").performTouchInput { click() }
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithText("Agent").performTouchInput { longClick() }
        compose.onNodeWithText("Copy").performClick()
        compose.runOnIdle { assertEquals("Looks good to me.", clipboard.text) }
    }

    private fun attachment(ref: String) = chat.matron.android.models.TrackerAttachment(
        blobRef = ref, mime = "image/png", name = "chart.png", size = 10,
    )

    private fun show(commentBody: String): FakeClipboard {
        val clipboard = FakeClipboard()
        val comment = TrackerComment("ic_1", "it_1", ItemAuthor.AGENT, body = commentBody, createdAt = now.minusSeconds(300))
        compose.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                MatronTheme(appearance = MatronAppearance.Light) {
                    Surface {
                        ItemDetailView(
                            model = ItemDetailModel(
                                item = TrackerItem("it_1", 1, ItemKind.TASK, title = "Review the parser", body = "", originConvoID = "c1", createdAt = now),
                                comments = listOf(comment), pending = emptyList(),
                                context = ItemContext(null, null, null), availableResolutions = emptyList(), isBusy = false,
                                loadedCommentCount = 1,
                            ),
                            draft = "", onDraftChange = {}, image = { null }, onOpenAttachment = {}, onOpenLink = {},
                            onOpenConversation = {}, onSubmit = {}, onAttach = {}, onVoiceNote = {}, now = now,
                        )
                    }
                }
            }
        }
        return clipboard
    }
}
