package chat.matron.android.features.voice

import android.Manifest
import android.media.AudioManager
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import chat.matron.android.designsystem.MicrophoneMenu
import chat.matron.android.designsystem.VoiceNoteBarMode
import chat.matron.android.designsystem.VoiceNoteRecordingBar
import chat.matron.android.models.Microphone
import chat.matron.android.platform.MicrophoneCatalog
import chat.matron.android.viewmodels.MediaRecorderAudioRecording
import chat.matron.android.viewmodels.MicrophonePreference
import chat.matron.android.viewmodels.VoiceRecorder
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/// The microphone menu in the chat composer's recording bar and the item
/// reply bar, drawn from fixtures, and a real note on the device's
/// microphones: the inputs are listed, the recording names the one in use,
/// and a pick mid-note keeps the same recording going.
@RunWith(AndroidJUnit4::class)
class MicrophoneMenuScreenTest {
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
        val directory = File(context.getExternalFilesDir(null), "voice-mic").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        // A Surface, as in the app, so text picks up the theme's content colour.
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Column(
                                Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) { content() }
                        }
                    }
                }
            }
        }

    private val phone = Microphone(MicrophoneCatalog.BUILT_IN_ID, "Phone microphone")
    private val headset = Microphone("7:00:11:22:33:44:55", "Headset A")
    private val usb = Microphone("22:USB headset B", "USB headset B")
    private val mics = listOf(phone, headset, usb)

    @Composable
    private fun caption(text: String) =
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    private fun chatBar(appearance: MatronAppearance, name: String, current: Microphone, open: Boolean) {
        show(appearance) {
            caption("Chat composer")
            val start = java.time.Instant.ofEpochSecond(1_000)
            VoiceNoteRecordingBar(
                mode = VoiceNoteBarMode.Recording(start),
                onCancel = {}, onSend = {},
                now = { start.plusSeconds(7) },
            )
            MicrophoneMenu(
                current = current, preferredID = null, available = { mics }, onSelect = {},
                initiallyExpanded = open,
            )
        }.use {
            node(By.text(current.name))
            if (open) node(By.text("USB headset B"))
            screenshot(name)
        }
    }

    @Test
    fun chatBarNamesTheMicrophone() = chatBar(MatronAppearance.Light, "chat-bar-light", phone, open = false)

    @Test
    fun chatBarMenuOpen() = chatBar(MatronAppearance.Light, "chat-menu-open-light", phone, open = true)

    @Test
    fun chatBarMenuOpenDark() = chatBar(MatronAppearance.Dark, "chat-menu-open-dark", phone, open = true)

    @Test
    fun chatBarAfterPickingAHeadset() = chatBar(MatronAppearance.Light, "chat-bar-headset-light", headset, open = false)

    @Test
    fun itemReplyBar() {
        show(MatronAppearance.Light) {
            caption("Item reply")
            ItemCommentComposer(
                draft = "Here is the summary", onDraftChange = {}, isBusy = false,
                onSubmit = {}, onAttach = {}, onVoiceNote = {}, voice = ItemCommentVoice.RECORDING,
                microphoneMenu = {
                    MicrophoneMenu(current = headset, preferredID = headset.id, available = { mics }, onSelect = {})
                },
            )
        }.use {
            node(By.text("Headset A"))
            screenshot("item-reply-light")
        }
    }

    @Test
    fun realMicrophones_listedNamedAndSwitchableMidNote(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val audioManager = context.getSystemService(AudioManager::class.java)
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach {
            Log.i(TAG, "input type=${it.type} address=${it.address} product=${it.productName} -> ${MicrophoneCatalog.microphone(it)}")
        }
        val catalog = MicrophoneCatalog(context)
        val available = catalog.list()
        Log.i(TAG, "catalogue=$available")
        assertTrue("the phone's microphone is listed", available.any { it.id == MicrophoneCatalog.BUILT_IN_ID })
        val prefs = MicrophonePreference.InMemory(MicrophoneCatalog.BUILT_IN_ID)
        val recorder = VoiceRecorder(
            requestPermission = { true },
            makeRecorder = { MediaRecorderAudioRecording(it, catalog, audioManager) },
            tempDirectory = File(context.cacheDir, "voice-test").apply { mkdirs() },
            listMicrophones = catalog::list,
            preferences = prefs,
        )
        recorder.start()
        delay(500)
        Log.i(TAG, "recording from ${recorder.microphone.value}")
        assertEquals(MicrophoneCatalog.BUILT_IN_ID, recorder.microphone.value?.id)
        // Every other input in turn, then back: the same note keeps going.
        (available.drop(1) + available.first()).forEach { mic ->
            recorder.selectMicrophone(mic.id)
            delay(700)
            Log.i(TAG, "picked ${mic.name}; recording from ${recorder.microphone.value}")
        }
        delay(1_000)
        val note = recorder.stop()
        assertNotNull(note)
        Log.i(TAG, "note bytes=${note!!.file.length()} measured=${durationMs(note.file)}ms")
        assertTrue(note.file.length() > 0)
        note.file.delete()
    }

    private fun durationMs(file: File): Long? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private companion object {
        const val TAG = "MicrophoneMenuTest"
    }
}
