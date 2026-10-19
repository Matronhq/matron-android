package chat.matron.android.designsystem

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// The reply composer while a voice note records: one send, enabled with or
/// without text, no send on the recording strip, the mic inert.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ItemCommentComposerVoiceTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun setComposer(draft: String, voice: ItemCommentVoice, log: MutableList<String>) {
        compose.setContent {
            MatronTheme(appearance = MatronAppearance.Light) {
                Surface {
                    ItemCommentComposer(
                        draft = draft, onDraftChange = {}, isBusy = false,
                        onSubmit = { log += "submit" }, onAttach = {}, onVoiceNote = { log += "mic" },
                        voice = voice, onCancelRecording = { log += "cancel" },
                    )
                }
            }
        }
    }

    @Test
    fun recordingLeavesTheComposerSendAsTheOnlySend() {
        val log = mutableListOf<String>()
        setComposer("Go with option B", ItemCommentVoice.RECORDING, log)
        compose.onNodeWithText("Recording…").assertExists()
        compose.onNodeWithContentDescription("Send voice note").assertDoesNotExist()
        compose.onNodeWithContentDescription("Record voice note").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Send reply with voice note").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Cancel recording").performClick()
        compose.runOnIdle { assertEquals(listOf("submit", "cancel"), log) }
    }

    @Test
    fun aRecordingNoteIsSendableWithNoText() {
        val log = mutableListOf<String>()
        setComposer("", ItemCommentVoice.RECORDING, log)
        compose.onNodeWithContentDescription("Send reply with voice note").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("submit"), log) }
    }

    @Test
    fun sendWaitsWhileTheMicrophoneStarts() {
        val log = mutableListOf<String>()
        setComposer("Go with option B", ItemCommentVoice.STARTING, log)
        compose.onNodeWithContentDescription("Send reply").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Record voice note").assertIsNotEnabled()
        compose.onNodeWithText("Recording…").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), log) }
    }

    @Test
    fun sendWaitsWhileTheStoppedNoteIsTakenIntoTheSend() {
        val log = mutableListOf<String>()
        setComposer("Typed after the note was sent", ItemCommentVoice.SENDING, log)
        compose.onNodeWithContentDescription("Send reply").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Record voice note").assertIsNotEnabled()
        compose.onNodeWithText("Recording…").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), log) }
    }

    @Test
    fun idleComposerKeepsItsMicAndTextSend() {
        val log = mutableListOf<String>()
        setComposer("Go with option B", ItemCommentVoice.IDLE, log)
        compose.onNodeWithText("Recording…").assertDoesNotExist()
        compose.onNodeWithContentDescription("Record voice note").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Send reply").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("mic", "submit"), log) }
    }

    /// The whole thread with a draft and a note recording; writes a PNG under
    /// build/reports for review.
    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
    fun threadWithARecordingNote() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val question = TrackerComment("ic_1", "it_1", ItemAuthor.AGENT, body = "Which layout should the settings page use?", createdAt = now.minusSeconds(300))
        val submitted = mutableStateOf(0)
        compose.setContent {
            MatronTheme(appearance = MatronAppearance.Light) {
                Surface {
                    ItemDetailView(
                        model = ItemDetailModel(
                            item = TrackerItem("it_1", 1, ItemKind.QUESTION, title = "Pick a settings layout", body = "Two options are drafted.", originConvoID = "c1", createdAt = now),
                            comments = listOf(question), pending = emptyList(),
                            context = ItemContext(null, null, null), availableResolutions = emptyList(), isBusy = false,
                            loadedCommentCount = 1,
                        ),
                        draft = "Go with the second option, and keep the old toggle", onDraftChange = {}, image = { null },
                        onOpenAttachment = {}, onOpenLink = {}, onOpenConversation = {},
                        onSubmit = { submitted.value += 1 }, onAttach = {}, onVoiceNote = {}, now = now,
                        voice = ItemCommentVoice.RECORDING,
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Send reply with voice note").performClick()
        compose.runOnIdle { assertEquals(1, submitted.value) }
        val screenshot = compose.runOnIdle {
            val view = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                view.draw(android.graphics.Canvas(it))
            }
        }
        val output = File("build/reports/item-thread-recording.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
