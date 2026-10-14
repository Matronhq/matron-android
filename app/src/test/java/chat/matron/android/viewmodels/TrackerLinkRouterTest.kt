package chat.matron.android.viewmodels

import chat.matron.android.journal.JournalApiError
import chat.matron.android.journal.MissionsRefreshOutcome
import chat.matron.android.journal.NumberKind
import chat.matron.android.journal.NumberLookup
import chat.matron.android.models.ItemKind
import chat.matron.android.models.TrackerItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Where a shareable tracker link opens. The user's own numbers resolve
/// exactly like "Go to number"; what the app has no page for goes to the
/// browser, and a number that will not open explains itself in place.
class TrackerLinkRouterTest {
    private val base = "https://journal.example.com"

    private class World {
        val localItems = mutableMapOf<Int, TrackerItem>()
        var lookupAnswer: NumberLookup? = null
        var lookupError: Throwable = JournalApiError.NotFound
        var itemResolution: TrackerItemLinkResolver.Resolution = TrackerItemLinkResolver.Resolution.NotSynced
        val lookups = mutableListOf<Int>()
        val resolver = GoToNumberResolver(
            localItem = { localItems[it] },
            localMission = { null },
            lookup = { num -> lookups += num; lookupAnswer ?: throw lookupError },
            resolveItem = { itemResolution },
            missionCached = { true },
            refreshMission = { MissionsRefreshOutcome.Succeeded },
            missionsSupported = { true },
        )
    }

    private fun router(world: World) = TrackerLinkRouter(base, sessionUser = "alice", resolver = world.resolver)

    @Test
    fun anOwnItemOpensInApp() = runTest {
        val world = World()
        world.localItems[412] = TrackerItem(id = "it_412", num = 412, kind = ItemKind.TASK, title = "Fix it", originConvoID = "c1")
        world.itemResolution = TrackerItemLinkResolver.Resolution.Open("it_412")
        assertEquals(
            TrackerLinkRouter.Route.Open(GoToTarget.Item("it_412")),
            router(world).route("https://journal.example.com/u/alice/412"),
        )
        assertTrue("a local hit asks the journal nothing", world.lookups.isEmpty())
    }

    @Test
    fun anOwnMissionAndAMilestoneOpenTheMissionPage() = runTest {
        val world = World()
        world.lookupAnswer = NumberLookup(NumberKind.MISSION, id = "ms_9", num = 9)
        assertEquals(
            TrackerLinkRouter.Route.Open(GoToTarget.Mission("ms_9")),
            router(world).route("https://journal.example.com/u/alice/9"),
        )
        world.lookupAnswer = NumberLookup(NumberKind.MILESTONE, id = "ml_10", num = 10, missionID = "ms_9", missionNum = 9)
        assertEquals(
            TrackerLinkRouter.Route.Open(GoToTarget.Mission("ms_9")),
            router(world).route("https://journal.example.com/u/alice/10"),
        )
        assertEquals(listOf(9, 10), world.lookups)
    }

    @Test
    fun aProjectGoesToTheBrowser() = runTest {
        val world = World()
        world.lookupAnswer = NumberLookup(NumberKind.PROJECT, id = "pr_3", num = 3)
        val url = "https://journal.example.com/u/alice/3"
        assertEquals(TrackerLinkRouter.Route.Browser(url), router(world).route(url))
    }

    @Test
    fun anotherPersonsLinkGoesToTheBrowserWithoutALookup() = runTest {
        val world = World()
        val url = "https://journal.example.com/u/bob/4"
        assertEquals(TrackerLinkRouter.Route.Browser(url), router(world).route(url))
        assertTrue(world.lookups.isEmpty())
    }

    @Test
    fun aLinkToAnotherJournalGoesToTheBrowser() = runTest {
        val world = World()
        val url = "https://other.example.com/u/alice/4"
        assertEquals(null, router(world).link(url))
        assertEquals(TrackerLinkRouter.Route.Browser(url), router(world).route(url))
        assertTrue(world.lookups.isEmpty())
    }

    @Test
    fun aNumberThatWillNotOpenExplainsItself() = runTest {
        val world = World()
        assertEquals(
            TrackerLinkRouter.Route.Explain(GoToNumberCopy.nothingNumbered(5)),
            router(world).route("https://journal.example.com/u/alice/5"),
        )
        world.lookupError = JournalApiError.Transport("offline")
        assertEquals(
            TrackerLinkRouter.Route.Explain(GoToNumberCopy.lookupFailed(5)),
            router(world).route("https://journal.example.com/u/alice/5"),
        )
        world.lookupAnswer = NumberLookup(NumberKind.ITEM, id = "it_6", num = 6)
        world.itemResolution = TrackerItemLinkResolver.Resolution.NotSynced
        assertEquals(
            TrackerLinkRouter.Route.Explain("Item #6 isn't on this device yet."),
            router(world).route("https://journal.example.com/u/alice/6"),
        )
    }
}
