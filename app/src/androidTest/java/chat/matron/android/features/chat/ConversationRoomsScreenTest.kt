package chat.matron.android.features.chat

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
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
import chat.matron.android.chat.ChatService
import chat.matron.android.chat.ChatSummary
import chat.matron.android.chat.MediaService
import chat.matron.android.chat.SubChatSummary
import chat.matron.android.chat.TimelineItem
import chat.matron.android.chat.TimelineService
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.MatronTheme
import chat.matron.android.models.BotCommandCatalog
import chat.matron.android.models.ConversationRoom
import chat.matron.android.models.RoomSessionState
import chat.matron.android.viewmodels.ChatViewModel
import chat.matron.android.viewmodels.ComposerViewModel
import chat.matron.android.viewmodels.RecentStartFolders
import chat.matron.android.viewmodels.SharedPreferencesKeyValueStore
import chat.matron.android.viewmodels.SubChatStripViewModel
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A chat's "Rooms · n" chip and the rooms sheet, drawn from fixtures; no server or account required. */
@RunWith(AndroidJUnit4::class)
class ConversationRoomsScreenTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val timeout = 5_000L
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun tearDown() = scope.cancel()

    private fun node(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Missing $selector" }

    // Optional review artifacts, containing only the fixtures below.
    private fun screenshot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        device.waitForIdle()
        Thread.sleep(700)
        val directory = File(context.getExternalFilesDir(null), "conversation-rooms").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }

    private val base = Instant.parse("2026-09-21T09:40:00Z")

    private fun text(seq: Long, sender: String, body: String, own: Boolean = false) = TimelineItem(
        id = seq.toString(), sender = sender, timestamp = base.plusSeconds(seq * 60),
        kind = TimelineItem.Kind.Text(body, null), isOwn = own,
    )

    private class FixedTimeline(private val rows: List<TimelineItem>) : TimelineService {
        override fun items(): Flow<List<TimelineItem>> = flowOf(rows)
        override suspend fun sendText(body: String, inReplyTo: String?) {}
        override suspend fun sendButtonResponse(selectedValues: List<String>, inReplyTo: String) {}
        override suspend fun sendImage(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
        override suspend fun sendFile(data: ByteArray, filename: String, mimeType: String, caption: String?) {}
        override suspend fun paginateBackward(requestSize: Int): Boolean = false
        override suspend fun markAsRead() {}
    }

    private object NoMedia : MediaService {
        override suspend fun image(url: String): ByteArray? = null
    }

    private object NoChildren : ChatService {
        override fun chatSummaries(): Flow<List<ChatSummary>> = flowOf(emptyList())
        override fun children(parentConvoID: String): Flow<List<SubChatSummary>> = flowOf(emptyList())
        override suspend fun createChat(botID: String): String = ""
        override suspend fun refresh() {}
        override suspend fun forceSnapshot() {}
        override suspend fun mute(roomID: String) {}
    }

    private val store by lazy {
        SharedPreferencesKeyValueStore(context.getSharedPreferences("rooms-screen-test", Context.MODE_PRIVATE))
    }

    private fun chatVM(roomID: String, rows: List<TimelineItem>) =
        ChatViewModel(roomID = roomID, timeline = FixedTimeline(rows), media = NoMedia, scope = scope, answeredPromptStore = store)

    private fun stripVM(id: String) = SubChatStripViewModel(chat = NoChildren, parentConvoID = id, scope = scope)

    private val ownRows = listOf(
        text(1, "you", "Can you and box-b agree the release checklist?", own = true),
        text(2, "box-a", "Started a room with box-b to go through it together. I'll report back here."),
    )

    private val roomRows = listOf(
        text(3, "box-a", "Here is my draft: build, run the unit suite, then the screen tests on the emulator."),
        text(4, "box-b", "Agreed. Add a smoke test of sign-in on a fresh install before tagging."),
        text(5, "box-a", "Added. I'll take the build and unit suite; you take the screen tests and the smoke test."),
    )

    private val launchRoom = ConversationRoom("room-1", "Release checklist", RoomSessionState.RUNNING)
    private val otherRooms = listOf(
        launchRoom,
        ConversationRoom("room-2", "Flaky test triage", RoomSessionState.WAITING),
        ConversationRoom("room-3", "Docs review", RoomSessionState.DONE),
    )

    private fun show(appearance: MatronAppearance, rooms: List<ConversationRoom>): ActivityScenario<MainActivity> {
        val host = ConversationRoomsHost(
            rooms = MutableStateFlow(rooms),
            room = { id -> chatVM(id, roomRows) to stripVM(id) },
            subagent = { id, room -> chatVM(id, emptyList()) to stripVM(room) },
        )
        val chat = chatVM("c-a", ownRows)
        val composer = ComposerViewModel(
            roomID = "c-a", timeline = FixedTimeline(ownRows), commands = BotCommandCatalog.claudeBridge,
            recentFolders = RecentStartFolders(store), stagingDirectory = context.cacheDir,
        )
        return ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MatronTheme(appearance) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
                            ChatScreen(
                                chatVM = chat,
                                composerVM = composer,
                                stripVM = stripVM("c-a"),
                                chatTitle = "Release prep",
                                onBack = {},
                                onOpenChild = {},
                                onOpenConversation = {},
                                boxName = "box-a",
                                conversationRooms = host,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun headerChipAndTheListOfSeveralRooms() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            val suffix = appearance.name.lowercase()
            show(appearance, otherRooms).use {
                node(By.text("Rooms · 3"))
                screenshot("chat-header-chip-$suffix")
                node(By.text("Rooms · 3")).click()
                // Each row reads as one element: state, then title.
                node(By.desc("Waiting, Flaky test triage"))
                node(By.desc("Done, Docs review"))
                screenshot("rooms-list-$suffix")
                node(By.desc("Running, Release checklist")).click()
                node(By.text("Open as chat"))
                node(By.textContains("smoke test of sign-in"))
                screenshot("rooms-list-opened-room-$suffix")
            }
        }
    }

    @Test
    fun aSoleRoomOpensStraightOnItsTimeline() {
        for (appearance in listOf(MatronAppearance.Light, MatronAppearance.Dark)) {
            val suffix = appearance.name.lowercase()
            show(appearance, listOf(launchRoom)).use {
                node(By.text("Rooms · 1")).click()
                node(By.text("Release checklist"))
                node(By.text("Open as chat"))
                node(By.textContains("smoke test of sign-in"))
                screenshot("sole-room-timeline-$suffix")
            }
        }
    }
}
