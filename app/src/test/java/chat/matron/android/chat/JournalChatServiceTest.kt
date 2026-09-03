package chat.matron.android.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.AgentDTO
import chat.matron.android.journal.ConvoSummaryDTO
import chat.matron.android.journal.FakeConnector
import chat.matron.android.journal.FakeSnapshotSource
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalStore
import chat.matron.android.journal.JournalSyncEngine
import chat.matron.android.journal.db.MatronDatabase
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Ported from matron-apple's `JournalChatServiceTests`. In-memory Room under
/// Robolectric; the engine never connects (list tests only read the store).
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalChatServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun makeStore() = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")

    private fun makeEngine(store: JournalStore, backoff: Double = 0.01) = JournalSyncEngine(
        api = FakeSnapshotSource(), store = store, connector = FakeConnector(emptyList()),
        token = "t", ownSender = "user:dan", search = null, backoffBaseSeconds = backoff,
    )

    private fun makeService(store: JournalStore, coalesce: Duration = 250.milliseconds) =
        JournalChatService(store, makeEngine(store), coalesce)

    private fun ev(seq: Long, convo: String, sender: String, body: String) = JournalEvent(
        seq, convo, Instant.ofEpochMilli(seq * 1000), sender, "text", buildJsonObject { put("body", body) },
    )

    private class FlowProbe<T>(scope: CoroutineScope, flow: Flow<T>) {
        private val channel = Channel<T>(Channel.UNLIMITED)
        private val job: Job = scope.launch { flow.collect { channel.send(it) } }
        suspend fun next(timeoutMs: Long = 3000): T = withTimeout(timeoutMs) { channel.receive() }
        fun cancel() = job.cancel()
    }

    @Test fun chatSummariesMapAndStream() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(ConvoSummaryDTO("c1", "Fix build", "running", 3, "s", 1_752_000_000_000)),
            headSeq = 3,
        )
        val service = makeService(store)
        val probe = FlowProbe(this, service.chatSummaries())
        val summaries = probe.next()
        assertEquals(1, summaries.size)
        assertEquals("c1", summaries.first().id)
        assertEquals("Fix build", summaries.first().title)
        assertEquals(0, summaries.first().unreadCount)
        assertNotNull(summaries.first().lastActivity)
        probe.cancel()
    }

    @Test fun chatSummariesExcludeChildrenButChildrenStreamIncludesThem() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(
                ConvoSummaryDTO("p1", "Parent", "running", 1, "", 1, parentConvoID = null),
                ConvoSummaryDTO("p1:sub:a1", "explore", "running", 2, "", 2, parentConvoID = "p1"),
                ConvoSummaryDTO("p1:sub:b2", "test", "done", 3, "", 3, parentConvoID = "p1"),
            ),
            headSeq = 3,
        )
        val service = makeService(store)
        val listProbe = FlowProbe(this, service.chatSummaries())
        assertEquals(listOf("p1"), listProbe.next().map { it.id })
        listProbe.cancel()

        val childProbe = FlowProbe(this, service.children("p1"))
        assertEquals(
            listOf(
                SubChatSummary("p1:sub:a1", "explore", true),
                SubChatSummary("p1:sub:b2", "test", false),
            ),
            childProbe.next(),
        )
        childProbe.cancel()
    }

    @Test fun untitledConvoFallsBackToID() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, "sess-42", "agent:a", "x"))
        val service = makeService(store)
        val probe = FlowProbe(this, service.chatSummaries())
        val summaries = probe.next()
        assertEquals("sess-42", summaries.first().title)
        assertEquals(1, summaries.first().unreadCount)
        probe.cancel()
    }

    @Test fun chatSummariesCoalesceBurstsToNewestSnapshot() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, "c1", "agent:a", "first"))
        val service = makeService(store, coalesce = 50.milliseconds)
        val probe = FlowProbe(this, service.chatSummaries())
        assertEquals(1, probe.next().first().unreadCount)

        for (seq in 2L..6L) store.applyJournal(ev(seq, "c1", "agent:a", "m"))

        var emissions = 0
        while (true) {
            val next = probe.next()
            emissions++
            if (next.first().unreadCount == 6) break
            assertTrue("burst should coalesce, not replay per-frame", emissions < 5)
        }
        assertTrue(emissions < 5)
        probe.cancel()
    }

    @Test fun createChatThrowsGracefully() = runBlocking {
        val service = makeService(makeStore())
        try {
            service.createChat("claude")
            fail("expected throw")
        } catch (e: Throwable) {
            assertTrue(e is JournalChatError)
        }
    }

    @Test fun leaveHidesConversation() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, "c1", "agent:a", "x"))
        val service = makeService(store)
        service.leave("c1")
        assertEquals(0, store.conversations().size)
    }

    @Test fun forceSnapshotIsBestEffortWhenOffline() = runBlocking {
        val service = makeService(makeStore())
        service.forceSnapshot() // must not throw or hang
    }

    /// Ports matron-apple's `testBoxNameOnlyResolvesWhenTheUserHasTwoOrMoreBoxes`.
    /// Records come from the store rather than a literal so the real snapshot
    /// path also proves agent_device_id survives a round trip.
    @Test fun boxNameOnlyResolvesWhenTheUserHasTwoOrMoreBoxes() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(
                ConvoSummaryDTO("c1", "Fix the parser", "running", 1, "", 1, agentDeviceID = 7),
                ConvoSummaryDTO("c2", "No box", "running", 1, "", 1),
                ConvoSummaryDTO("c3", "Old", "done", 1, "", 1, agentDeviceID = 999),
            ),
            headSeq = 1,
        )
        val owned = store.conversation("c1")!!
        val orphan = store.conversation("c2")!!
        val stale = store.conversation("c3")!!

        // One box: no chip anywhere — a single-box user has nothing to
        // disambiguate and shouldn't pay for the clutter.
        assertNull(JournalChatService.summary(owned, mapOf(7L to "dev-y")).boxName)

        // Two boxes: the owning box is named.
        val two = mapOf(7L to "dev-y", 9L to "dev-z")
        assertEquals("dev-y", JournalChatService.summary(owned, two).boxName)
        // …but a conversation with no recorded box still shows nothing.
        assertNull(JournalChatService.summary(orphan, two).boxName)
        // …and an id that resolves to nothing (revoked box) shows nothing.
        assertNull(JournalChatService.summary(stale, two).boxName)
    }

    /// Ports matron-apple's `testRoomBoxNamesTagEveryParticipatingBox`. Same
    /// store-backed setup as the boxName test: participants round-trip through
    /// the real snapshot path into the record the summary reads.
    @Test fun roomBoxNamesTagEveryParticipatingBox() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(
                ConvoSummaryDTO(
                    "room", "↔️ [ab] mac ↔ dev-z", "waiting", 1, "", 1,
                    agentDeviceID = 7, participants = listOf(7, 9),
                ),
                ConvoSummaryDTO(
                    "local", "↔️ [cd] mac ↔ mac", "waiting", 1, "", 1,
                    agentDeviceID = 7, participants = listOf(7),
                ),
                ConvoSummaryDTO(
                    "ghost", "↔️ [ef] mac ↔ gone", "waiting", 1, "", 1,
                    agentDeviceID = 7, participants = listOf(7, 999),
                ),
            ),
            headSeq = 1,
        )
        val room = store.conversation("room")!!
        val local = store.conversation("local")!!
        val ghost = store.conversation("ghost")!!
        val two = mapOf(7L to "dev-y", 9L to "dev-z")
        val letters = SessionTag.boxLetters(two)

        // A genuine multi-box room tags every box — names for the hue,
        // letters for the glyphs, journal order — and the room short comes
        // off the `↔️ [ab] ` title prefix with the marker kept.
        val multi = JournalChatService.summary(room, two, letters)
        assertEquals(listOf("dev-y", "dev-z"), multi.roomBoxNames)
        assertEquals(listOf("Y", "Z"), multi.roomBoxShorts)
        assertEquals("ab", multi.sessionShort)
        assertEquals("↔️ mac ↔ dev-z", multi.title)

        // Single-box user: same gate as the single-box tag — no letters.
        val gated = JournalChatService.summary(room, mapOf(7L to "dev-y"))
        assertEquals(emptyList<String>(), gated.roomBoxNames)
        assertNull(gated.boxName)

        // A local room's two ends share one box: fall back to the single
        // owning-box tag rather than a redundant one-entry "pair".
        val solo = JournalChatService.summary(local, two, letters)
        assertEquals(emptyList<String>(), solo.roomBoxNames)
        assertEquals("dev-y", solo.boxName)
        assertEquals("Y", solo.boxShort)

        // A participant whose box was revoked resolves to nothing — with
        // only one name left the room tag collapses to the same fallback.
        val revoked = JournalChatService.summary(ghost, two, letters)
        assertEquals(emptyList<String>(), revoked.roomBoxNames)
        assertEquals("dev-y", revoked.boxName)
    }

    /// Ports matron-apple's `testSummaryStripsTheShortAndGatesTheLetter`
    /// (from SessionTagTests — store-backed, so it lives here under
    /// Robolectric): the fields rows actually consume.
    @Test fun summaryStripsTheShortAndGatesTheLetter() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(
                ConvoSummaryDTO("c1", "[b5] css token migration", "running", 1, "", 1, agentDeviceID = 7),
            ),
            headSeq = 1,
        )
        val record = store.conversation("c1")!!

        // Two boxes: title is cleaned, short peeled, letter derived.
        val two = mapOf(7L to "dev-y", 9L to "dev-z")
        val tagged = JournalChatService.summary(record, two, SessionTag.boxLetters(two))
        assertEquals("css token migration", tagged.title)
        assertEquals("b5", tagged.sessionShort)
        assertEquals("Y", tagged.boxShort)

        // One box: the session short still shows (it tells SESSIONS apart),
        // but the letter obeys the chip gate.
        val one = mapOf(7L to "dev-y")
        val solo = JournalChatService.summary(record, one, SessionTag.boxLetters(one))
        assertEquals("b5", solo.sessionShort)
        assertNull(solo.boxShort)
        assertNull(solo.boxName)
    }

    /// Ports matron-apple's `testRenamingABoxRelabelsAnOpenChatList`: a rename
    /// arrives as `device_meta`, which writes the `agent` table and nothing
    /// else — the conversations flow alone never re-fires for it, so the
    /// summaries stream must observe the roster too (the `combine` in
    /// `chatSummaries`) or open chips keep the old label until unrelated
    /// conversation activity happens to re-fire the list.
    @Test fun renamingABoxRelabelsAnOpenChatList() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(ConvoSummaryDTO("c1", "Fix the parser", "running", 1, "", 1, agentDeviceID = 7)),
            headSeq = 1,
        )
        store.replaceAgents(listOf(AgentDTO(7, "dev-y"), AgentDTO(9, "dev-z")))
        val service = makeService(store, coalesce = 10.milliseconds)

        val probe = FlowProbe(this, service.chatSummaries())
        // Let the first snapshot (and its "dev-y" chip) land before the
        // rename, so the relabel is unambiguously a re-emission.
        val labels = mutableListOf<String?>()
        labels.add(probe.next().first().boxName)

        store.renameAgent(7, "dev-yellow")
        while (labels.last() != "dev-yellow") {
            val label = probe.next().firstOrNull()?.boxName
            if (labels.last() != label) labels.add(label)
        }
        assertEquals(
            "an agent rename must re-emit summaries with the new chip label",
            listOf("dev-y", "dev-yellow"),
            labels,
        )
        probe.cancel()
    }

    @Test fun refreshThrowsWhenEngineStopped() = runBlocking {
        val store = makeStore()
        val engine = makeEngine(store)
        val service = JournalChatService(store, engine)
        engine.beginSync()
        delay(30)
        engine.endSync()
        try {
            service.refresh()
            fail("expected refresh to throw once the engine is stopped")
        } catch (e: Throwable) {
            // expected
        }
    }
}
