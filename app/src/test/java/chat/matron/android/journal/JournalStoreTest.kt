package chat.matron.android.journal

import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import chat.matron.android.journal.db.MatronDatabase
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Ported from matron-apple JournalStoreTests. Uses an in-memory Room database
/// under Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalStoreTest {
    private fun makeStore(): JournalStore {
        val db = MatronDatabase.inMemory(ApplicationProvider.getApplicationContext())
        return JournalStore(db, ownSender = "user:dan")
    }

    private fun ev(
        seq: Long,
        convo: String = "c1",
        sender: String = "agent:dev-2",
        type: String = "text",
        payload: JsonObject = buildJsonObject { put("body", "hi") },
    ) = JournalEvent(seq, convo, Instant.ofEpochMilli(seq * 1000), sender, type, payload)

    private fun body(text: String) = buildJsonObject { put("body", text) }

    // 24h + n hours past the seq=1 event (ts = 1000ms), expressed in epoch ms.
    private fun nowSeconds(seconds: Double): Long = (seconds * 1000).toLong()

    @Test
    fun applyAdvancesCursorAndIsIdempotent() = runBlocking {
        val store = makeStore()
        assertEquals(0L, store.cursor())
        assertTrue(store.applyJournal(ev(1)))
        assertTrue(store.applyJournal(ev(2)))
        assertFalse("replayed frame must be a no-op", store.applyJournal(ev(2)))
        assertEquals(2L, store.cursor())
        assertEquals(listOf(1L, 2L), store.events("c1").map { it.seq })
    }

    @Test
    fun autoCreatesConversationAndUpdatesSummary() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, payload = body("hello world")))
        val convo = store.conversations().first()
        assertEquals("c1", convo.id)
        assertEquals(1L, convo.lastSeq)
        assertEquals("hello world", convo.snippet)
        assertEquals(1, convo.unreadCount)
    }

    @Test
    fun promptEventSnippetIsQuestionMarkPrefixed() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "prompt", payload = buildJsonObject { put("question", "Deploy now?") }))
        assertEquals("? Deploy now?", store.conversations().first().snippet)
    }

    @Test
    fun permissionRequestEventSnippetIsPermissionPrefixed() = runBlocking {
        val store = makeStore()
        store.applyJournal(
            ev(1, type = "permission_request", payload = buildJsonObject { put("description", "run rm -rf tmp/") })
        )
        assertEquals("permission: run rm -rf tmp/", store.conversations().first().snippet)
    }

    @Test
    fun ensureConversationCreatesPlaceholderOnce() = runBlocking {
        val store = makeStore()
        store.ensureConversation("c-new", "New chat")
        val convo = store.conversations().first()
        assertEquals("c-new", convo.id)
        assertEquals("New chat", convo.title)
        assertEquals("running", convo.sessionState)
        assertEquals(0, convo.unreadCount)

        store.applyJournal(ev(1, convo = "c-new", type = "convo_meta", payload = buildJsonObject { put("title", "Real title") }))
        store.ensureConversation("c-new", "New chat")
        val after = store.conversations().first()
        assertEquals("Real title", after.title)
        assertEquals(1L, after.lastSeq)
    }

    @Test
    fun convoMetaSetsTitleForNewConversation() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, convo = "new1", type = "convo_meta", payload = buildJsonObject { put("title", "Fresh chat") }))
        assertEquals("Fresh chat", store.conversations().first { it.id == "new1" }.title)
    }

    @Test
    fun convoMetaUpdatesExistingTitleAndIgnoresEmpty() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "convo_meta", payload = buildJsonObject { put("title", "First") }))
        store.applyJournal(ev(2, type = "convo_meta", payload = buildJsonObject { put("title", "Renamed") }))
        assertEquals("Renamed", store.conversations().first().title)
        store.applyJournal(ev(3, type = "convo_meta", payload = buildJsonObject { put("title", "") }))
        assertEquals("Renamed", store.conversations().first().title)
    }

    @Test
    fun convoMetaDoesNotBumpUnread() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "convo_meta", payload = buildJsonObject { put("title", "T") }))
        assertEquals(0, store.conversations().first().unreadCount)
    }

    @Test
    fun conversationExists() = runBlocking {
        val store = makeStore()
        assertFalse(store.conversationExists("c1"))
        store.applyJournal(ev(1, convo = "c1"))
        assertTrue(store.conversationExists("c1"))
        assertFalse(store.conversationExists("other"))
    }

    @Test
    fun ownMessagesDoNotBumpUnread() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, sender = "user:dan"))
        assertEquals(0, store.conversations().first().unreadCount)
    }

    @Test
    fun readMarkerRecomputesUnread() = runBlocking {
        val store = makeStore()
        for (i in 1L..5L) store.applyJournal(ev(i))
        assertEquals(5, store.conversations().first().unreadCount)
        store.applyJournal(
            ev(6, sender = "user:dan", type = "read_marker",
                payload = buildJsonObject { put("convo_id", "c1"); put("up_to_seq", 4) })
        )
        val convo = store.conversations().first()
        assertEquals(4L, convo.readUpToSeq)
        assertEquals(1, convo.unreadCount)
    }

    @Test
    fun sessionStatusUpdatesStateWithoutUnread() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "session_status", payload = buildJsonObject { put("state", "waiting") }))
        val convo = store.conversations().first()
        assertEquals("waiting", convo.sessionState)
        assertEquals(0, convo.unreadCount)
    }

    @Test
    fun coldSnapshotThenHistoryInsert() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(ConvoSummaryDTO("c1", "T", "running", 10, "s", 0)),
            headSeq = 10,
        )
        assertEquals(10L, store.cursor())
        assertEquals("T", store.conversations().first().title)
        store.insertHistory(listOf(ev(8), ev(9)))
        assertEquals(10L, store.cursor())
        assertEquals(listOf(8L, 9L), store.events("c1").map { it.seq })
        assertEquals(0, store.conversations().first().unreadCount)
        assertEquals(8L, store.minSeq("c1"))
    }

    @Test
    fun refreshSummariesNeverRegressesLastSeq() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(5))
        store.refreshSummaries(listOf(ConvoSummaryDTO("c1", "new title", "done", 3, "old", 0)))
        val convo = store.conversations().first()
        assertEquals("new title", convo.title)
        assertEquals("done", convo.sessionState)
        assertEquals("stale snapshot must not roll back lastSeq", 5L, convo.lastSeq)
    }

    @Test
    fun refreshSummariesUpdatesLastActivityMonotonically() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(5))
        val applied = store.conversations().first().lastActivityTS!!

        store.refreshSummaries(listOf(ConvoSummaryDTO("c1", "T", "running", 9, "new", 0, lastTS = applied + 60_000)))
        assertEquals(applied + 60_000, store.conversations().first().lastActivityTS)

        store.refreshSummaries(listOf(ConvoSummaryDTO("c1", "T", "running", 9, "new", 0, lastTS = applied - 60_000)))
        assertEquals(applied + 60_000, store.conversations().first().lastActivityTS)

        store.refreshSummaries(listOf(ConvoSummaryDTO("c1", "T", "running", 9, "new", 0)))
        assertEquals(applied + 60_000, store.conversations().first().lastActivityTS)
    }

    @Test
    fun coldSnapshotSeedsLastActivityFromLastTS() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(ConvoSummaryDTO("c1", "T", "running", 10, "s", 0, lastTS = 123_000)),
            headSeq = 10,
        )
        assertEquals(123_000L, store.conversations().first().lastActivityTS)
    }

    @Test
    fun conversationsStreamYieldsOnChange() = runBlocking {
        val store = makeStore()
        store.conversationsFlow().test {
            assertEquals(0, awaitItem().size)
            store.applyJournal(ev(1))
            assertEquals("c1", awaitItem().first().id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun wipe() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1))
        store.wipe()
        assertEquals(0L, store.cursor())
        assertEquals(0, store.conversations().size)
    }

    @Test
    fun nonMessageFramesDoNotBumpLastActivity() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1))
        assertEquals(1000L, store.conversations().first().lastActivityTS)

        store.applyJournal(ev(2, sender = "user:dan", type = "read_marker",
            payload = buildJsonObject { put("convo_id", "c1"); put("up_to_seq", 1) }))
        store.applyJournal(ev(3, type = "session_status", payload = buildJsonObject { put("state", "waiting") }))
        store.applyJournal(ev(4, type = "convo_meta", payload = buildJsonObject { put("title", "T") }))
        val convo = store.conversations().first()
        assertEquals(1000L, convo.lastActivityTS)
        assertEquals(4L, convo.lastSeq)
    }

    @Test
    fun chatListOrdersByLastActivityNotBookkeepingSeq() = runBlocking {
        val store = makeStore()
        // b is the older conversation by real activity...
        store.applyJournal(ev(1, convo = "b", type = "text"))
        // ...a is the newer one.
        store.applyJournal(ev(2, convo = "a", type = "text"))
        // Bookkeeping frames land on b afterwards, bumping its last_seq past
        // a's without touching its last_activity_ts.
        store.applyJournal(ev(3, convo = "b", sender = "user:dan", type = "read_marker",
            payload = buildJsonObject { put("convo_id", "b"); put("up_to_seq", 1) }))
        store.applyJournal(ev(4, convo = "b", type = "session_status",
            payload = buildJsonObject { put("state", "waiting") }))

        val convos = store.conversations()
        assertEquals(4L, convos.first { it.id == "b" }.lastSeq)
        assertTrue(
            "convo with newer real activity (a) must sort first despite b's higher last_seq " +
                "from bookkeeping frames",
            convos.first().id == "a",
        )
    }

    @Test
    fun insertHistoryRecountsUnread() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1))
        store.applyJournal(ev(2, sender = "user:dan", type = "read_marker",
            payload = buildJsonObject { put("convo_id", "c1"); put("up_to_seq", 1) }))
        assertEquals(0, store.conversations().first().unreadCount)
        store.insertHistory(listOf(ev(3), ev(4)))
        assertEquals(2, store.conversations().first().unreadCount)
    }

    // MARK: Tool-output TTL

    private fun toolOutputPayload(
        snippet: String? = "output text",
        command: String = "make test",
        liveLog: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("message_ref", "toolu_1")
        put("command", command)
        put("exit_code", 1)
        put("denied", false)
        put("truncated", false)
        put("blob_ref", "blob-1")
        if (liveLog) put("live_log", true)
        if (snippet != null) put("snippet", snippet)
    }

    private suspend fun storedPayload(store: JournalStore, seq: Long): JsonObject =
        store.events("c1").first { it.seq == seq }.payload

    @Test
    fun purgeRewritesStaleLiveLogToTombstone() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "tool_output", payload = toolOutputPayload()))
        store.purgeExpiredToolOutputSnippets(now = nowSeconds(1.0 + 25 * 3600))

        val payload = storedPayload(store, 1)
        assertNull(payload.stringOrNull("snippet"))
        assertFalse("snippet" in payload)
        assertEquals(true, payload.boolOrNull("expired"))
        assertEquals(JsonNull, payload["blob_ref"])
        assertEquals("make test", payload.stringOrNull("command"))
        assertEquals(1, payload.intOrNull("exit_code"))
    }

    @Test
    fun purgeLeavesYoungAndNonLiveLogRows() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "tool_output", payload = toolOutputPayload(liveLog = false)))
        store.applyJournal(ev(2, type = "tool_output", payload = toolOutputPayload()))
        store.purgeExpiredToolOutputSnippets(now = nowSeconds(2.0 + 23 * 3600))
        assertNotNull(storedPayload(store, 2).stringOrNull("snippet"))

        store.purgeExpiredToolOutputSnippets(now = nowSeconds(2.0 + 48 * 3600))
        assertNotNull("legacy payloads without live_log keep their snippet", storedPayload(store, 1).stringOrNull("snippet"))
        assertNull(storedPayload(store, 2).stringOrNull("snippet"))
    }

    @Test
    fun purgeRewritesConvoPreviewWhenPurgedEventIsNewest() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "tool_output", payload = toolOutputPayload()))
        assertEquals("output text", store.conversations(now = nowSeconds(1.0)).first().snippet)
        store.purgeExpiredToolOutputSnippets(now = nowSeconds(1.0 + 25 * 3600))
        assertEquals("$ make test", store.conversations().first().snippet)
    }

    @Test
    fun purgeKeepsConvoPreviewWhenNewerMessageExists() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "tool_output", payload = toolOutputPayload()))
        store.applyJournal(ev(2, payload = body("later text")))
        store.purgeExpiredToolOutputSnippets(now = nowSeconds(2.0 + 48 * 3600))
        assertEquals("later text", store.conversations().first().snippet)
    }

    @Test
    fun purgeIsIdempotent() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "tool_output", payload = toolOutputPayload()))
        val now = nowSeconds(1.0 + 25 * 3600)
        store.purgeExpiredToolOutputSnippets(now = now)
        val first = storedPayload(store, 1)
        store.purgeExpiredToolOutputSnippets(now = now)
        val second = storedPayload(store, 1)
        assertEquals(first.keys.sorted(), second.keys.sorted())
        assertEquals(true, second.boolOrNull("expired"))
    }

    @Test
    fun conversationsAppliesTTLAtReadTimeWithoutPurge() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "tool_output", payload = toolOutputPayload()))
        assertEquals("output text", store.conversations(now = nowSeconds(1.0 + 0.001)).first().snippet)

        val stale = store.conversations(now = nowSeconds(1.0 + 25 * 3600))
        assertEquals("$ make test", stale.first().snippet)

        assertNotNull("read-time enforcement must not write to disk", storedPayload(store, 1).stringOrNull("snippet"))
    }

    @Test
    fun conversationsReadTimeTTLLeavesNonLiveLogSnippetsAlone() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, type = "tool_output", payload = toolOutputPayload(liveLog = false)))
        val stale = store.conversations(now = nowSeconds(1.0 + 48 * 3600))
        assertEquals("output text", stale.first().snippet)
    }

    @Test
    fun conversationsReadTimeTTLLeavesTextSnippetsAlone() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, payload = body("hello world")))
        val stale = store.conversations(now = nowSeconds(1.0 + 48 * 3600))
        assertEquals("hello world", stale.first().snippet)
    }

    // MARK: Subagent sub-chats

    @Test
    fun childConvoMetaSetsParentAndHidesFromList() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, convo = "p1", type = "convo_meta", payload = buildJsonObject { put("title", "Parent") }))
        store.applyJournal(ev(2, convo = "p1:sub:a1", type = "convo_meta",
            payload = buildJsonObject { put("title", "explore repo"); put("parent_convo_id", "p1") }))
        assertEquals(listOf("p1"), store.conversations().map { it.id })

        val children = store.children("p1")
        assertEquals(listOf("p1:sub:a1"), children.map { it.id })
        assertEquals("explore repo", children.first().title)
        assertEquals("p1", children.first().parentConvoID)
        assertEquals("p1", store.parentConvoID("p1:sub:a1"))
        assertNull(store.parentConvoID("p1"))
    }

    @Test
    fun childTitlelessMetaStillLinksParent() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, convo = "p1:sub:a1", type = "convo_meta", payload = buildJsonObject { put("parent_convo_id", "p1") }))
        assertEquals("p1", store.parentConvoID("p1:sub:a1"))
        assertTrue(store.conversations().isEmpty())
    }

    @Test
    fun parentConvoIDIsImmutable() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, convo = "p1:sub:a1", type = "convo_meta",
            payload = buildJsonObject { put("title", "child"); put("parent_convo_id", "p1") }))
        store.applyJournal(ev(2, convo = "p1:sub:a1", type = "convo_meta", payload = buildJsonObject { put("title", "child renamed") }))
        assertEquals("p1", store.parentConvoID("p1:sub:a1"))
        store.refreshSummaries(listOf(ConvoSummaryDTO("p1:sub:a1", "child", "running", 2, "", 0)))
        assertEquals("p1", store.parentConvoID("p1:sub:a1"))
    }

    @Test
    fun snapshotCarriesParentConvoID() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(
                ConvoSummaryDTO("p1", "Parent", "running", 5, "", 0, parentConvoID = null),
                ConvoSummaryDTO("p1:sub:a1", "child", "done", 6, "", 1, parentConvoID = "p1"),
            ),
            headSeq = 6,
        )
        assertEquals(listOf("p1"), store.conversations().map { it.id })
        val children = store.children("p1")
        assertEquals(listOf("p1:sub:a1"), children.map { it.id })
        assertEquals("done", children.first().sessionState)
    }

    @Test
    fun childrenIncludeRunningAndFinishedOrderedByCreation() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(
                ConvoSummaryDTO("p1:sub:b", "second", "done", 2, "", 200, parentConvoID = "p1"),
                ConvoSummaryDTO("p1:sub:a", "first", "running", 1, "", 100, parentConvoID = "p1"),
            ),
            headSeq = 2,
        )
        val children = store.children("p1")
        assertEquals(listOf("p1:sub:a", "p1:sub:b"), children.map { it.id })
        assertEquals(listOf("running", "done"), children.map { it.sessionState })
    }

    @Test
    fun childSessionStateTransitionsRunningToDone() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, convo = "p1:sub:a1", type = "convo_meta",
            payload = buildJsonObject { put("title", "child"); put("parent_convo_id", "p1") }))
        assertEquals("running", store.children("p1").first().sessionState)
        store.applyJournal(ev(2, convo = "p1:sub:a1", type = "session_status", payload = buildJsonObject { put("state", "done") }))
        assertEquals("done", store.children("p1").first().sessionState)
    }

    @Test
    fun nestedChildrenRecurse() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, convo = "p1:sub:a", type = "convo_meta", payload = buildJsonObject { put("parent_convo_id", "p1") }))
        store.applyJournal(ev(2, convo = "p1:sub:a:sub:b", type = "convo_meta", payload = buildJsonObject { put("parent_convo_id", "p1:sub:a") }))
        assertEquals(listOf("p1:sub:a"), store.children("p1").map { it.id })
        assertEquals(listOf("p1:sub:a:sub:b"), store.children("p1:sub:a").map { it.id })
        assertTrue(store.conversations().isEmpty())
    }

    @Test
    fun existingRowsSurviveReopen() = runBlocking {
        // Fresh v1 schema already includes parent_convo_id (no migration). A
        // file-backed store reopened on the same file keeps its rows and
        // defaults parent_convo_id null.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("journal-reopen", ".sqlite")
        file.delete()
        try {
            val firstDb = MatronDatabase.open(context, file)
            JournalStore(firstDb, "user:dan").applyJournal(ev(1, convo = "c1", payload = body("survivor")))
            firstDb.close()

            val secondDb = MatronDatabase.open(context, file)
            val convo = JournalStore(secondDb, "user:dan").conversations().first()
            assertEquals("c1", convo.id)
            assertEquals("survivor", convo.snippet)
            assertNull(convo.parentConvoID)
            secondDb.close()
        } finally {
            file.delete()
        }
        Unit
    }

    @Test
    fun childrenStreamYieldsOnChildCreationAndFinish() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1, convo = "p1", type = "convo_meta", payload = buildJsonObject { put("title", "Parent") }))
        store.childrenFlow("p1").test {
            assertEquals(0, awaitItem().size)
            store.applyJournal(ev(2, convo = "p1:sub:a1", type = "convo_meta",
                payload = buildJsonObject { put("title", "child"); put("parent_convo_id", "p1") }))
            val afterCreate = awaitItem()
            assertEquals(listOf("p1:sub:a1"), afterCreate.map { it.id })
            assertEquals("running", afterCreate.first().sessionState)
            store.applyJournal(ev(3, convo = "p1:sub:a1", type = "session_status", payload = buildJsonObject { put("state", "done") }))
            assertEquals("done", awaitItem().first().sessionState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // MARK: applyJournalBatch (matron-apple #85 port)

    @Test
    fun batchAppliesAllAndAdvancesCursor() = runBlocking {
        val store = makeStore()
        val applied = store.applyJournalBatch((1L..5L).map { ev(it) })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), applied.map { it.seq })
        assertEquals(5L, store.cursor())
        assertEquals(5, store.events("c1").size)
    }

    @Test
    fun batchSkipsDuplicatesAndReturnsOnlyApplied() = runBlocking {
        val store = makeStore()
        store.applyJournal(ev(1))
        store.applyJournal(ev(2))
        val applied = store.applyJournalBatch(listOf(ev(1), ev(2), ev(3), ev(4)))
        assertEquals(listOf(3L, 4L), applied.map { it.seq })
        assertEquals(4L, store.cursor())
        assertEquals(4, store.events("c1").size)
    }

    @Test
    fun batchWriteFailureRollsBackTheWholeBatch() = runBlocking {
        val store = makeStore()
        store.failApplyForTesting = { it == 3L }
        val thrown = runCatching { store.applyJournalBatch(listOf(ev(1), ev(2), ev(3))) }.exceptionOrNull()
        assertTrue(thrown is JournalStoreWriteException)
        // The cursor-only-advances-with-the-write invariant must hold for the
        // whole batch: frames 1-2 committed nothing, so a reconnect replays
        // all three.
        assertEquals(0L, store.cursor())
        assertTrue(store.events("c1").isEmpty())
    }

    @Test
    fun batchConfirmsAttemptedOutboxSendsInSameTransaction() = runBlocking {
        val store = makeStore()
        store.outboxInsert("local-1", "c1", "queued body", now = 1)
        store.outboxMarkAttempt("local-1")
        val applied = store.applyJournalBatch(
            listOf(ev(1, sender = "user:dan", payload = body("queued body"))),
        )
        assertEquals(1, applied.size)
        assertTrue(store.outboxRows("c1").isEmpty())
    }

    @Test
    fun batchUnreadCountsMatchPerFrameApplication() = runBlocking {
        val batched = makeStore()
        val perFrame = makeStore()
        val events = (1L..6L).map { ev(it) }
        batched.applyJournalBatch(events)
        events.forEach { perFrame.applyJournal(it) }
        assertEquals(
            perFrame.conversations(now = 10_000).single().unreadCount,
            batched.conversations(now = 10_000).single().unreadCount,
        )
    }

    @Test
    fun emptyBatchIsANoOp() = runBlocking {
        val store = makeStore()
        assertTrue(store.applyJournalBatch(emptyList()).isEmpty())
        assertEquals(0L, store.cursor())
    }

    /// Ports matron-apple's `testSnapshotAndConvoMetaRecordTheOwningBox`.
    @Test
    fun snapshotAndConvoMetaRecordTheOwningBox() = runBlocking {
        val store = makeStore()
        store.applyColdSnapshot(
            listOf(
                ConvoSummaryDTO("c1", "Fix the parser", "running", 5, "", 1, agentDeviceID = 7),
                ConvoSummaryDTO("c2", "No box", "running", 6, "", 1),
            ),
            headSeq = 6,
        )

        assertEquals(7L, store.conversation("c1")?.agentDeviceID)
        assertNull(store.conversation("c2")?.agentDeviceID)

        // A later snapshot that omits the field must not clear what we know.
        store.refreshSummaries(
            listOf(ConvoSummaryDTO("c1", "Fix the parser", "running", 7, "", 1)),
        )
        assertEquals(7L, store.conversation("c1")?.agentDeviceID)

        // A live convo_meta teaches the linkage for a convo we have never seen.
        store.applyJournal(
            ev(
                8, convo = "c3", type = "convo_meta",
                payload = buildJsonObject { put("title", "Brand new"); put("agent_device_id", 9) },
            )
        )
        assertEquals(9L, store.conversation("c3")?.agentDeviceID)

        // Re-pointing IS allowed: a session resumed on another box legitimately
        // changes owner, unlike parent_convo_id which is immutable.
        store.applyJournal(
            ev(
                9, convo = "c3", type = "convo_meta",
                payload = buildJsonObject { put("title", "Brand new"); put("agent_device_id", 11) },
            )
        )
        assertEquals(11L, store.conversation("c3")?.agentDeviceID)
    }

    /// Ports matron-apple's `testAgentRosterMirrorsSnapshotAndLiveRenames`.
    @Test
    fun agentRosterMirrorsSnapshotAndLiveRenames() = runBlocking {
        val store = makeStore()
        assertTrue(store.agentNames().isEmpty())

        store.replaceAgents(listOf(AgentDTO(7, "dev-y"), AgentDTO(9, "dev-z")))
        assertEquals(mapOf(7L to "dev-y", 9L to "dev-z"), store.agentNames())

        // Wholesale replace: a box revoked server-side disappears here too.
        store.replaceAgents(listOf(AgentDTO(7, "dev-y")))
        assertEquals(mapOf(7L to "dev-y"), store.agentNames())

        // An empty list is "this server doesn't say", not "you have no boxes".
        store.replaceAgents(emptyList())
        assertEquals(mapOf(7L to "dev-y"), store.agentNames())

        // A live rename patches one row without a re-snapshot.
        store.renameAgent(7, "dev-yellow")
        assertEquals(mapOf(7L to "dev-yellow"), store.agentNames())

        // A rename for a box we have never seen inserts it.
        store.renameAgent(12, "dev-new")
        assertEquals("dev-new", store.agentNames()[12])
    }

    /// Ports matron-apple's `testAgentNamesStreamRefiresOnRename`: the roster
    /// needs an observation of its own — Room only re-fires a Flow for the
    /// tables its query reads, and `conversationsFlow()` never reads `agent`,
    /// so a `device_meta` rename has to reach chip labels through this flow.
    @Test
    fun agentNamesFlowRefiresOnRename() = runBlocking {
        val store = makeStore()
        store.replaceAgents(listOf(AgentDTO(7, "dev-y"), AgentDTO(9, "dev-z")))
        store.agentNamesFlow().test {
            assertEquals(mapOf(7L to "dev-y", 9L to "dev-z"), awaitItem())
            store.renameAgent(7, "dev-yellow")
            assertEquals(mapOf(7L to "dev-yellow", 9L to "dev-z"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
