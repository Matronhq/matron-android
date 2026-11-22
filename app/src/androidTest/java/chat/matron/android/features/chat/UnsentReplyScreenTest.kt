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
import chat.matron.android.viewmodels.ComposerDraftMemory
import chat.matron.android.viewmodels.ComposerViewModel
import chat.matron.android.viewmodels.RecentStartFolders
import chat.matron.android.viewmodels.SharedPreferencesKeyValueStore
import chat.matron.android.viewmodels.UnsentReplyStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A chat composer reopened with a fresh store (as after a restart) shows the unsent text and files; fixtures only. */
@RunWith(AndroidJUnit4::class)
class UnsentReplyScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val directory = File(context.getExternalFilesDir(null), "unsent-reply").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private object IdleTimeline : TimelineService {
        override fun items(): Flow<List<TimelineItem>> = emptyFlow()
        override suspend fun sendText(body: String, inReplyTo: String?) {}
        override suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String) {}
        override suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
        override suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
        override suspend fun paginateBackward(requestSize: Int): Boolean = false
        override suspend fun markAsRead() {}
    }

    private fun composer(store: UnsentReplyStore): ComposerViewModel {
        val prefs = context.getSharedPreferences("unsent-reply-test", Context.MODE_PRIVATE)
        return ComposerViewModel(
            "c1", IdleTimeline, emptyList(), RecentStartFolders(SharedPreferencesKeyValueStore(prefs)),
            store.stagingDirectory, unsentReplies = store,
        )
    }

    private fun restored(appearance: MatronAppearance, name: String) {
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "unsent").toFile()
        fun store() = UnsentReplyStore(File(root, "boxes"), File(root, "staging"))
        val picked = Files.createTempDirectory(context.cacheDir.toPath(), "picked").toFile()
        // Before the "restart": type and attach, then drop the view model and
        // the in-memory draft cache.
        composer(store()).apply {
            input = "Here is the walkthrough and the floor plan, can you check the measurements?"
            runBlocking {
                attachFiles(listOf(File(picked, "floor-plan.pdf").apply { writeBytes(ByteArray(240_000)) }))
                attachFiles(listOf(File(picked, "walkthrough.mp4").apply { writeBytes(ByteArray(3_200_000)) }))
            }
        }
        ComposerDraftMemory.resetForTesting()
        val vm = composer(store())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
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
            assertNotNull(device.wait(Until.findObject(By.textContains("floor plan")), 5_000))
            screenshot(name)
        }
        vm.discardAttachments()
        root.deleteRecursively()
        picked.deleteRecursively()
    }

    @Test
    fun restoredAfterRestartLight() = restored(MatronAppearance.Light, "composer-restored-light")

    @Test
    fun restoredAfterRestartDark() = restored(MatronAppearance.Dark, "composer-restored-dark")
}
