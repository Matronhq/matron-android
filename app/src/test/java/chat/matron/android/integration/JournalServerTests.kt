package chat.matron.android.integration

import androidx.test.core.app.ApplicationProvider
import chat.matron.android.auth.JournalAuthService
import chat.matron.android.journal.ClientOp
import chat.matron.android.journal.JournalApi
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import chat.matron.android.journal.JournalStore
import chat.matron.android.journal.JournalSyncEngine
import chat.matron.android.journal.OkHttpWebSocketConnector
import chat.matron.android.journal.ServerFrame
import chat.matron.android.journal.WebSocketConnecting
import chat.matron.android.journal.WebSocketConnection
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.journal.stringOrNull
import chat.matron.android.storage.InMemorySessionStore
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Integration tests driving the real matron-journal server (see
/// [JournalServerHarness]). Precondition: `cd ~/Dev/matron-journal && npm
/// install` once. Each test boots its own harness (fresh temp SQLite DB, fresh
/// free port) so tests never share server-side state. The client store is an
/// in-memory Room DB (Robolectric supplies the `Context`); the sockets are real
/// OkHttp connections to `127.0.0.1` (Robolectric allows real networking).
///
/// [JournalServerHarness.start] throws (JUnit `Assume`-style) when the server
/// checkout / node / node_modules are missing — everything else (a real startup
/// failure) is a hard error, not a skip: on a properly set-up machine these
/// tests must actually run.
///
/// Dedicated invocation:
///   ./gradlew :app:testDebugUnitTest --tests 'chat.matron.android.integration.*'
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalServerTests {
    private val ownSender = "user:dan"

    private fun makeStore(): JournalStore {
        val db = MatronDatabase.inMemory(ApplicationProvider.getApplicationContext())
        return JournalStore(db, ownSender = ownSender)
    }

    // MARK: Test 1 — sign-in, snapshot, live round-trip

    @Test
    fun testSignInSnapshotLiveRoundTrip() = runBlocking {
        val harness = JournalServerHarness.start(
            users = listOf(JournalServerHarness.UserSpec("dan", "pw")),
            agents = listOf(JournalServerHarness.AgentSpec("dan", "dev-2")),
        )
        try {
            val agentToken = harness.agentTokens["dev-2"]
            assertNotNull("agent token must be provisioned", agentToken)

            val auth = JournalAuthService(sessionStore = InMemorySessionStore())
            val session = auth.loginPassword(
                homeserverURL = harness.baseURL, username = "dan", password = "pw",
                initialDeviceDisplayName = "integration-test-client",
            )

            val store = makeStore()
            val engine = JournalSyncEngine(
                api = JournalApi(harness.baseURL, token = session.accessToken),
                store = store, connector = OkHttpWebSocketConnector(),
                token = session.accessToken, ownSender = ownSender, search = null,
                backoffBaseSeconds = 0.25,
            )
            engine.beginSync()
            engine.waitUntilReady()

            val agent = FakeAgent.connect(harness.baseURL, agentToken!!)
            try {
                agent.convoUpsert(id = "sess-1", title = "Session 1", sessionState = "running")
                for (i in 1..3) {
                    agent.publish("sess-1", "text", buildJsonObject { put("body", "hello $i") })
                }

                waitUntil(5_000, "3 published texts to converge into the store") {
                    store.events("sess-1").count { it.type == JournalEventType.TEXT } >= 3
                }
                val texts = store.events("sess-1")
                    .filter { it.type == JournalEventType.TEXT }
                    .sortedBy { it.seq }
                assertEquals(3, texts.size)
                assertEquals(
                    listOf("hello 1", "hello 2", "hello 3"),
                    texts.map { it.payload.stringOrNull("body") },
                )
                assertTrue(texts.all { it.sender == "agent:dev-2" })

                // Client -> server -> agent: the engine's own send must reach the
                // agent's live socket as a journal frame (same fan-out the server
                // uses for every device of the user).
                engine.sendOp(ClientOp.Send(convoID = "sess-1", body = "from client", localID = "local-1"))
                val received = agent.waitForFrame(5_000) { frame ->
                    frame.stringOrNull("kind") == "journal" && frame.frameBody() == "from client"
                }
                assertEquals(ownSender, received.stringOrNull("sender"))
                assertEquals(JournalEventType.TEXT, received.stringOrNull("type"))
            } finally {
                agent.close()
                engine.endSync()
            }
        } finally {
            harness.stop()
        }
        Unit
    }

    // MARK: Test 2 — cursor resume across an engine restart

    @Test
    fun testResumeAfterEngineRestart() = runBlocking {
        val harness = JournalServerHarness.start(
            users = listOf(JournalServerHarness.UserSpec("dan", "pw")),
            agents = listOf(JournalServerHarness.AgentSpec("dan", "dev-2")),
        )
        try {
            val agentToken = harness.agentTokens["dev-2"]
            assertNotNull("agent token must be provisioned", agentToken)

            val auth = JournalAuthService(sessionStore = InMemorySessionStore())
            val session = auth.loginPassword(
                homeserverURL = harness.baseURL, username = "dan", password = "pw",
                initialDeviceDisplayName = "integration-test-client",
            )

            val store = makeStore()
            val api = JournalApi(harness.baseURL, token = session.accessToken)

            val agent = FakeAgent.connect(harness.baseURL, agentToken!!)
            try {
                agent.convoUpsert(id = "sess-2", title = "Session 2", sessionState = "running")

                val engine1 = JournalSyncEngine(
                    api = api, store = store, connector = OkHttpWebSocketConnector(),
                    token = session.accessToken, ownSender = ownSender, search = null,
                    backoffBaseSeconds = 0.25,
                )
                engine1.beginSync()
                engine1.waitUntilReady()

                for (i in 1..5) {
                    agent.publish("sess-2", "text", buildJsonObject { put("body", "first-$i") })
                }
                waitUntil(5_000, "first 5 texts to converge") {
                    store.events("sess-2").count { it.type == JournalEventType.TEXT } >= 5
                }
                engine1.endSync()

                for (i in 1..5) {
                    agent.publish("sess-2", "text", buildJsonObject { put("body", "second-$i") })
                }
                // Engine 1 is stopped — these 5 must NOT have reached the store yet.
                delay(300)
                assertEquals(
                    "events published while the engine was stopped must not appear until resume",
                    5, store.events("sess-2").count { it.type == JournalEventType.TEXT },
                )

                // A brand-new engine on the SAME store/DB must resume from the
                // persisted cursor across this process-lifecycle boundary.
                val engine2 = JournalSyncEngine(
                    api = api, store = store, connector = OkHttpWebSocketConnector(),
                    token = session.accessToken, ownSender = ownSender, search = null,
                    backoffBaseSeconds = 0.25,
                )
                engine2.beginSync()
                engine2.waitUntilReady()
                try {
                    waitUntil(5_000, "all 10 texts to converge after resume") {
                        store.events("sess-2").count { it.type == JournalEventType.TEXT } >= 10
                    }
                    val texts = store.events("sess-2")
                        .filter { it.type == JournalEventType.TEXT }
                        .sortedBy { it.seq }
                    assertEquals(
                        "resume must be gap-free and exactly-once, in seq order",
                        (1..5).map { "first-$it" } + (1..5).map { "second-$it" },
                        texts.map { it.payload.stringOrNull("body") },
                    )
                } finally {
                    engine2.endSync()
                }
            } finally {
                agent.close()
            }
        } finally {
            harness.stop()
        }
        Unit
    }

    // MARK: Test 3 — chaos resume against the real server

    /// Client-side headline test mirroring the server's own chaos suite: 200
    /// events published with 1ms spacing while [RealChaosConnector] (a
    /// [WebSocketConnecting] decorator over the real [OkHttpWebSocketConnector])
    /// force-closes the socket after a random 10-40 *journal* frames on every
    /// connect, driving repeated reconnects. The store must still converge to an
    /// exact, gap-free, duplicate-free copy of the 200 published texts.
    ///
    /// The convo also carries a `session_status` event (from `convo_upsert`'s
    /// `session_state`), so raw seq values aren't contiguous 1..200 for this
    /// convo's full event list — the assertion is scoped to the published `text`
    /// events specifically.
    @Test
    fun testChaosResumeAgainstRealServer() = runBlocking {
        val harness = JournalServerHarness.start(
            users = listOf(JournalServerHarness.UserSpec("dan", "pw")),
            agents = listOf(JournalServerHarness.AgentSpec("dan", "dev-2")),
        )
        try {
            val agentToken = harness.agentTokens["dev-2"]
            assertNotNull("agent token must be provisioned", agentToken)

            val auth = JournalAuthService(sessionStore = InMemorySessionStore())
            val session = auth.loginPassword(
                homeserverURL = harness.baseURL, username = "dan", password = "pw",
                initialDeviceDisplayName = "integration-test-client",
            )

            val store = makeStore()
            val api = JournalApi(harness.baseURL, token = session.accessToken)
            val connector = RealChaosConnector()
            val engine = JournalSyncEngine(
                api = api, store = store, connector = connector,
                token = session.accessToken, ownSender = ownSender, search = null,
                backoffBaseSeconds = 0.05,
            )
            engine.beginSync()
            try {
                val agent = FakeAgent.connect(harness.baseURL, agentToken!!)
                try {
                    agent.convoUpsert(id = "sess-chaos", title = "Chaos", sessionState = "running")

                    val total = 200
                    for (i in 1..total) {
                        agent.publish("sess-chaos", "text", buildJsonObject { put("body", "chaos-$i") })
                        delay(1)
                    }

                    waitUntil(30_000, "all 200 published texts to converge") {
                        store.events("sess-chaos").count { it.type == JournalEventType.TEXT } >= total
                    }
                    val texts = store.events("sess-chaos")
                        .filter { it.type == JournalEventType.TEXT }
                        .sortedBy { it.seq }
                    val bodies = texts.map { it.payload.stringOrNull("body") }
                    assertEquals("no dupes, no drops", total, bodies.size)
                    assertEquals(
                        "gap-free, exactly-once, in seq order",
                        (1..total).map { "chaos-$it" }, bodies,
                    )
                    assertEquals("no duplicate bodies", total, bodies.filterNotNull().toSet().size)
                    assertTrue(
                        "chaos must actually force at least one reconnect",
                        connector.connectCount > 1,
                    )
                } finally {
                    agent.close()
                }
            } finally {
                engine.endSync()
            }
        } finally {
            harness.stop()
        }
        Unit
    }

    // MARK: Helpers

    private suspend fun waitUntil(timeoutMs: Long, description: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(50)
        }
        fail("condition not met within ${timeoutMs}ms: $description")
    }
}

