package chat.matron.android.features.chat

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.chat.TimelineItem
import chat.matron.android.chat.TimelineService
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.models.AttachmentLimits
import chat.matron.android.viewmodels.ComposerViewModel
import chat.matron.android.viewmodels.RecentStartFolders
import chat.matron.android.viewmodels.SharedPreferencesKeyValueStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The composer refusing a file over 100 MB, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class AttachmentLimitScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val directory = File(context.getExternalFilesDir(null), "attachment-limit").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    /** A timeline that never sends: the screen only stages and refuses. */
    private object IdleTimeline : TimelineService {
        override fun items(): Flow<List<TimelineItem>> = emptyFlow()
        override suspend fun sendText(body: String, inReplyTo: String?) {}
        override suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String) {}
        override suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
        override suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
        override suspend fun paginateBackward(requestSize: Int): Boolean = false
        override suspend fun markAsRead() {}
    }

    private fun file(dir: File, name: String, length: Long): File =
        File(dir, name).also { RandomAccessFile(it, "rw").use { raf -> raf.setLength(length) } }

    private fun composer(): ComposerViewModel {
        val prefs = context.getSharedPreferences("attachment-limit-test", Context.MODE_PRIVATE)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "staging").toFile()
        return ComposerViewModel(
            "c1", IdleTimeline, emptyList(), RecentStartFolders(SharedPreferencesKeyValueStore(prefs)), staging,
        )
    }

    private fun show(appearance: MatronAppearance, vm: ComposerViewModel) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding(),
                            verticalArrangement = Arrangement.Bottom,
                        ) { ComposerView(vm) }
                    }
                }
            }
        }

    private fun refusal(appearance: MatronAppearance, name: String) {
        val picked = Files.createTempDirectory(context.cacheDir.toPath(), "picked").toFile()
        val vm = composer()
        runBlocking { vm.attachFiles(listOf(file(picked, "site-walkthrough.mp4", 40_000_000))) }
        show(appearance, vm).use {
            // After the composer is up: it clears a stale error on appearing.
            device.waitForIdle()
            runBlocking { vm.attachFiles(listOf(file(picked, "full-meeting.mov", AttachmentLimits.MAX_BYTES + 1))) }
            assertNotNull(
                device.wait(Until.findObject(By.text(AttachmentLimits.oversizeMessage("full-meeting.mov"))), 5_000),
            )
            screenshot(name)
        }
        vm.discardAttachments()
        picked.deleteRecursively()
    }

    @Test
    fun oversizeRefusalLight() = refusal(MatronAppearance.Light, "composer-refusal-light")

    @Test
    fun oversizeRefusalDark() = refusal(MatronAppearance.Dark, "composer-refusal-dark")
}
