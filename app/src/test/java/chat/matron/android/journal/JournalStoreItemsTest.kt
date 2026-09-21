package chat.matron.android.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import chat.matron.android.journal.db.ItemOutboxEntity
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.ItemState
import chat.matron.android.models.ItemsScope
import chat.matron.android.models.TrackerAttachment
import chat.matron.android.models.TrackerComment
import chat.matron.android.models.TrackerItem
import chat.matron.android.models.TrackerLink
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// The tracker cache: `item` / `item_comment` / `item_outbox` and the
/// per-scope watermarks in `meta`. Ported from matron-apple's
/// `JournalStoreItemsTests`.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalStoreItemsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun makeStore() = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")

    private fun item(
        id: String, num: Int, convo: String = "c1", rank: Double = 1024.0, kind: ItemKind = ItemKind.TASK,
        state: ItemState = ItemState.OPEN, awaiting: ItemAwaiting? = ItemAwaiting.AGENT,
    ) = TrackerItem(
        id = id, num = num, kind = kind, state = state, awaiting = awaiting, rank = rank, title = "T$num",
        body = "b", labels = listOf("l1"), links = listOf(TrackerLink("https://x", "X")),
        attachments = listOf(TrackerAttachment("blob", "image/png", "s.png", 3)), originConvoID = convo,
        createdAt = Instant.ofEpochMilli(1_000), updatedAt = Instant.ofEpochMilli(2_000), hasImage = true,
    )

    private fun outboxRow(localID: String, itemID: String?, op: String, payload: String, createdAt: Long = 0) =
        ItemOutboxEntity(localID, itemID, op, payload, createdAt, attempts = 0, lastError = null)

    @Test
    fun upsertRoundTripsAndScopes() = runBlocking {
        val store = makeStore()
        val a = item("a", 1, rank = 2.0)
        val b = item("b", 2, convo = "c2", rank = 1.0)
        store.upsertItems(listOf(a, b))
        assertEquals("every field survives the JSON columns", a, store.item("a"))
        assertEquals(listOf("b", "a"), store.items(ItemsScope.All).map { it.id })
        assertEquals(listOf("a"), store.items(ItemsScope.Convo("c1")).map { it.id })
        // Upsert replaces in place.
        store.upsertItems(listOf(a.copy(title = "renamed")))
        assertEquals("renamed", store.item("a")?.title)
        assertEquals(2, store.items(ItemsScope.All).size)
    }

    @Test
    fun itemByNumberFindsTheItemAndMissesCleanly() = runBlocking {
        val store = makeStore()
        store.upsertItems(listOf(item("a", 65)))
        assertEquals("a", store.item(num = 65)?.id)
        assertNull(store.item(num = 66))
    }

    @Test
    fun commentsReplaceWholesale() = runBlocking {
        val store = makeStore()
        store.replaceComments("it_1", listOf(TrackerComment("c1", "it_1", ItemAuthor.USER, body = "x", createdAt = Instant.ofEpochMilli(1))))
        store.replaceComments(
            "it_1",
            listOf(
                TrackerComment("c2", "it_1", ItemAuthor.AGENT, body = "y", createdAt = Instant.ofEpochMilli(2)),
                TrackerComment("c3", "it_1", ItemAuthor.USER, body = "z", createdAt = Instant.ofEpochMilli(3)),
            ),
        )
        assertEquals(listOf("c2", "c3"), store.comments("it_1").map { it.id })
        assertEquals("other items' threads are untouched", emptyList<TrackerComment>(), store.comments("it_2"))
    }

    @Test
    fun insertCommentsUpsertsWithoutDeletingExisting() = runBlocking {
        val store = makeStore()
        store.replaceComments("it_1", listOf(TrackerComment("c1", "it_1", ItemAuthor.USER, body = "x", createdAt = Instant.ofEpochMilli(1))))
        store.insertComments(listOf(TrackerComment("c2", "it_1", ItemAuthor.AGENT, body = "y", createdAt = Instant.ofEpochMilli(2))))
        assertEquals(listOf("c1", "c2"), store.comments("it_1").map { it.id })
        store.insertComments(listOf(TrackerComment("c1", "it_1", ItemAuthor.USER, body = "edited", createdAt = Instant.ofEpochMilli(1))))
        assertEquals("a re-insert of an existing id replaces, not duplicates", listOf("edited", "y"), store.comments("it_1").map { it.body })
    }

    @Test
    fun commentStatusSnapshotRoundTrips() = runBlocking {
        val store = makeStore()
        val status = TrackerComment(
            "s1", "it_1", ItemAuthor.USER, kind = TrackerComment.Kind.STATUS, body = "",
            statusFrom = TrackerItem.StatusSnapshot(ItemState.OPEN, null, ItemAwaiting.USER),
            statusTo = TrackerItem.StatusSnapshot(ItemState.CLOSED, ItemResolution.REVERSED, null),
            createdAt = Instant.ofEpochMilli(5),
        )
        val plain = TrackerComment("c1", "it_1", ItemAuthor.AGENT, body = "hi", createdAt = Instant.ofEpochMilli(6))
        store.replaceComments("it_1", listOf(status, plain))
        val back = store.comments("it_1")
        assertEquals(status, back[0])
        assertEquals(plain, back[1])
        assertNull(back[1].statusFrom); assertNull(back[1].statusTo)
    }

    @Test
    fun itemsFlowFiresOnUpsert() = runBlocking {
        val store = makeStore()
        store.itemsFlow(ItemsScope.Convo("c1")).test {
            assertEquals(emptyList<TrackerItem>(), awaitItem())
            store.upsertItems(listOf(item("a", 1)))
            assertEquals(listOf("a"), awaitItem().map { it.id })
            store.upsertItems(listOf(item("z", 9, convo = "c2")))
            expectNoEvents() // another conversation's row is filtered out and deduplicated
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun itemOutboxCreatesFlowOnlyYieldsCreateRows() = runBlocking {
        val store = makeStore()
        store.itemOutboxInsert(outboxRow("L1", "it_1", ItemOutboxEntity.OP_COMMENT, "{}", 0))
        store.itemOutboxInsert(outboxRow("L2", null, ItemOutboxEntity.OP_CREATE, "{}", 1))
        store.itemOutboxCreatesFlow().test {
            assertEquals(listOf("L2"), awaitItem().map { it.localID })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun outboxLifecycle() = runBlocking {
        val store = makeStore()
        store.itemOutboxInsert(outboxRow("L1", "it_1", ItemOutboxEntity.OP_COMMENT, """{"body":"x"}""", 0))
        store.itemOutboxInsert(outboxRow("L1", "it_1", ItemOutboxEntity.OP_COMMENT, """{"body":"dupe"}""", 0))
        store.itemOutboxInsert(outboxRow("L2", null, ItemOutboxEntity.OP_CREATE, "{}", 1))
        assertEquals("insert is idempotent on local id; pending is oldest first", listOf("L1", "L2"), store.itemOutboxPending().map { it.localID })
        assertEquals("""{"body":"x"}""", store.itemOutboxRows("it_1").single().payloadJson)
        store.itemOutboxMarkAttempt("L1", "offline")
        val row = store.itemOutboxRows("it_1").single()
        assertEquals(1, row.attempts); assertEquals("offline", row.lastError)
        store.itemOutboxDelete("L1")
        assertEquals(listOf("L2"), store.itemOutboxPending().map { it.localID })
    }

    @Test
    fun commitOutboxResultIsOneWrite() = runBlocking {
        val store = makeStore()
        store.itemOutboxInsert(outboxRow("L1", "it_1", ItemOutboxEntity.OP_COMMENT, "{}", 0))
        val comment = TrackerComment("srv", "it_1", ItemAuthor.USER, body = "x", createdAt = Instant.ofEpochMilli(1))
        store.commitOutboxResult(item("it_1", 1), comment, deletingLocalID = "L1")
        assertNotNull(store.item("it_1"))
        assertEquals(listOf("srv"), store.comments("it_1").map { it.id })
        assertTrue(store.itemOutboxPending().isEmpty())
    }

    @Test
    fun itemsWatermarkIsPerScopeAndClearedByWipeItems() = runBlocking {
        val store = makeStore()
        assertNull(store.itemsWatermark(ItemsScope.All))
        store.setItemsWatermark(Instant.ofEpochMilli(20_000), ItemsScope.All)
        store.setItemsWatermark(Instant.ofEpochMilli(30_000), ItemsScope.Convo("c1"))
        assertEquals(Instant.ofEpochMilli(20_000), store.itemsWatermark(ItemsScope.All))
        assertEquals(Instant.ofEpochMilli(30_000), store.itemsWatermark(ItemsScope.Convo("c1")))
        assertNull("another convo's watermark is independent", store.itemsWatermark(ItemsScope.Convo("c2")))
        store.wipeItems()
        assertNull(store.itemsWatermark(ItemsScope.All))
        assertNull(store.itemsWatermark(ItemsScope.Convo("c1")))
    }

    @Test
    fun wipeItemsClearsAllThreeAndKeepsTheCursor() = runBlocking {
        val store = makeStore()
        store.applyJournal(JournalEvent(1, "c1", Instant.ofEpochMilli(1), "agent:a", "text", buildJsonObject { put("body", "hi") }))
        store.upsertItems(listOf(item("a", 1)))
        store.replaceComments("a", listOf(TrackerComment("c1", "a", ItemAuthor.USER, body = "x")))
        store.itemOutboxInsert(outboxRow("L1", "a", ItemOutboxEntity.OP_COMMENT, "{}"))
        store.wipeItems()
        assertTrue(store.items(ItemsScope.All).isEmpty())
        assertTrue(store.comments("a").isEmpty())
        assertTrue(store.itemOutboxPending().isEmpty())
        assertEquals("only the tracker's meta keys go", 1L, store.cursor())
    }

    @Test
    fun fullWipeClearsCacheButKeepsItemOutbox() = runBlocking {
        val store = makeStore()
        store.upsertItems(listOf(item("a", 1)))
        store.replaceComments("a", listOf(TrackerComment("c1", "a", ItemAuthor.USER, body = "x")))
        store.setItemsWatermark(Instant.ofEpochMilli(20_000), ItemsScope.All)
        store.itemOutboxInsert(outboxRow("L1", "a", ItemOutboxEntity.OP_COMMENT, "{}"))
        store.wipe()
        assertTrue(store.items(ItemsScope.All).isEmpty())
        assertTrue(store.comments("a").isEmpty())
        assertNull("a wiped mirror refetches from scratch", store.itemsWatermark(ItemsScope.All))
        assertEquals("a replay-gap wipe must not eat an offline reply", listOf("L1"), store.itemOutboxPending().map { it.localID })
    }

    @Test
    fun wipeOutboxClearsItemOutbox() = runBlocking {
        val store = makeStore()
        store.outboxInsert("t1", "c1", "hello")
        store.itemOutboxInsert(outboxRow("L1", "a", ItemOutboxEntity.OP_COMMENT, "{}"))
        store.wipeOutbox()
        assertTrue(store.outboxPending().isEmpty())
        assertTrue(store.itemOutboxPending().isEmpty())
    }

    private fun fallbackTwin(seq: Long, sender: String = "agent:a") = JournalEvent(
        seq, "c1", Instant.ofEpochMilli(seq * 1000), sender, JournalEventType.TEXT,
        buildJsonObject {
            put("body", "📌 Needs you — question #12: Which auth?"); put("fallback_for", "item")
            put("item_id", "it_1"); put("num", 12); put("action", "created")
        },
    )

    /// The item marker's old-client `fallback_for` text twin is hidden from
    /// the timeline, so it must not act as a message anywhere the user could
    /// notice: no snippet, no unread bump, no activity timestamp, no search
    /// body — live, on a read-marker recount, and on a history recount alike
    /// (Bugbot, #71).
    @Test
    fun fallbackTwinDoesNotBumpUnreadSnippetOrActivity() = runBlocking {
        val store = makeStore()
        store.applyJournal(JournalEvent(1, "c1", Instant.ofEpochMilli(1_000), "agent:a", JournalEventType.TEXT, buildJsonObject { put("body", "real message") }))
        val before = store.conversation("c1")!!
        assertEquals(1, before.unreadCount); assertEquals("real message", before.snippet)

        assertTrue(store.applyJournal(fallbackTwin(2)))
        val after = store.conversation("c1")!!
        assertEquals("the twin is a stored journal row", 2L, after.lastSeq)
        assertEquals("but not a message: unread unchanged", 1, after.unreadCount)
        assertEquals("snippet unchanged", "real message", after.snippet)
        assertEquals("activity unchanged", before.lastActivityTS, after.lastActivityTS)
        assertNull("never indexed for search", fallbackTwin(2).previewText())

        // A read-marker recount (the SQL path) agrees with the incremental count.
        store.applyJournal(JournalEvent(3, "c1", Instant.ofEpochMilli(3_000), "user:dan", JournalEventType.READ_MARKER, buildJsonObject { put("up_to_seq", 0) }))
        assertEquals(1, store.conversation("c1")!!.unreadCount)

        // And the history path's recount.
        store.insertHistory(listOf(fallbackTwin(4)))
        assertEquals(1, store.conversation("c1")!!.unreadCount)
        assertEquals("a real text still counts", "real message", store.conversation("c1")!!.snippet)
    }

    @Test
    fun conversationOriginLabelsNameTheBox() = runBlocking {
        val store = makeStore()
        store.refreshSummaries(
            listOf(
                ConvoSummaryDTO("c1", "Fix the parser", "running", 1, "", 0, agentDeviceID = 7),
                ConvoSummaryDTO("c2", "Solo chat", "running", 1, "", 0),
                ConvoSummaryDTO("c3", "", "running", 1, "", 0),
            ),
        )
        store.replaceAgents(listOf(AgentDTO(7, "dev-y")))
        val labels = store.conversationOriginLabels()
        assertEquals("dev-y · Fix the parser", labels["c1"])
        assertEquals("Solo chat", labels["c2"])
        assertNull("untitled rows are omitted", labels["c3"])
        assertEquals("dev-y · Fix the parser", store.conversationOriginLabel("c1"))
        assertNull(store.conversationOriginLabel("c3"))
        assertNull(store.conversationOriginLabel("nope"))
    }
}
