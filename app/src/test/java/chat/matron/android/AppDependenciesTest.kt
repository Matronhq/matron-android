package chat.matron.android

import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.UserSession
import chat.matron.android.search.SearchDatabase
import chat.matron.android.storage.InMemorySessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke coverage for the composition root. Ports MatronTests/AppDependenciesTests
 * .swift (media identity + timeline LRU) and adds graph-construction / sign-out
 * teardown assertions. Built with an in-memory session store + in-memory Room so
 * the whole graph stands up hermetically under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppDependenciesTest {

    private fun deps(): AppDependencies = AppDependencies(
        context = ApplicationProvider.getApplicationContext(),
        sessionStoreFactory = { InMemorySessionStore() },
        journalDatabaseFactory = { c, _ -> MatronDatabase.inMemory(c) },
        searchDatabaseFactory = { c, _ -> SearchDatabase.inMemory(c) },
    )

    private fun session(userID: String = "@a:s") = UserSession(
        userID = userID,
        deviceID = "D",
        homeserverURL = "https://s.example",
        accessToken = "t",
    )

    @Test
    fun graphConstructs_andWiresEveryServiceFactory() {
        val deps = deps()
        val session = session()

        assertNotNull("auth must be wired", deps.auth)
        assertNotNull("search index must open in-memory", deps.search)
        assertNotNull(deps.syncService(session))
        assertNotNull(deps.chatService(session))
        assertNotNull(deps.mediaService(session))
        assertNotNull(deps.pushService(session))
        assertNotNull(deps.devicesService(session))
        assertNotNull(deps.agentRPCService(session))
        assertNotNull(deps.timelineService(session, "!room:s"))
    }

    @Test
    fun mediaService_isCached_perSession() {
        val deps = deps()
        val session = session()
        assertSame(
            "mediaService must return the same instance for the same session",
            deps.mediaService(session),
            deps.mediaService(session),
        )
    }

    @Test
    fun mediaService_isDistinct_perUser() {
        val deps = deps()
        assertNotSame(
            "different sessions must get different media services",
            deps.mediaService(session("@a:s")),
            deps.mediaService(session("@b:s")),
        )
    }

    @Test
    fun timelineCache_evictsOldestEntry_whenLimitExceeded() {
        val deps = deps()
        val session = session()
        val limit = AppDependencies.timelineCacheLimit
        assertEquals(16, limit)

        for (i in 0 until limit) deps.timelineService(session, "!room$i:s")
        assertEquals("cache must reach exactly the limit before evicting", limit, deps.timelineCacheCount)
        assertTrue(deps.timelineCacheContains(session.userID, "!room0:s"))

        deps.timelineService(session, "!room$limit:s")

        assertEquals("cache must stay bounded after over-fill", limit, deps.timelineCacheCount)
        assertFalse("LRU entry must be evicted", deps.timelineCacheContains(session.userID, "!room0:s"))
        assertTrue("newly-inserted entry must remain", deps.timelineCacheContains(session.userID, "!room$limit:s"))
    }

    @Test
    fun timelineCache_reaccessDoesNotPromote_evictionIsFIFO() {
        val deps = deps()
        val session = session()
        val limit = AppDependencies.timelineCacheLimit

        for (i in 0 until limit) deps.timelineService(session, "!room$i:s")
        deps.timelineService(session, "!room0:s") // re-fetch: non-promoting read
        deps.timelineService(session, "!room$limit:s") // trigger eviction

        assertFalse("FIFO eviction: oldest insert goes first regardless of re-access",
            deps.timelineCacheContains(session.userID, "!room0:s"))
        assertTrue(deps.timelineCacheContains(session.userID, "!room1:s"))
    }

    @Test
    fun signOut_tearsDown_andClearsCaches() = runBlocking {
        val deps = deps()
        val session = session()

        // Build a core + a timeline entry.
        deps.chatService(session)
        deps.timelineService(session, "!room:s")
        assertEquals(1, deps.timelineCacheCount)

        deps.signOut()
        deps.awaitPendingTeardown()

        assertEquals("timeline cache must be cleared on sign-out", 0, deps.timelineCacheCount)
        // A fresh core builds cleanly against a new in-memory database.
        assertNotNull(deps.timelineService(session, "!room:s"))
        Unit
    }
}
