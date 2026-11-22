package chat.matron.android.features.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import chat.matron.android.MainActivity
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.features.chatlist.NewChatSheet
import chat.matron.android.journal.DeviceDTO
import chat.matron.android.journal.RPCReply
import chat.matron.android.models.SyncConnectionState
import chat.matron.android.viewmodels.AgentRPCProviding
import chat.matron.android.viewmodels.BoxCapacity
import chat.matron.android.viewmodels.BoxCapacityCaching
import chat.matron.android.viewmodels.BoxStatus
import chat.matron.android.viewmodels.CachedBoxCapacity
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The share screen's states, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class ShareScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(800)
        val directory = File(context.getExternalFilesDir(null), "share").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun waitForText(text: String) =
        assertNotNull("Missing $text", device.wait(Until.findObject(By.textContains(text)), 5_000))

    private val now = Instant.now()
    private val targets = listOf(
        ShareTarget("coord", "Coordinator", isCoordinator = true, lastActivity = now),
        ShareTarget("c1", "Checkout redesign", detail = "box-a", lastActivity = now.minusSeconds(600)),
        ShareTarget("c2", "Release notes for 2.4", detail = "box-b", lastActivity = now.minusSeconds(4_000)),
        ShareTarget("c3", "Menu prices", detail = "box-a", lastActivity = now.minusSeconds(90_000)),
        ShareTarget("c4", "Onboarding emails", detail = "box-b", lastActivity = now.minusSeconds(200_000)),
        ShareTarget("c5", "Payment step sandbox", detail = "box-a", lastActivity = now.minusSeconds(400_000)),
    )

    private fun fixtureImage(): SharedFile {
        val dir = File(context.cacheDir, "share-fixtures").apply { mkdirs() }
        val file = File(dir, "sunset.png")
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 240f, 0xFFFF8A3D.toInt(), 0xFF6A3DFF.toInt(), Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(120f, 150f, 50f, Paint().apply { color = 0xFFFFE08A.toInt() })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return SharedFile("img", file, "sunset.png", "image/png", 2_458_112)
    }

    private fun doc(name: String, mime: String, size: Long) =
        SharedFile(name, File(context.cacheDir, name), name, mime, size)

    private fun show(appearance: MatronAppearance, content: @Composable () -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) { Surface(Modifier.fillMaxSize()) { content() } }
                }
            }
        }

    @Composable
    private fun screen(state: ShareScreenState) {
        ShareScreen(state, {}, {}, {}, {}, {}, {}, {}, {})
    }

    private fun pickerState(image: SharedFile) = ShareScreenState(
        phase = ShareViewModel.Phase.Ready,
        files = listOf(image),
        message = "The sunset from the terrace, for the landing page header",
        targets = targets,
        destination = ShareViewModel.Destination.Conversation("c1"),
        canSend = true,
    )

    @Test
    fun chatPicker() {
        val image = fixtureImage()
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) { screen(pickerState(image)) }.use {
                waitForText("Checkout redesign")
                screenshot("picker-${appearance.rawValue}")
            }
        }
        show(MatronAppearance.Light) {
            screen(
                pickerState(image).copy(
                    query = "menu",
                    targets = ShareTargets.filter(targets, "menu"),
                    hasAnyTargets = true,
                    offersNewConversation = false,
                    destination = ShareViewModel.Destination.Conversation("c3"),
                ),
            )
        }.use {
            waitForText("Menu prices")
            screenshot("picker-search")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun newConversation() {
        val state = ShareScreenState(
            phase = ShareViewModel.Phase.Ready,
            message = "https://example.com/blog/release-checklist",
            targets = targets,
            destination = ShareViewModel.Destination.NewConversation,
            canSend = true,
        )
        show(MatronAppearance.Light) { screen(state) }.use {
            waitForText("Choose a box and folder")
            screenshot("new-conversation")
        }
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) {
                Box(Modifier.fillMaxSize()) {
                    screen(state)
                    ModalBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        NewChatSheet(
                            api = FakeAgents,
                            capacityCache = EmptyCapacityCache,
                            prepareConversation = {},
                            onCreated = {},
                            onCancel = {},
                        )
                    }
                }
            }.use {
                waitForText("Start a chat on")
                screenshot("new-conversation-chooser-${appearance.rawValue}")
            }
        }
    }

    @Test
    fun stagedItems() {
        val files = listOf(
            fixtureImage(),
            doc("checkout-spec.pdf", "application/pdf", 1_258_291),
            doc("old-mockups.zip", "application/zip", 8_912_896),
        )
        val base = ShareScreenState(
            phase = ShareViewModel.Phase.Ready,
            files = files,
            message = "Everything for the payment step review",
            targets = targets,
            destination = ShareViewModel.Destination.Conversation("coord"),
            canSend = true,
        )
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) { screen(base) }.use {
                waitForText("old-mockups.zip")
                screenshot("staged-${appearance.rawValue}")
            }
        }
        show(MatronAppearance.Light) {
            screen(
                base.copy(
                    phase = ShareViewModel.Phase.Sending(
                        ShareProgress(ShareProgress.Step.UPLOADING, 2, 3, "checkout-spec.pdf", 0.45),
                    ),
                    canSend = false,
                ),
            )
        }.use {
            waitForText("Uploading checkout-spec.pdf")
            screenshot("staged-sending")
        }
        show(MatronAppearance.Light) {
            screen(base.copy(errorMessage = "movie.mp4 is larger than 100 MB and wasn't attached."))
        }.use {
            waitForText("larger than 100 MB")
            screenshot("staged-too-large")
        }
    }

    @Test
    fun lockedAndSignedOut() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            show(appearance) { ShareLockedScreen(isAuthenticating = false, errorMessage = null, onUnlock = {}, onCancel = {}) }.use {
                waitForText("Matron is locked")
                screenshot("locked-${appearance.rawValue}")
            }
        }
        show(MatronAppearance.Light) { screen(ShareScreenState(phase = ShareViewModel.Phase.SignedOut)) }.use {
            waitForText("Sign in to Matron first")
            screenshot("signed-out")
        }
    }

    private object FakeAgents : AgentRPCProviding {
        private fun box(id: Long, name: String) = DeviceDTO(
            id = id, kind = "agent", name = name, createdAt = 0, cursor = 0, lag = 0,
            lastSeenAt = System.currentTimeMillis(), isSelf = false, connected = true,
        )

        override suspend fun devices(): List<DeviceDTO> = listOf(box(2, "box-a"), box(3, "box-b"))

        override suspend fun agentRequest(agentDeviceID: Long, method: String, paramsJson: String): RPCReply =
            RPCReply.Ok(Json.parseToJsonElement("""{"folders":[{"path":"~/projects/shop","last_used":1}]}"""))

        override fun boxStatusUpdates(): Flow<Pair<Long, BoxStatus>> = emptyFlow()

        override fun connectionState(): StateFlow<SyncConnectionState> = MutableStateFlow(SyncConnectionState.Running)
    }

    private object EmptyCapacityCache : BoxCapacityCaching {
        override fun loadAll(): Map<Long, CachedBoxCapacity> = emptyMap()
        override fun save(capacity: BoxCapacity, agentID: Long, capturedAtMs: Long) {}
        override fun prune(keeping: Set<Long>) {}
    }
}
