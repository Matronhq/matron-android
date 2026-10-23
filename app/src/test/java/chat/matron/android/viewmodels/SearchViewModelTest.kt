package chat.matron.android.viewmodels

import chat.matron.android.chat.ChatSummary
import chat.matron.android.journal.NumberKind
import chat.matron.android.journal.TrackerSearchHit
import chat.matron.android.journal.TrackerSearchKind
import chat.matron.android.models.BotIdentity
import chat.matron.android.search.SearchHit
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    /// The session short is VISIBLE in the row (`b5` rendered as `Y:b5`), so
    /// it must stay searchable even though the branch peels it out of the
    /// stored title. Diverges from matron-apple, whose `chatHits` matches
    /// only title + bot name (gap unfixed there as of apple #156).
    @Test
    fun chatHits_matchTheVisibleSessionShortAndTagForm() {
        val tagged = ChatSummary(
            id = "!1:s", title = "Auth bug", bot = claude,
            lastActivity = null, unreadCount = 0,
            boxName = "dev-y", sessionShort = "b5", boxShort = "Y",
        )
        val other = ChatSummary(
            id = "!2:s", title = "Refactor", bot = claude,
            lastActivity = null, unreadCount = 0,
            boxName = "dev-z", sessionShort = "c7", boxShort = "Z",
        )
        val room = ChatSummary(
            id = "!3:s", title = "mac ↔ dev-z", bot = claude,
            lastActivity = null, unreadCount = 0,
            sessionShort = "ab",
            roomBoxNames = listOf("dev-y", "dev-z"), roomBoxShorts = listOf("Y", "Z"),
        )
        val vm = SearchViewModel(FakeSearchService(), listOf(tagged, other, room))

        vm.query = "b5"
        assertEquals("the bare short finds its chat", listOf("!1:s"), vm.chatHits.map { it.id })

        vm.query = "y:b5"
        assertEquals("the displayed letter:short form matches too", listOf("!1:s"), vm.chatHits.map { it.id })

        vm.query = "y↔z:ab"
        assertEquals("the displayed room tag matches too", listOf("!3:s"), vm.chatHits.map { it.id })

        vm.query = "d4"
        assertEquals("an unrelated short matches nothing", emptyList<String>(), vm.chatHits.map { it.id })
    }

    /// Guard mirrored from matron-apple #157: a query that IS one of a
    /// chat's box letters never TAG-matches that chat — `contains` on the
    /// rendered `y:b5` would otherwise let a bare `y` light up every chat
    /// on that box and drown real hits. The guard is per-chat: `y` still
    /// title-matches, and each letter of a room's pair is guarded alone.
    @Test
    fun chatHits_bareBoxLetterNeverTagMatchesItsChats() {
        val tagged = ChatSummary(
            id = "!1:s", title = "Auth bug", bot = claude,
            lastActivity = null, unreadCount = 0,
            boxName = "dev-y", sessionShort = "b5", boxShort = "Y",
        )
        val titled = ChatSummary(
            id = "!2:s", title = "Sync history", bot = claude,
            lastActivity = null, unreadCount = 0,
            boxName = "dev-y", sessionShort = "c7", boxShort = "Y",
        )
        val room = ChatSummary(
            id = "!3:s", title = "mac pair", bot = claude,
            lastActivity = null, unreadCount = 0,
            sessionShort = "ab",
            roomBoxNames = listOf("dev-y", "dev-z"), roomBoxShorts = listOf("Y", "Z"),
        )
        val vm = SearchViewModel(FakeSearchService(), listOf(tagged, titled, room))

        vm.query = "y"
        assertEquals(
            "a bare box letter tag-matches nothing; only the title hit survives",
            listOf("!2:s"), vm.chatHits.map { it.id },
        )

        vm.query = "Y"
        assertEquals("the guard is case-insensitive", listOf("!2:s"), vm.chatHits.map { it.id })

        vm.query = "z"
        assertEquals(
            "each letter of a room's pair is guarded alone",
            emptyList<String>(), vm.chatHits.map { it.id },
        )
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

    /// Ports matron-apple's
    /// `test_hitTitle_carriesTagHalvesAndDropsRoomMarkerBesideTag`: the
    /// search rows carry the same colored tag as the chat list — the
    /// resolver hands the row the tag halves plus a title with the room
    /// marker dropped exactly when a room tag will render in its place.
    @Test
    fun hitTitle_carriesTagHalvesAndDropsRoomMarkerBesideTag() {
        val solo = ChatSummary(
            id = "!1:s", title = "Auth bug", bot = claude,
            lastActivity = null, unreadCount = 0,
            boxName = "dev-y", sessionShort = "b5", boxShort = "Y",
        )
        val room = ChatSummary(
            id = "!2:s", title = "↔️ mac ↔ dev-z", bot = claude,
            lastActivity = null, unreadCount = 0,
            sessionShort = "ab",
            roomBoxNames = listOf("dev-y", "dev-z"), roomBoxShorts = listOf("Y", "Z"),
        )
        val vm = SearchViewModel(FakeSearchService(), listOf(solo, room))

        val tagged = vm.hitTitle("!1:s")
        assertEquals("Auth bug", tagged.title)
        assertEquals("b5", tagged.sessionShort)
        assertEquals("Y", tagged.boxLetter)
        assertEquals("dev-y", tagged.boxName)

        val roomLine = vm.hitTitle("!2:s")
        assertEquals("marker drops beside a rendered room tag", "mac ↔ dev-z", roomLine.title)
        assertEquals(listOf("Y", "Z"), roomLine.roomBoxShorts)

        val unknown = vm.hitTitle("!gone:s")
        assertEquals("!gone:s", unknown.title)
        assertEquals(null, unknown.boxLetter)
    }

    @Test
    fun emptyState_showsNoResults() = runBlocking {
        val vm = SearchViewModel(FakeSearchService(), emptyList())
        vm.query = "anything"
        vm.search()
        assertEquals("No results.", vm.emptyResultsMessage)
    }

    @Test
    fun search_indexFailure_setsSearchFailed_ratherThanEmptyResults() = runBlocking {
        val fakeSearch = FakeSearchService(queryError = RuntimeException("index corrupt"))
        val vm = SearchViewModel(fakeSearch, emptyList())
        vm.query = "anything"
        vm.search()
        assertEquals(0, vm.messageHits.value.size)
        assertEquals(true, vm.searchFailed.value)
    }

    @Test
    fun search_success_clearsPriorSearchFailed() = runBlocking {
        val fakeSearch = FakeSearchService(queryError = RuntimeException("index corrupt"))
        val vm = SearchViewModel(fakeSearch, emptyList())
        vm.query = "anything"
        vm.search()
        assertEquals(true, vm.searchFailed.value)

        fakeSearch.queryError = null
        vm.search()
        assertEquals(false, vm.searchFailed.value)
    }

    /// apple #172: message hits are grouped one row per chat, ordered by the
    /// newest hit, carrying the count and the newest hit's snippet.
    @Test
    fun search_groupsMessageHitsPerChat() = runBlocking {
        fun hit(id: String, room: String, t: Long) =
            SearchHit(id = id, roomID = room, sender = "@a:s", timestamp = Instant.ofEpochSecond(t), snippet = "<mark>x</mark> $id")
        val fakeSearch = FakeSearchService(listOf(hit("4", "rA", 400), hit("3", "rB", 300), hit("2", "rA", 200), hit("1", "rA", 100)))
        val vm = SearchViewModel(fakeSearch, emptyList())
        vm.query = "x"
        vm.search()
        assertEquals(listOf("rA", "rB"), vm.messageHits.value.map { it.roomID })
        assertEquals(listOf(3, 1), vm.messageHits.value.map { it.count })
        assertEquals("4", vm.messageHits.value[0].newestHit.id)
        assertEquals("x", vm.trimmedQuery)
    }

    // MARK: Search by number

    private fun itemHit(num: Int, title: String? = "Fix the login form") =
        NumberHit(NumberKind.ITEM, num, title, "i$num")

    @Test
    fun aBareNumberQueryExposesTheNumberHit() = runBlocking {
        val asked = mutableListOf<Int>()
        val vm = SearchViewModel(FakeSearchService(), emptyList()) { num -> asked += num; itemHit(num) }
        vm.query = " #412 "
        vm.search()
        assertEquals(itemHit(412), vm.numberHit.value)
        vm.query = "412"
        vm.search()
        assertEquals(itemHit(412), vm.numberHit.value)
        assertEquals(listOf(412, 412), asked)
    }

    @Test
    fun messageSearchStillRunsForANumber() = runBlocking {
        val fakeSearch = FakeSearchService(
            hits = listOf(SearchHit("\$1", "!r:s", "@a:s", Instant.now(), "build <mark>412</mark> passed")),
        )
        val vm = SearchViewModel(fakeSearch, emptyList()) { itemHit(it) }
        vm.query = "412"
        vm.search()
        assertEquals(1, vm.messageHits.value.size)
        assertEquals(itemHit(412), vm.numberHit.value)
    }

    @Test
    fun onlyABareNumberIsLookedUp() = runBlocking {
        val asked = mutableListOf<Int>()
        val vm = SearchViewModel(FakeSearchService(), emptyList()) { num -> asked += num; itemHit(num) }
        for (text in listOf("build 412", "412a", "# 412", "0", "")) {
            vm.query = text
            vm.search()
            assertEquals("\"$text\"", null, vm.numberHit.value)
        }
        assertEquals(emptyList<Int>(), asked)
    }

    @Test
    fun noMatchOrAFailedLookupAddsNothing() = runBlocking {
        var answer: () -> NumberHit? = { null }
        val vm = SearchViewModel(FakeSearchService(), emptyList()) { answer() }
        vm.query = "412"
        vm.search()
        assertEquals(null, vm.numberHit.value)
        answer = { throw IllegalStateException("offline") }
        vm.search()
        assertEquals(null, vm.numberHit.value)
        assertEquals("a failed lookup is not a failed search", false, vm.searchFailed.value)
    }

    @Test
    fun withoutALookupThereIsNoNumberSection() = runBlocking {
        val vm = SearchViewModel(FakeSearchService(), emptyList())
        vm.query = "412"
        vm.search()
        assertEquals(null, vm.numberHit.value)
    }

    @Test
    fun editingAwayFromTheNumberDropsItsHitAtOnce() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fakeSearch = FakeSearchService()
        val vm = SearchViewModel(fakeSearch, emptyList()) { itemHit(it) }
        vm.query = "412"
        vm.search()
        assertEquals(itemHit(412), vm.numberHit.value)
        // The next search parks in the message query; the stale hit must
        // already be gone rather than sit above the new query's results.
        fakeSearch.queryGate = gate
        vm.query = "412 passed"
        val next = launch { vm.search() }
        yield()
        assertEquals(null, vm.numberHit.value)
        gate.complete(Unit)
        next.join()
        assertEquals(null, vm.numberHit.value)
    }

    /// Each keystroke starts its own search: a slow answer for `41` must not
    /// land once the query has become `412`.
    @Test
    fun aStaleLookupNeverOverwritesTheCurrentQuery() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val vm = SearchViewModel(FakeSearchService(), emptyList()) { num ->
            if (num == 41) slow.await()
            itemHit(num, title = "T$num")
        }
        vm.query = "41"
        val first = launch { vm.search() }
        yield()
        vm.query = "412"
        vm.search()
        assertEquals(itemHit(412, title = "T412"), vm.numberHit.value)
        slow.complete(Unit)
        first.join()
        assertEquals("the late #41 answer is dropped", itemHit(412, title = "T412"), vm.numberHit.value)
    }

    @Test
    fun aStaleLookupIsDroppedWhenTheQueryIsNoLongerANumber() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val vm = SearchViewModel(FakeSearchService(), emptyList()) { num -> slow.await(); itemHit(num) }
        vm.query = "412"
        val first = launch { vm.search() }
        yield()
        vm.query = "412 passed"
        slow.complete(Unit)
        first.join()
        assertEquals(null, vm.numberHit.value)
    }

    @Test
    fun trackerHits_followTheQueryTheyWereAskedFor() = runBlocking {
        val asked = mutableListOf<String>()
        val hit = TrackerSearchHit(kind = TrackerSearchKind.ITEM, id = "it_1", num = 12, title = "Pick the nginx timeout")
        val vm = SearchViewModel(FakeSearchService(), emptyList(), trackerSearch = { q -> asked += q; listOf(hit) })
        vm.query = "nginx"
        vm.search()
        assertEquals(listOf("nginx"), asked)
        assertEquals(listOf(hit), vm.trackerHits.value)
        assertEquals("nginx", vm.trackerHitsQuery)

        vm.query = ""
        vm.search()
        assertEquals(emptyList<TrackerSearchHit>(), vm.trackerHits.value)
    }

    @Test
    fun trackerHits_aFailureSaysSoAndKeepsTheRest() = runBlocking {
        val vm = SearchViewModel(FakeSearchService(), emptyList(), trackerSearch = { error("offline") })
        vm.query = "nginx"
        vm.search()
        assertEquals(emptyList<TrackerSearchHit>(), vm.trackerHits.value)
        assertEquals(true, vm.trackerFailed.value)
    }

    @Test
    fun trackerHits_aQueryChangedDuringThePauseIsNotAsked() = runBlocking {
        val asked = mutableListOf<String>()
        val vm = SearchViewModel(FakeSearchService(), emptyList(), trackerSearch = { q -> asked += q; emptyList() })
        vm.query = "ngi"
        val first = launch { vm.search() }
        yield()
        vm.query = "nginx"
        vm.search()
        first.join()
        assertEquals(listOf("nginx"), asked)
    }
}
