package chat.matron.android.viewmodels

import chat.matron.android.chat.ChatSummary
import chat.matron.android.models.BotIdentity
import chat.matron.android.search.SearchHit
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/// Ported from matron-apple's `SearchViewModelTests`.
class SearchViewModelTest {
    private val claude = BotIdentity(matrixID = "@claude:s", displayName = "Claude", avatarURL = null)

    private fun chat(id: String, title: String) =
        ChatSummary(id = id, title = title, bot = claude, lastActivity = null, unreadCount = 0)

    @Test
    fun query_populatesResults() = runBlocking {
        val fakeSearch = FakeSearchService(
            hits = listOf(
                SearchHit("\$1", "!r:s", "@a:s", Instant.now(), "<mark>hello</mark> world"),
            ),
        )
        val vm = SearchViewModel(fakeSearch, emptyList())
        vm.query = "hello"
        vm.search()
        assertEquals(1, vm.messageHits.value.size)
    }

    @Test
    fun chatHits_filterByTitleOrBotName() {
        val chats = listOf(chat("!1:s", "Auth bug"), chat("!2:s", "Refactor"))
        val vm = SearchViewModel(FakeSearchService(), chats)
        vm.query = "auth"
        assertEquals(listOf("!1:s"), vm.chatHits.map { it.id })
    }

    @Test
    fun chatTitle_resolvesViaAllChats() {
        val chats = listOf(chat("!a:s", "Auth bug"), chat("!b:s", "Refactor"))
        val vm = SearchViewModel(FakeSearchService(), chats)
        assertEquals("Auth bug", vm.chatTitle("!a:s"))
        assertEquals("Refactor", vm.chatTitle("!b:s"))
        assertEquals("!unknown:s", vm.chatTitle("!unknown:s"))
    }

    @Test
    fun updateChats_refreshesChatHitsAndTitles() {
        val vm = SearchViewModel(FakeSearchService(), listOf(chat("!1:s", "Auth bug")))
        vm.query = "refactor"
        assertEquals(emptyList<String>(), vm.chatHits.map { it.id })

        vm.updateChats(listOf(chat("!1:s", "Auth fix"), chat("!2:s", "Refactor search")))
        assertEquals(listOf("!2:s"), vm.chatHits.map { it.id })
        assertEquals("Auth fix", vm.chatTitle("!1:s"))
    }

    @Test
    fun emptyState_showsNoResults() = runBlocking {
        val vm = SearchViewModel(FakeSearchService(), emptyList())
        vm.query = "anything"
        vm.search()
        assertEquals("No results.", vm.emptyResultsMessage)
    }
}
