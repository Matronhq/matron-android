package chat.matron.android.features

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import chat.matron.android.chat.ChatService
import chat.matron.android.chat.ChatSummary
import chat.matron.android.chat.SubChatSummary
import chat.matron.android.designsystem.SyncBannerState
import chat.matron.android.features.chatlist.ArchivedChatsScreen
import chat.matron.android.features.chatlist.ChatListScreen
import chat.matron.android.features.settings.DeviceSettingsScreen
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.journal.UserSettings
import chat.matron.android.journal.UserSettingsProviding
import chat.matron.android.models.BotIdentity
import chat.matron.android.models.UserSession
import chat.matron.android.viewmodels.AutoArchiveSettingViewModel
import chat.matron.android.viewmodels.ChatListViewModel
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/// The archive's screens: Archive in the chat list's long-press menu and the
/// way into Archived, the Archived list's search and Unarchive, and Settings
/// → Archive.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArchiveScreensTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    internal class FakeChats(
        private val active: List<ChatSummary>,
        private val archived: List<ChatSummary>,
    ) : ChatService {
        val calls = mutableListOf<Pair<List<String>, Boolean>>()
        override fun chatSummaries(): Flow<List<ChatSummary>> = flowOf(active)
        override fun archivedChatSummaries(): Flow<List<ChatSummary>> = flowOf(archived)
        override fun children(parentConvoID: String): Flow<List<SubChatSummary>> = emptyFlow()
        override suspend fun createChat(botID: String): String = "x"
        override suspend fun refresh() {}
        override suspend fun forceSnapshot() {}
        override suspend fun mute(roomID: String) {}
        override suspend fun setArchived(roomIDs: List<String>, archived: Boolean) { calls += roomIDs to archived }
    }

    internal companion object {
        private val now: Instant = Instant.now()
        fun chat(id: String, title: String, snippet: String, minutesAgo: Long, unread: Int = 0) = ChatSummary(
            id = id, title = title, bot = BotIdentity("agent:box-a", "box-a", null),
            lastActivity = now.minusSeconds(minutesAgo * 60), unreadCount = unread, snippet = snippet,
        )
        val active = listOf(
            chat("c1", "Release checklist", "All checks passed on the release branch.", 4, unread = 2),
            chat("c2", "Fix flaky upload test", "The retry now waits for the server's answer.", 50),
            chat("c3", "Docs: getting started", "Draft ready for review.", 60 * 26),
        )
        val archived = listOf(
            chat("a1", "Migrate settings storage", "Merged; nothing left to do.", 60 * 24 * 12),
            chat("a2", "Weekly dependency bump", "Updated 14 packages, tests green.", 60 * 24 * 35),
            chat("a3", "Onboarding copy review", "Final wording agreed.", 60 * 24 * 70),
        )
    }

    @Test
    fun archiveFromTheListAndOpenArchived() {
        val chats = FakeChats(active, archived)
        val vm = ChatListViewModel(chats, CoroutineScope(SupervisorJob() + Dispatchers.Main)).also { it.start() }
        var openedArchived = 0
        compose.setContent {
            MaterialTheme {
                ChatListScreen(
                    viewModel = vm, connectionState = SyncBannerState.Running, hasEverConnected = true,
                    searchAvailable = true, chat = chats, onOpenChat = {}, onNewChat = {}, onOpenSearch = {},
                    onOpenSettings = {}, onSignOut = {}, onOpenArchived = { openedArchived++ },
                )
            }
        }
        compose.waitUntil(3000) { compose.onAllNodesWithText("Release checklist").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Release checklist").performTouchInput { longClick() }
        compose.onNodeWithText("Archive").performClick()
        compose.waitUntil(3000) { chats.calls.isNotEmpty() }
        assertEquals(listOf(listOf("c1") to true), chats.calls)

        // The row at the foot of the list, then the overflow menu's item.
        compose.onNodeWithText("Archived").performClick()
        compose.runOnIdle { assertEquals(1, openedArchived) }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onAllNodesWithText("Archived").onLast().performClick()
        compose.runOnIdle { assertEquals(2, openedArchived) }
    }

    @Test
    fun archivedSearchesAndUnarchives() {
        val chats = FakeChats(active, archived)
        compose.setContent { MaterialTheme { ArchivedChatsScreen(chat = chats, onOpenChat = {}, onBack = {}) } }
        compose.onNodeWithText("Weekly dependency bump").assertExists()
        compose.onNodeWithTag("archived-search").performTextInput("onboarding")
        compose.onNodeWithText("Weekly dependency bump").assertDoesNotExist()
        compose.onNodeWithText("Onboarding copy review").performTouchInput { longClick() }
        compose.onNodeWithText("Unarchive").performClick()
        compose.waitUntil(3000) { chats.calls.isNotEmpty() }
        assertEquals(listOf(listOf("a3") to false), chats.calls)
    }

    @Test
    fun archivedSaysWhenNothingMatches() {
        compose.setContent { MaterialTheme { ArchivedChatsScreen(chat = FakeChats(active, archived), onOpenChat = {}, onBack = {}) } }
        compose.onNodeWithTag("archived-search").performTextInput("zebra")
        compose.onNodeWithText("No archived chats match “zebra”.").assertExists()
    }

    internal class SettingsApi : UserSettingsProviding {
        var stored = UserSettings(notices = true, autoArchiveDays = 30)
        val patches = mutableListOf<Int>()
        override suspend fun settings(): UserSettings = stored
        override suspend fun updateSettings(notices: Boolean): UserSettings = stored
        override suspend fun updateAutoArchiveDays(days: Int): UserSettings {
            patches += days
            stored = stored.copy(autoArchiveDays = days)
            return stored
        }
    }

    @Test
    fun settingsArchivePicksAWindow() {
        val api = SettingsApi()
        val model = AutoArchiveSettingViewModel(api, { MutableSharedFlow() }, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        compose.setContent {
            MaterialTheme {
                DeviceSettingsScreen(
                    session = UserSession("alice", "1", "https://journal.example.com", "t"),
                    devicesApi = null, appearance = MatronAppearance.System, onAppearanceChange = {},
                    onManageDevices = {}, autoArchive = model, onBack = {},
                )
            }
        }
        compose.waitUntil(3000) { compose.onAllNodesWithText("After 30 days").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Archive idle chats").assertExists()
        compose.onNodeWithText("After 30 days").performClick()
        compose.onNodeWithText("After 90 days").performClick()
        compose.waitUntil(3000) { api.patches.isNotEmpty() }
        assertEquals(listOf(90), api.patches)
        compose.onNodeWithText("After 90 days").assertExists()
    }
}
