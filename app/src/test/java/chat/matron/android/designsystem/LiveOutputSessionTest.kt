package chat.matron.android.designsystem

import chat.matron.android.events.LiveOutputEvent
import chat.matron.android.events.LiveOutputFrame
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// Plain-logic tests for the live-output session's state machine — streaming,
/// the reconnect replay-dedupe, expiry gating, rolling-tail trim, and the
/// store's LRU reuse/eviction. Ported from the Swift `LiveOutputSessionTests`
/// (in `LiveOutputLogicTests`); `AsyncThrowingStream` connectors become `Flow`
/// factories and `waitUntil` polling becomes `advanceUntilIdle` on virtual time.
@OptIn(ExperimentalCoroutinesApi::class)
class LiveOutputSessionTest {
    private fun event(expiresAt: Instant? = null): LiveOutputEvent = LiveOutputEvent(
        toolUseID = "toolu_1",
        command = "npm test",
        viewerURL = "https://viewer.example.com/live?token=t",
        expiresAt = expiresAt,
    )

    @Test
    fun streamsAndCompletes() = runTest {
        val session = LiveOutputSession(event()) {
            flow {
                emit(LiveOutputFrame.Data("line one\n"))
                emit(LiveOutputFrame.Data("line two\n"))
                emit(LiveOutputFrame.Complete(exitCode = 0, denied = false, truncated = false))
            }
        }
        session.startIfNeeded(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertEquals(LiveOutputSession.Phase.Complete(0, false, false), session.phase)
        assertEquals("line one\nline two\n", session.output.text)
        assertTrue(session.hasOutput)
    }

    @Test
    fun reconnectReplayIsDeduped() = runTest {
        // First connection delivers 2 chunks then dies; the retry replays from
        // offset 0 (viewer semantics) plus new output. Rendered text must not
        // duplicate the replayed prefix.
        var connections = 0
        val session = LiveOutputSession(event()) {
            connections++
            if (connections == 1) {
                flow {
                    emit(LiveOutputFrame.Data("AAAA"))
                    emit(LiveOutputFrame.Data("BBBB"))
                    throw java.io.IOException("network connection lost")
                }
            } else {
                flow {
                    emit(LiveOutputFrame.Data("AAAABBBB"))
                    emit(LiveOutputFrame.Data("CCCC"))
                    emit(LiveOutputFrame.Complete(exitCode = 0, denied = false, truncated = false))
                }
            }
        }
        session.startIfNeeded(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertEquals("AAAABBBBCCCC", session.output.text)
    }

    @Test
    fun expiredEventNeverConnects() = runTest {
        var connected = false
        val session = LiveOutputSession(event(expiresAt = Instant.now().minusSeconds(10))) {
            connected = true
            flow { }
        }
        session.startIfNeeded(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertEquals(LiveOutputSession.Phase.Expired, session.phase)
        assertFalse("must not connect for an expired token", connected)
    }

    @Test
    fun deniedCompletion() = runTest {
        val session = LiveOutputSession(event()) {
            flow {
                emit(LiveOutputFrame.Complete(exitCode = null, denied = true, truncated = false))
            }
        }
        session.startIfNeeded(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertEquals(LiveOutputSession.Phase.Complete(null, true, false), session.phase)
        assertFalse(session.hasOutput)
    }

    @Test
    fun hugeOutputIsTrimmedToRollingTail() = runTest {
        // The tee allows logs up to 50MB; the pane keeps a bounded rolling tail
        // so a giant replay can't balloon memory or hang rendering.
        val big = "x".repeat(150_000) + "y".repeat(100_000)
        val session = LiveOutputSession(event()) {
            flow {
                emit(LiveOutputFrame.Data(big))
                emit(LiveOutputFrame.Complete(exitCode = 0, denied = false, truncated = true))
            }
        }
        session.startIfNeeded(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        val text = session.output.text
        assertTrue(text.length <= 200_000)
        assertTrue("trim must drop the HEAD, keeping the newest output", text.endsWith("y"))
    }

    @Test
    fun storeReusesAndEvicts() {
        val store = LiveOutputSessionStore(limit = 2)
        val a = store.session(event())
        val aAgain = store.session(event())
        assertTrue("same tool_use_id must reuse the session", a === aAgain)

        val b = LiveOutputEvent("b", "ls", a.event.viewerURL, null)
        val c = LiveOutputEvent("c", "ls", a.event.viewerURL, null)
        store.session(b)
        store.session(c) // evicts "toolu_1"
        val aNew = store.session(event())
        assertFalse("evicted session must not be resurrected", a === aNew)
    }
}
