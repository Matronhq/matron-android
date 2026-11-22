package chat.matron.android.designsystem

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.features.chat.PickedCopy
import chat.matron.android.features.chat.copyUriToTemp
import chat.matron.android.models.StagedAttachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pasting a copied file into the item reply composer attaches it under its own name; fixture file only, no account. */
@RunWith(AndroidJUnit4::class)
class PasteAttachmentScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context = instrumentation.targetContext
    private var inserted: Uri? = null

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), 5_000)) { "Missing $selector" }

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(500)
        val directory = File(context.getExternalFilesDir(null), "paste").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    /// A small PDF-typed fixture in Downloads, as a files app would offer it.
    private fun fixtureFile(): Uri {
        // A leftover from an earlier run would make the system rename this one.
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf("meeting-notes.pdf"),
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "meeting-notes.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri)!!.use { it.write("%PDF-1.4 fixture".toByteArray()) }
        inserted = uri
        return uri
    }

    @After
    fun cleanUp() {
        inserted?.let { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    @Test
    fun pastingACopiedFileAttachesItWithItsName() {
        val uri = fixtureFile()
        val name = context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)!!
            .use { it.moveToFirst(); it.getString(0) }
        val staging = File(context.cacheDir, "paste-test").apply { mkdirs() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newUri(activity.contentResolver, "notes", uri))
                activity.setContent {
                    MatronTheme(MatronAppearance.Light) {
                        var draft by remember { mutableStateOf("") }
                        var tray by remember { mutableStateOf(listOf<StagedAttachment>()) }
                        val scope = rememberCoroutineScope()
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                                .systemBarsPadding().imePadding(),
                        ) {
                            Spacer(Modifier.weight(1f))
                            ItemCommentComposer(
                                draft = draft,
                                onDraftChange = { draft = it },
                                isBusy = false,
                                onSubmit = {},
                                onAttach = {},
                                onVoiceNote = {},
                                attachments = tray,
                                onRemoveAttachment = { id -> tray = tray.filterNot { it.id == id } },
                                onPasteAttachments = { uris ->
                                    scope.launch {
                                        val staged = withContext(Dispatchers.IO) {
                                            uris.mapNotNull { (copyUriToTemp(activity, it, AttachmentPaste::fallbackName) as? PickedCopy.Copied)?.file }
                                                .map { StagedAttachment.stage(it, staging) }
                                        }
                                        tray = tray + staged
                                    }
                                },
                            )
                        }
                    }
                }
            }
            // The system's "Copied" preview sits over the bottom of the
            // screen for a few seconds after a copy; close it first.
            device.wait(Until.findObject(By.pkg("com.android.systemui").desc("Dismiss")), 2_000)?.click()
            device.wait(Until.gone(By.pkg("com.android.systemui").text("Copied")), 10_000)
            val field = node(By.text("Reply…"))
            field.click()
            Thread.sleep(500)
            field.longClick()
            node(By.text("Paste")).click()
            node(By.text(name))
            assertTrue("the toolbar closes after the paste", device.wait(Until.gone(By.text("Paste")), 3_000))
            screenshot("pasted-file")
        }
    }
}
