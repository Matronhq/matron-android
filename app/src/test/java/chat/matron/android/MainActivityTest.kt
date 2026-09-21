package chat.matron.android

import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// [openConversationCallback] is the non-Compose seam behind the agent-spawn
/// card / `SpawnOutcomeRow` "Open" action's nav wiring (Task 3 of the
/// agent-spawn-card plan): the deep link a `started` outcome's room id
/// resolves to. The Compose threading that carries it from the nav host down
/// through `ChatScreen`/`SubChatView`/`TimelineItemView` isn't unit-testable
/// without Compose, but the effect ordering it produces is — this covers the
/// [NewChatSheet] precedent it copies: `prepareConversation` completes fully
/// BEFORE `navigate` fires, so a navigation into `chat/$roomId` never beats
/// the placeholder row it depends on.
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityTest {

    @Test
    fun preparesTheConversationBeforeNavigating() = runTest {
        val calls = mutableListOf<String>()
        val callback = openConversationCallback(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            prepareConversation = { roomId -> calls += "prepare:$roomId" },
            navigate = { roomId -> calls += "navigate:$roomId" },
        )

        callback("room-9")
        advanceUntilIdle()

        assertEquals(listOf("prepare:room-9", "navigate:room-9"), calls)
    }

    @Test
    fun navigateNeverFiresWhenPrepareConversationThrows() = runTest {
        var navigated = false
        var caught: Throwable? = null
        val handler = CoroutineExceptionHandler { _, error -> caught = error }
        val callback = openConversationCallback(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + handler),
            prepareConversation = { throw IllegalStateException("boom") },
            navigate = { navigated = true },
        )

        callback("room-9")
        advanceUntilIdle()

        assertFalse(navigated)
        assertEquals("boom", caught?.message)
    }

    @Test
    fun eachCallCarriesItsOwnRoomId() = runTest {
        val navigated = mutableListOf<String>()
        val callback = openConversationCallback(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            prepareConversation = { },
            navigate = { roomId -> navigated += roomId },
        )

        callback("room-1")
        callback("room-2")
        advanceUntilIdle()

        assertEquals(listOf("room-1", "room-2"), navigated)
    }

    // MARK: - Item detail push guard (Bugbot on #78)

    /// A double tap on an inline item card, or a link to the item already
    /// on screen, must not push a second identical detail.
    @Test
    fun itemAlreadyOnTopIsANoOp() {
        assertTrue(itemIsAlreadyOnTop("item/{itemID}", "it_1", "", "it_1"))
        assertTrue(itemIsAlreadyOnTop("decisions/item/{itemID}", "it_1", "decisions/", "it_1"))
    }

    @Test
    fun anyOtherTopDestinationStillPushes() {
        assertFalse("a different item", itemIsAlreadyOnTop("item/{itemID}", "it_2", "", "it_1"))
        assertFalse("the chat under the card", itemIsAlreadyOnTop("chat/{convoID}", null, "", "it_1"))
        assertFalse("the same item on another tab's stack", itemIsAlreadyOnTop("decisions/item/{itemID}", "it_1", "", "it_1"))
        assertFalse("no destination yet", itemIsAlreadyOnTop(null, null, "", "it_1"))
        // A sub-chat renders on the same `chat/{convoID}` destination as the
        // full chat, on whichever tab hosts it: an item link tapped in a
        // sub-chat body pushes the detail over it, like any other link tap.
        assertFalse("a sub-chat on the Coordinator stack", itemIsAlreadyOnTop("coordinator/chat/{convoID}", null, "coordinator/", "it_1"))
    }

    // MARK: - Sub-chat item links (Bugbot on #78)

    /// `[#65](matron://item/65)` renders underlined, accent and tappable in
    /// EVERY body [MarkdownText] draws, a sub-chat's included — and a tap
    /// reaches [chat.matron.android.designsystem.LocalOpenTrackerItem], which
    /// is null unless a `TrackerItemLinkHost` is installed above it. With the
    /// host wrapped around the `ChatScreen` branch only, a link in a sub-chat
    /// body was swallowed: no navigation, and not even the miss alert.
    ///
    /// `ChatRoute` therefore installs the host ONCE, around both
    /// presentations. The Compose wiring itself isn't unit-testable in this
    /// module (no Compose test rig — see the `testImplementation` block), so
    /// this pins the structure the way matron-apple's view-binding tests pin
    /// theirs: by reading the source. It fails if the host is pushed back
    /// down into one branch, or duplicated per branch (two hosts = two gates,
    /// so a tap in one presentation could no longer supersede a resolve
    /// started in the other).
    @Test
    fun chatRouteInstallsOneItemLinkHostAroundBothPresentations() {
        val route = mainActivitySource()
            .substringAfter("private fun ChatRoute(")
            .substringBefore("private data class ParentLookup")
        val host = route.indexOf("TrackerItemLinkHost(")
        val subChat = route.indexOf("SubChatView(")
        val chatScreen = route.indexOf("ChatScreen(")

        assertTrue("ChatRoute must install a TrackerItemLinkHost", host >= 0)
        assertTrue("ChatRoute must render SubChatView", subChat >= 0)
        assertTrue("ChatRoute must render ChatScreen", chatScreen >= 0)
        assertTrue("the sub-chat presentation must sit inside the link host", subChat > host)
        assertTrue("the full-chat presentation must sit inside the link host", chatScreen > host)
        assertEquals(
            "exactly one host per chat destination — one gate, one alert",
            1,
            Regex("TrackerItemLinkHost\\(").findAll(route).count(),
        )
    }

    /// `MainActivity.kt` as text. Gradle runs unit tests with the module
    /// directory as the working directory; walking up keeps it working from
    /// the repository root too.
    private fun mainActivitySource(): String {
        val relative = "src/main/java/chat/matron/android/MainActivity.kt"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("MainActivity.kt not found from ${System.getProperty("user.dir")}")
    }
}
