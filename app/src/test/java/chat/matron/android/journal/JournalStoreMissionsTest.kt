package chat.matron.android.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemState
import chat.matron.android.models.Milestone
import chat.matron.android.models.MilestoneKind
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionConversation
import chat.matron.android.models.MissionLastMilestone
import chat.matron.android.models.MissionState
import chat.matron.android.models.TrackerItem
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// The mission cache: `mission` / `milestone` / `mission_conversation`.
/// Ported from matron-apple's `JournalStoreMissionsTests`.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalStoreMissionsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun makeStore() = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")

    private fun mission(
        id: String, num: Int, state: MissionState = MissionState.OPEN, convo: String = "c1",
        lastMilestoneAt: Long? = 10, needsYou: Int = 0, closedAt: Long? = null,
    ) = Mission(
        id = id, num = num, state = state, title = "M$num", body = "goal", originConvoID = convo,
        createdAt = Instant.ofEpochSecond(1), updatedAt = Instant.ofEpochSecond(2),
        lastMilestoneAt = lastMilestoneAt?.let(Instant::ofEpochSecond), closedAt = closedAt?.let(Instant::ofEpochSecond),
        openItems = needsYou, needsYou = needsYou, conversationCount = 1, milestoneCount = 1,
        lastMilestone = MissionLastMilestone(num + 1, "step", MilestoneKind.PROGRESS, Instant.ofEpochSecond(lastMilestoneAt ?: 0)),
    )

    private fun milestone(
        id: String, mission: String, num: Int, convo: String = "c1", seq: Long,
        kind: MilestoneKind = MilestoneKind.PROGRESS, created: Long,
    ) = Milestone(
        id = id, missionID = mission, num = num, kind = kind, title = "T$num", body = "b", convoID = convo,
        seq = seq, createdAt = Instant.ofEpochSecond(created),
    )

    @Test
    fun missionsRoundTripAndSortByLatestMilestone() = runBlocking {
        val store = makeStore()
        store.upsertMissions(
            listOf(
                mission("ms_1", 61, lastMilestoneAt = 10),
                mission("ms_2", 62, convo = "c2", lastMilestoneAt = 30, needsYou = 2),
                mission("ms_3", 63, convo = "c3", lastMilestoneAt = null),
                mission("ms_4", 64, state = MissionState.CLOSED, convo = "c4", lastMilestoneAt = 20, closedAt = 40),
            ),
        )
        // Open, newest milestone first, a mission with no milestone last.
        assertEquals(listOf("ms_2", "ms_1", "ms_3"), store.missions(MissionState.OPEN).map { it.id })
        assertEquals(listOf("ms_4"), store.missions(MissionState.CLOSED).map { it.id })
        // state = null puts every open mission ahead of every closed one.
        assertEquals(listOf("ms_2", "ms_1", "ms_3", "ms_4"), store.missions(null).map { it.id })
        assertEquals("every field survives, the last milestone included", mission("ms_2", 62, convo = "c2", lastMilestoneAt = 30, needsYou = 2), store.mission("ms_2"))
        assertEquals("step", store.mission("ms_2")?.lastMilestone?.title)
        assertEquals("ms_3", store.mission(num = 63)?.id)
        assertNull(store.mission(num = 999))

        // Upsert replaces in place — the fetched row wins, no duplicates.
        store.upsertMissions(listOf(mission("ms_1", 61, state = MissionState.CLOSED, lastMilestoneAt = 10, closedAt = 50)))
        assertEquals(4, store.missions(null).size)
        assertEquals(MissionState.CLOSED, store.mission("ms_1")?.state)
    }

    @Test
    fun milestonesAreReplacedWholesaleAndReadNewestFirst() = runBlocking {
        val store = makeStore()
        store.upsertMissions(listOf(mission("ms_1", 61)))
        store.replaceMilestones(
            "ms_1",
            listOf(
                milestone("ml_1", "ms_1", 62, seq = 100, created = 1),
                milestone("ml_2", "ms_1", 63, seq = 200, kind = MilestoneKind.USER_INPUT, created = 5),
            ),
        )
        assertEquals(listOf("ml_2", "ml_1"), store.milestones("ms_1").map { it.id })
        assertEquals(200L, store.milestones("ms_1").first().seq)
        assertEquals(MilestoneKind.USER_INPUT, store.milestones("ms_1").first().kind)
        store.replaceMilestones("ms_1", listOf(milestone("ml_2", "ms_1", 63, seq = 200, created = 5)))
        assertEquals("a replace drops rows the server no longer returns", listOf("ml_2"), store.milestones("ms_1").map { it.id })
    }

    @Test
    fun milestonesByConversationAreNewestFirst() = runBlocking {
        val store = makeStore()
        store.upsertMissions(listOf(mission("ms_1", 61)))
        store.replaceMilestones(
            "ms_1",
            listOf(
                milestone("ml_1", "ms_1", 62, convo = "c1", seq = 100, created = 1),
                milestone("ml_2", "ms_1", 63, convo = "c9", seq = 900, created = 9),
                milestone("ml_3", "ms_1", 64, convo = "c1", seq = 300, created = 3),
            ),
        )
        assertEquals(listOf("ml_3", "ml_1"), store.milestonesForConversation("c1").map { it.id })
        assertTrue(store.milestonesForConversation("nope").isEmpty())
    }

    /// A conversation's mission: origin first, then `mission_conversation`
    /// membership, then any milestone posted in it (the join / inheritance
    /// cases, which the snapshot never carries).
    @Test
    fun missionIDForConversationPrefersOriginThenMembershipThenMilestone() = runBlocking {
        val store = makeStore()
        store.upsertMissions(listOf(mission("ms_1", 61, convo = "c1")))
        store.replaceMilestones("ms_1", listOf(milestone("ml_1", "ms_1", 62, convo = "c7", seq = 10, created = 1)))
        assertEquals("origin conversation", "ms_1", store.missionID("c1"))
        assertEquals("joined conversation, learned from its milestone", "ms_1", store.missionID("c7"))
        assertNull(store.missionID("c8"))
    }

    /// A conversation that joined or inherited a mission is named in
    /// `mission_conversation` (populated by a detail fetch) before it has
    /// ever hosted a milestone — the title-tap affordance must not wait for
    /// a checkpoint that may never come.
    @Test
    fun missionIDForAJoinedConversationWithNoMilestoneYet() = runBlocking {
        val store = makeStore()
        store.upsertMissions(listOf(mission("ms_1", 61, convo = "c1")))
        store.replaceMissionConversations(
            "ms_1",
            listOf(
                MissionConversation("c1", "Origin", null, "running"),
                MissionConversation("c9", "Joined", null, "running"),
            ),
        )
        assertEquals("joined, no milestone posted there yet", "ms_1", store.missionID("c9"))
        assertNull("not a member and no milestone either", store.missionID("c10"))
    }

    /// The live form re-fires on a write to ANY of the three tables it
    /// derives from — a title tap becomes possible the moment membership is
    /// known, without reopening the chat.
    @Test
    fun missionIDFlowFollowsMembershipWrites() = runBlocking {
        val store = makeStore()
        store.missionIDFlow("c9").test {
            assertNull(awaitItem())
            store.upsertMissions(listOf(mission("ms_1", 61, convo = "c1")))
            store.replaceMissionConversations("ms_1", listOf(MissionConversation("c9", "Joined", null, "running")))
            assertEquals("ms_1", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun missionConversationsAreReplacedWholesale() = runBlocking {
        val store = makeStore()
        store.upsertMissions(listOf(mission("ms_1", 61)))
        store.replaceMissionConversations(
            "ms_1",
            listOf(
                MissionConversation("c1", "Session", "dev-2", "running"),
                MissionConversation("c2", "Other", null, "idle"),
            ),
        )
        assertEquals(listOf("c1", "c2"), store.missionConversations("ms_1").map { it.id })
        assertNull(store.missionConversations("ms_1").last().box)
        store.replaceMissionConversations("ms_1", listOf(MissionConversation("c2", "Other", null, "idle")))
        assertEquals(listOf("c2"), store.missionConversations("ms_1").map { it.id })
    }

    /// The mission page's open items come from the local item cache, with
    /// the ones awaiting the user first.
    @Test
    fun itemsForMissionPutAwaitingYouFirst() = runBlocking {
        val store = makeStore()
        store.upsertItems(
            listOf(
                TrackerItem(id = "it_1", num = 1, kind = ItemKind.TASK, awaiting = ItemAwaiting.AGENT, title = "agent one", originConvoID = "c1", updatedAt = Instant.ofEpochSecond(9), missionID = "ms_1", missionNum = 61),
                TrackerItem(id = "it_2", num = 2, kind = ItemKind.QUESTION, awaiting = ItemAwaiting.USER, title = "needs you", originConvoID = "c1", updatedAt = Instant.ofEpochSecond(1), missionID = "ms_1", missionNum = 61),
                TrackerItem(id = "it_3", num = 3, kind = ItemKind.TASK, state = ItemState.CLOSED, title = "done", originConvoID = "c1", updatedAt = Instant.ofEpochSecond(8), missionID = "ms_1", missionNum = 61),
                TrackerItem(id = "it_4", num = 4, kind = ItemKind.TASK, awaiting = ItemAwaiting.AGENT, title = "other mission", originConvoID = "c2", updatedAt = Instant.ofEpochSecond(7), missionID = "ms_9", missionNum = 99),
            ),
        )
        assertEquals(
            "awaiting-you first, then updatedAt desc; closed items are excluded",
            listOf("it_2", "it_1"), store.missionItems("ms_1").map { it.id },
        )
    }

    /// The authoritative replace (CodeRabbit apple #209 MAJOR): a mission
    /// outside the given set goes, with its milestones and conversations
    /// (no FK cascade), and tracker rows stop pointing at it; a protected
    /// id survives and its cached row is NOT overwritten by the stale one.
    @Test
    fun replaceMissionsIsAuthoritativeAndHonoursProtectedIDs() = runBlocking {
        val store = makeStore()
        store.upsertMissions(listOf(mission("ms_1", 61), mission("ms_2", 62), mission("ms_3", 63)))
        store.replaceMilestones("ms_1", listOf(milestone("ml_1", "ms_1", 1, seq = 400, created = 4)))
        store.replaceMissionConversations("ms_1", listOf(MissionConversation("c1", "Session", "dev-2", "running")))
        store.upsertItems(listOf(TrackerItem(id = "it_1", num = 900, kind = ItemKind.TASK, title = "carry", originConvoID = "c1", missionID = "ms_1", missionNum = 61)))

        store.replaceMissions(
            listOf(mission("ms_2", 62), Mission(id = "ms_3", num = 63, title = "stale title", originConvoID = "c1")),
            protectedIDs = setOf("ms_3"),
        )
        assertEquals(listOf("ms_2", "ms_3"), store.missions(null).map { it.id }.sorted())
        assertTrue(store.milestones("ms_1").isEmpty())
        assertTrue(store.missionConversations("ms_1").isEmpty())
        val survivor = store.item("it_1")
        assertNull("an item pointed at a deleted mission must be cleared, not left dangling", survivor?.missionID)
        assertNull(survivor?.missionNum)
        assertEquals("a protected id keeps the row a detail fetch just wrote", "M63", store.mission("ms_3")?.title)
    }

    @Test
    fun missionsFlowEmitsOnWrite() = runBlocking {
        val store = makeStore()
        store.missionsFlow(MissionState.OPEN).test {
            assertTrue(awaitItem().isEmpty())
            store.upsertMissions(listOf(mission("ms_1", 61)))
            assertEquals(listOf("ms_1"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun wipeClearsTheMissionCache() = runBlocking {
        val store = makeStore()
        store.upsertMissions(listOf(mission("ms_1", 61)))
        store.replaceMilestones("ms_1", listOf(milestone("ml_1", "ms_1", 62, seq = 1, created = 1)))
        store.replaceMissionConversations("ms_1", listOf(MissionConversation("c1", "S", null, "idle")))
        store.wipe()
        assertTrue(store.missions(null).isEmpty())
        assertTrue(store.milestones("ms_1").isEmpty())
        assertTrue(store.missionConversations("ms_1").isEmpty())
        // And the sign-out form on its own.
        store.upsertMissions(listOf(mission("ms_1", 61)))
        store.wipeMissions()
        assertTrue(store.missions(null).isEmpty())
    }

    /// `sessionTags` restates the chat list's tag derivation for the
    /// mission page: room halves for a genuine multi-agent room, the
    /// single-box halves otherwise, no entry for an unsynced conversation.
    @Test
    fun sessionTagsCarryRoomHalvesForARoomAndFallBackOtherwise() = runBlocking {
        val store = makeStore()
        store.replaceAgents(listOf(AgentDTO(id = 7, name = "dev-y"), AgentDTO(id = 9, name = "dev-z")))
        store.applyColdSnapshot(
            listOf(
                ConvoSummaryDTO(id = "room", title = "↔️ [ab] mac ↔ dev-z", sessionState = "waiting", lastSeq = 1, snippet = "", createdAt = 1, agentDeviceID = 7, participants = listOf(7, 9)),
                ConvoSummaryDTO(id = "local", title = "[cd] mac", sessionState = "waiting", lastSeq = 1, snippet = "", createdAt = 1, agentDeviceID = 7, participants = listOf(7)),
            ),
            headSeq = 1,
        )
        val tags = store.sessionTags(setOf("room", "local", "unsynced"))
        assertEquals(listOf("dev-y", "dev-z"), tags["room"]?.roomBoxNames)
        assertEquals(2, tags["room"]?.roomBoxShorts?.size)
        assertEquals("ab", tags["room"]?.sessionShort)
        assertEquals("a local room's two ends share one box: single-box halves only", emptyList<String>(), tags["local"]?.roomBoxNames)
        assertEquals("dev-y", tags["local"]?.boxName)
        assertEquals("cd", tags["local"]?.sessionShort)
        assertNull("an unsynced conversation carries no tag rather than an empty one", tags["unsynced"])
        assertTrue(store.sessionTags(emptySet()).isEmpty())
    }

    /// Bugbot (#79) suspected `NOT IN ()` fails on an empty keep set.
    /// SQLite accepts an empty IN list (its documented extension), so a
    /// successful `GET /missions` answering with no rows sweeps the cache.
    @Test
    fun replaceMissionsWithAnEmptyListSweepsEverything() = runBlocking {
        val store = makeStore()
        store.upsertMissions(listOf(mission("ms_1", 61), mission("ms_2", 62)))
        store.replaceMilestones("ms_1", listOf(milestone("ml_1", "ms_1", 1, seq = 1, created = 1)))
        store.replaceMissions(emptyList())
        assertTrue(store.missions(null).isEmpty())
        assertTrue(store.milestones("ms_1").isEmpty())
        // And an empty set with a protected id keeps only that id.
        store.upsertMissions(listOf(mission("ms_1", 61), mission("ms_2", 62)))
        store.replaceMissions(emptyList(), protectedIDs = setOf("ms_2"))
        assertEquals(listOf("ms_2"), store.missions(null).map { it.id })
    }
}
