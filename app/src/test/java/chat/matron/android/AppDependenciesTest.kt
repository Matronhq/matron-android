package chat.matron.android

import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.UserSession
import chat.matron.android.search.SearchDatabase
import chat.matron.android.storage.InMemorySessionStore
import chat.matron.android.storage.StoragePaths
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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

    /**
     * Bugbot "Teardown await drops newer job": a sign-out that chains a new
     * teardown while `awaitPendingTeardown()` is already suspended must still be
     * covered by that await — the caller publishes the next session the moment
     * await returns, so returning early would let a new core race the old wipe.
     *
     * The injected single-threaded test dispatcher makes the interleaving
     * deterministic: teardown work only progresses when the test pumps the
     * scheduler, so once the awaiter has returned, the chained teardown is
     * provably frozen wherever it stopped.
     */
    @Test
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun awaitPendingTeardown_coversTeardownChainedDuringTheWait() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        val openedDbs = mutableListOf<MatronDatabase>()
        val deps = AppDependencies(
            context = ApplicationProvider.getApplicationContext(),
            sessionStoreFactory = { InMemorySessionStore() },
            journalDatabaseFactory = { c, _ -> MatronDatabase.inMemory(c).also { openedDbs += it } },
            searchDatabaseFactory = { c, _ -> SearchDatabase.inMemory(c) },
            appScope = CoroutineScope(SupervisorJob() + dispatcher),
        )
        val session = session()
        runBlocking {
            deps.chatService(session) // core 1
            deps.signOut() // teardown A — queued on the paused dispatcher
            var awaitReturned = false
            val awaiter = launch(dispatcher) {
                deps.awaitPendingTeardown()
                awaitReturned = true
            }
            scheduler.runCurrent() // awaiter is now suspended joining teardown A

            deps.chatService(session) // core 2, built while the await is in flight
            deps.signOut() // teardown B chains behind A

            // Pump until the awaiter resumes. advanceTimeBy fires the virtual
            // unregisterPush timeouts; the real sleeps let OkHttp/Room callbacks
            // land between pumps.
            val deadline = System.currentTimeMillis() + 30_000
            while (!awaitReturned && System.currentTimeMillis() < deadline) {
                scheduler.advanceTimeBy(6_000)
                scheduler.runCurrent()
                Thread.sleep(2)
            }
            assertTrue("awaitPendingTeardown never returned", awaitReturned)
            assertEquals(2, openedDbs.size)
            assertFalse(
                "awaitPendingTeardown must cover a teardown chained while it was waiting: " +
                    "the second core's DB should already be closed when it returns",
                openedDbs[1].isOpen,
            )
            awaiter.join()
        }
    }

    /**
     * Bugbot "Sign-out leaves local mirror": if the process dies before the
     * background teardown finishes, the mirror file (and search index) survive
     * on disk. A fresh interactive login starts from a server snapshot anyway,
     * so it wipes any leftovers before the first core opens.
     */
    @Test
    fun wipeLocalDataForFreshLogin_removesMirrorFilesAndSearchIndex() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val deps = deps()

        // Simulate a crashed sign-out teardown: a stale per-user mirror on disk
        // and a still-populated search index.
        val journalDir = File(StoragePaths.appSupport(context), "journal-store").apply { mkdirs() }
        val stale = File(journalDir, "stale-user.sqlite").apply { writeText("stale") }
        deps.search!!.index(
            roomID = "!room:s",
            eventID = "42",
            sender = "user:@old:s",
            timestamp = Instant.now(),
            body = "previous user's needle",
        )

        deps.wipeLocalDataForFreshLogin()

        assertFalse("stale mirror file must be deleted on fresh login", stale.exists())
        assertTrue(
            "search index must be wiped on fresh login",
            deps.search!!.query("needle", limit = 10).isEmpty(),
        )
    }
}
