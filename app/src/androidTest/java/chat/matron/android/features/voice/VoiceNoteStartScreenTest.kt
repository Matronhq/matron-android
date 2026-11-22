package chat.matron.android.features.voice

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.designsystem.ItemCommentComposer
import chat.matron.android.designsystem.ItemCommentVoice
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.designsystem.VoiceNoteBarMode
import chat.matron.android.designsystem.VoiceNoteRecordingBar
import chat.matron.android.viewmodels.MediaRecorderAudioRecording
import chat.matron.android.viewmodels.VoiceRecorder
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/// The voice-note bars' starting and recording states drawn from fixtures,
/// and one real note on the device's microphone (the emulator's virtual mic
/// is enough): it must pass through Starting before Recording and come back
/// with audio in the file.
@RunWith(AndroidJUnit4::class)
class VoiceNoteStartScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    // Optional review artifacts, containing only the fixtures below.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "voice-start").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) { content() }
                    }
                }
            }
        }

    @Composable
    private fun caption(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    private fun bars(appearance: MatronAppearance, name: String) {
        // A fixed start 7 s before "now" so the clock reads 0:07.
        val start = Instant.ofEpochSecond(1_000)
        show(appearance) {
            caption("Chat composer")
            VoiceNoteRecordingBar(mode = VoiceNoteBarMode.Starting, onCancel = {}, onSend = {})
            HorizontalDivider()
            VoiceNoteRecordingBar(
                mode = VoiceNoteBarMode.Recording(start),
                onCancel = {}, onSend = {},
                now = { start.plusSeconds(7) },
            )
            HorizontalDivider()
            VoiceNoteRecordingBar(mode = VoiceNoteBarMode.Recording(start, isPaused = true), onCancel = {}, onSend = {})
            HorizontalDivider()
            caption("Item reply")
            ItemCommentComposer(
                draft = "Here is the summary", onDraftChange = {}, isBusy = false,
                onSubmit = {}, onAttach = {}, onVoiceNote = {}, voice = ItemCommentVoice.STARTING,
            )
            ItemCommentComposer(
                draft = "Here is the summary", onDraftChange = {}, isBusy = false,
                onSubmit = {}, onAttach = {}, onVoiceNote = {}, voice = ItemCommentVoice.RECORDING,
            )
        }.use {
            node(By.text("Recording 0:07"))
            assertEquals(2, device.findObjects(By.text("Starting microphone…")).size)
            node(By.text("Recording paused"))
            screenshot(name)
        }
    }

    @Test
    fun barsLight() = bars(MatronAppearance.Light, "bars-light")

    @Test
    fun barsDark() = bars(MatronAppearance.Dark, "bars-dark")

    @Test
    fun realMicrophone_startsThenRecordsAndSavesAudio(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val seen = mutableListOf<VoiceRecorder.State>()
        lateinit var recorder: VoiceRecorder
        recorder = VoiceRecorder(
            requestPermission = { true },
            makeRecorder = { MediaRecorderAudioRecording(it) },
            tempDirectory = File(context.cacheDir, "voice-test").apply { mkdirs() },
            measureDuration = MediaRecorderAudioRecording::measuredDuration,
            pause = {
                seen.add(recorder.state.value)
                delay(VoiceRecorder.LIVE_CHECK_INTERVAL)
            },
        )
        val started = System.nanoTime()
        recorder.start()
        val warmUpMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("went live: ${recorder.state.value}", recorder.state.value is VoiceRecorder.State.Recording)
        delay(2.seconds)
        val note = recorder.stop()
        assertNotNull(note)
        val measured = MediaRecorderAudioRecording.measuredDuration(note!!.file)
        android.util.Log.i("VoiceNoteStartTest", "warmUp=${warmUpMs}ms startingChecks=${seen.size} bytes=${note.file.length()} measured=$measured")
        assertTrue("passed through Starting", seen.all { it == VoiceRecorder.State.Starting })
        assertTrue("audio in the file: $measured", (measured ?: 0.seconds) >= 1.seconds)
        note.file.delete()
    }
}