/// Wraps the real [OkHttpWebSocketConnector] and force-closes the underlying
/// socket after a random 10-40 *journal* frames on every connect, forcing the
/// sync engine's normal reconnect/resume path against a genuinely flaky
/// transport — the client-side mirror of the fake-socket `ChaosServerConnector`
/// unit test, but here against the real server over a real socket. Faithful
/// port of the matron-apple `ChaosConnector`/`ChaosConnection`.
class RealChaosConnector : WebSocketConnecting {
    private val inner = OkHttpWebSocketConnector()
    private val lock = Any()
    var connectCount = 0
        private set

    override suspend fun connect(url: String): WebSocketConnection {
        synchronized(lock) { connectCount += 1 }
        return RealChaosConnection(inner.connect(url))
    }
}

private class RealChaosConnection(private val inner: WebSocketConnection) : WebSocketConnection {
    // Int.random(in: 10...40) → 10..40 inclusive.
    private val cutAfter = Random.nextInt(10, 41)
    private val lock = Any()
    private var journalFramesSeen = 0

    override suspend fun sendText(text: String) = inner.sendText(text)

    override suspend fun receiveText(): String {
        val text = inner.receiveText()
        // Only journal frames count toward the cut — control/ephemeral frames
        // (hello_ok, etc.) shouldn't shorten the "real" replay window.
        if (ServerFrame.decode(text) is ServerFrame.Journal) {
            val shouldClose = synchronized(lock) {
                journalFramesSeen += 1
                journalFramesSeen >= cutAfter
            }
            if (shouldClose) inner.close()
        }
        return text
    }

    override suspend fun ping() = inner.ping()

    override fun close() = inner.close()
}
