package chat.matron.android

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.ConvoSummaryDTO
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.UserSession
import chat.matron.android.search.SearchDatabase
import chat.matron.android.storage.InMemorySessionStore
import chat.matron.android.storage.StoragePaths
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sign-in separation and remote sign-out in the composition root: each
 * sign-in (user, journal, device) has its own stack and database file, a
 * signed-out sign-in only ever gets an inert stack, and a revoke reaches the
 * host once, for the sign-in it was found on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppDependenciesSignInTest {
    private val context: android.content.Context get() = ApplicationProvider.getApplicationContext()
    private val journalDir: File get() = File(StoragePaths.appSupport(context), "journal-store")

    /** The files the stacks were asked to open, in order. */
    private val opened = mutableListOf<File>()
    private val sessionStore = InMemorySessionStore()

    private fun deps() = AppDependencies(
        context = context,
        sessionStoreFactory = { sessionStore },
        journalDatabaseFactory = { c, f -> opened += f; MatronDatabase.inMemory(c) },
        searchDatabaseFactory = { c, _ -> SearchDatabase.inMemory(c) },
    )

    private fun alice(device: String = "1", token: String = "t$device") = UserSession(
        userID = "alice", deviceID = device, homeserverURL = "https://journal.example.com/", accessToken = token,
    )

    @Before
    fun setUp() {
        journalDir.deleteRecursively()
        journalDir.mkdirs()
    }

    @After
    fun tearDown() {
        journalDir.deleteRecursively()
    }

    @Test
    fun aRefusedHello_reachesTheHostOnce() {
        val deps = deps()
        val revoked = mutableListOf<UserSession>()
        deps.onCredentialsRevoked = { revoked += it }
        val session = alice()
        deps.chatService(session)

        deps.simulateConnectionAuthRejected(session)
        deps.simulateConnectionAuthRejected(session)
        repeat(5) { deps.simulateRESTResponse(401, session); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5)) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(session), revoked)
    }

    @Test
    fun rest401sOverTheMinimumSpan_reachTheHost() {
        val deps = deps()
        val revoked = mutableListOf<UserSession>()
        deps.onCredentialsRevoked = { revoked += it }
        val session = alice()
        deps.chatService(session)

        deps.simulateRESTResponse(401, session)
        deps.simulateRESTResponse(401, session)
        deps.simulateRESTResponse(401, session)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("three 401s at once are not a revoke", revoked.isEmpty())

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        deps.simulateRESTResponse(401, session)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(session), revoked)
    }

    @Test
    fun aRevokeWithNoHost_signsOutAtOnce_andTellsTheHostLater() = runBlocking {
        val deps = deps()
        val session = alice()
        deps.auth.persist(session)
        deps.chatService(session)

        deps.simulateConnectionAuthRejected(session)
        shadowOf(Looper.getMainLooper()).idle()
        deps.awaitPendingTeardown()

        assertFalse("signed out without waiting for a host", deps.hasLiveCore(session))
        assertNull("the saved session is gone", deps.auth.restoreSession())
        val revoked = mutableListOf<UserSession>()
        deps.onCredentialsRevoked = { revoked += it }
        assertEquals("the host still hears why", listOf(session), revoked)
        deps.onCredentialsRevoked = { revoked += it }
        assertEquals("only once", 1, revoked.size)
    }

    @Test
    fun aRevokeForAStackAlreadyTornDown_isDropped() = runBlocking {
        val deps = deps()
        val revoked = mutableListOf<UserSession>()
        deps.onCredentialsRevoked = { revoked += it }
        val session = alice()
        deps.chatService(session)
        deps.signOut()
        deps.awaitPendingTeardown()

        deps.credentialsRevoked(session)
        assertTrue(revoked.isEmpty())
    }

    /**
     * The real order of a sign-out and a link again by QR: sign out, a late
     * call with the old session, the fresh-login wipe, the new sign-in, then
     * the old token refused on the socket and over REST. The new sign-in is
     * not signed out, its database works, and its own revoke is still heard.
     */
    @Test
    fun aLateCallForTheOldSignIn_neitherSignsOutNorBreaksTheNewOne() = runBlocking {
        val deps = deps()
        val revoked = mutableListOf<UserSession>()
        deps.onCredentialsRevoked = { revoked += it }
        val old = alice(device = "1")
        deps.chatService(old)
        assertEquals(listOf("alice.d1.sqlite"), opened.map { it.name })

        deps.signOut()
        deps.awaitPendingTeardown()

        // A screen that outlived the session asks for its stack again.
        assertNotNull(deps.chatService(old))
        assertFalse("never a live stack for a signed-out sign-in", deps.hasLiveCore(old))
        assertEquals("the inert stack opens no file", 1, opened.size)

        deps.wipeLocalDataForFreshLogin()
        val next = alice(device = "2")
        deps.chatService(next)
        assertTrue(deps.hasLiveCore(next))
        assertEquals("alice.d2.sqlite", opened.last().name)
        assertNotSame(deps.journalStore(old), deps.journalStore(next))

        deps.simulateConnectionAuthRejected(old)
        repeat(6) {
            deps.simulateRESTResponse(401, old)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the old sign-in's refusal must not sign the new one out", revoked.isEmpty())
        assertTrue(deps.hasLiveCore(next))

        val store = deps.journalStore(next)
        store.applyColdSnapshot(listOf(ConvoSummaryDTO("c1", "", "running", 0, "", 0)), headSeq = 0)
        assertEquals(listOf("c1"), store.allConversationIDs())

        deps.simulateConnectionAuthRejected(next)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(next), revoked)
    }

    @Test
    fun eachSignIn_getsItsOwnStackAndMediaService() {
        val deps = deps()
        assertNotSame(deps.journalStore(alice("1")), deps.journalStore(alice("2")))
        assertNotSame(deps.mediaService(alice("1")), deps.mediaService(alice("2")))
        assertNotSame(
            deps.journalStore(alice("1")),
            deps.journalStore(alice("1").copy(homeserverURL = "https://other.example.com/")),
        )
    }

    @Test
    fun aNewToken_forTheSameSignIn_keepsItsStack() {
        val deps = deps()
        val store = deps.journalStore(alice("1", token = "a"))
        assertTrue(store === deps.journalStore(alice("1", token = "b")))
        assertEquals(1, opened.size)
    }

    @Test
    fun aRestoredSession_adoptsTheOldStoreFile() {
        File(journalDir, "alice.sqlite").writeText("main")
        File(journalDir, "alice.sqlite-wal").writeText("wal")
        val deps = deps()

        deps.chatService(alice("4"))

        assertEquals(File(journalDir, "alice.d4.sqlite"), opened.single())
        assertEquals("main", File(journalDir, "alice.d4.sqlite").readText())
        assertEquals("wal", File(journalDir, "alice.d4.sqlite-wal").readText())
        assertFalse(File(journalDir, "alice.sqlite").exists())
        assertFalse(File(journalDir, "alice.sqlite-wal").exists())
    }

    @Test
    fun theFreshLoginWipe_keepsTheFilesALiveStackHolds() = runBlocking {
        val deps = deps()
        val live = alice("5")
        deps.chatService(live)
        val held = File(journalDir, "alice.d5.sqlite").apply { writeText("main") }
        val heldWal = File(journalDir, "alice.d5.sqlite-wal").apply { writeText("wal") }
        val stale = File(journalDir, "alice.d4.sqlite").apply { writeText("stale") }
        val staleWal = File(journalDir, "alice.d4.sqlite-wal").apply { writeText("stale") }
        val legacy = File(journalDir, "bob.sqlite").apply { writeText("stale") }

        deps.wipeLocalDataForFreshLogin()

        assertTrue(held.exists())
        assertTrue(heldWal.exists())
        assertFalse(stale.exists())
        assertFalse(staleWal.exists())
        assertFalse(legacy.exists())
    }

    @Test
    fun isHeldStoreFile_matchesTheDatabaseAndItsSideFilesOnly() {
        val held = setOf("alice.d5.sqlite")
        assertTrue(AppDependencies.isHeldStoreFile("alice.d5.sqlite", held))
        assertTrue(AppDependencies.isHeldStoreFile("alice.d5.sqlite-wal", held))
        assertTrue(AppDependencies.isHeldStoreFile("alice.d5.sqlite-shm", held))
        assertTrue(AppDependencies.isHeldStoreFile("alice.d5.sqlite-journal", held))
        assertFalse(AppDependencies.isHeldStoreFile("alice.d55.sqlite", held))
        assertFalse(AppDependencies.isHeldStoreFile("alice.d5.sqlite-old", held))
        assertFalse(AppDependencies.isHeldStoreFile("alice.sqlite", held))
    }
}
