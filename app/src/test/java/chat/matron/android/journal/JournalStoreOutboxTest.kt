package chat.matron.android.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import chat.matron.android.journal.db.OutboxEntity
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Outbox table semantics (offline send queue). Ported from matron-apple's
/// `JournalOutboxStoreTests`. The outbox holds text sends that couldn't be
/// delivered yet; rows survive relaunch AND the `snapshot_required` mirror
/// wipe (a replay-gap wipe must not eat the user's unsent messages). Delivery
/// confirmation deletes a row when the own-text journal frame lands.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalStoreOutboxTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun makeStore() = JournalStore(MatronDatabase.inMemory(context), ownSender = "user:dan")

    private fun ownText(seq: Long, body: String, sender: String = "user:dan") = JournalEvent(
        seq = seq, convoID = "c1", ts = Instant.ofEpochMilli(seq * 1000), sender = sender,
        type = JournalEventType.TEXT, payload = buildJsonObject { put("body", body) },
    )

    @Test
    fun insertAndFetchPendingFIFO() = runBlocking {
        val store = makeStore()
        store.outboxInsert("a", "c1", "first", now = 1)
        store.outboxInsert("b", "c1", "second", now = 2)
        store.outboxInsert("c", "c2", "third", now = 3)
        val pending = store.outboxPending()
        assertEquals("flush order is FIFO by creation", listOf("a", "b", "c"), pending.map { it.localID })
        assertTrue(pending.all { it.state == OutboxEntity.STATE_QUEUED })
        assertEquals(0, pending.first().attempts)
    }

    @Test
    fun insertSameLocalIDIsIdempotent() = runBlocking {
        val store = makeStore()
        store.outboxInsert("a", "c1", "hi")
        store.outboxInsert("a", "c1", "hi")
        assertEquals(1, store.outboxPending().size)
    }

    @Test
    fun markAttemptIncrements() = runBlocking {
        val store = makeStore()
        store.outboxInsert("a", "c1", "hi")
        store.outboxMarkAttempt("a")
        store.outboxMarkAttempt("a")
        assertEquals(2, store.outboxPending().first().attempts)
    }

    @Test
    fun markFailedAndRequeue() = runBlocking {
        val store = makeStore()
        store.outboxInsert("a", "c1", "hi")
        store.outboxMarkFailed("a", "rejected")
        val failed = store.outboxRows("c1").first()
        assertEquals(OutboxEntity.STATE_FAILED, failed.state)
        assertEquals("rejected", failed.lastError)
        // Failed rows are NOT part of the automatic flush set…
        assertTrue(store.outboxPending().isEmpty())
        // …until an explicit requeue (tap-to-retry) puts them back.
        store.outboxRequeue("a")
        assertEquals(listOf("a"), store.outboxPending().map { it.localID })
    }

    @Test
    fun deleteFirstMatchingPrefersOldestAttemptedRow() = runBlocking {
        val store = makeStore()
        store.outboxInsert("old", "c1", "same", now = 1)
        store.outboxInsert("new", "c1", "same", now = 2)
        store.outboxMarkAttempt("old")
        store.outboxMarkAttempt("new")
        // Delivery confirmation for identical bodies retires the OLDEST
        // attempted copy (FIFO, mirrors echo retirement).
        assertEquals("old", store.outboxDeleteFirstMatching("c1", "same"))
        assertEquals(listOf("new"), store.outboxRows("c1").map { it.localID })
    }

    @Test
    fun deleteFirstMatchingIgnoresUnattemptedRows() = runBlocking {
        val store = makeStore()
        // Never-attempted rows can't be the row a journal frame confirms —
        // deleting one would silently eat a message that was never sent
        // (e.g. same body sent from another device while this one queued).
        store.outboxInsert("a", "c1", "hi")
        assertNull(store.outboxDeleteFirstMatching("c1", "hi"))
        assertEquals(1, store.outboxRows("c1").size)
    }

    @Test
    fun deleteFirstMatchingPrefersQueuedOverFailed() = runBlocking {
        val store = makeStore()
        store.outboxInsert("failed", "c1", "same", now = 1)
        store.outboxInsert("queued", "c1", "same", now = 2)
        store.outboxMarkAttempt("failed")
        store.outboxMarkAttempt("queued")
        store.outboxMarkFailed("failed", "rejected")
        // A delivered copy's ack can't retire an undelivered one: the queued
        // copy goes first even though the failed one is older.
        assertEquals("queued", store.outboxDeleteFirstMatching("c1", "same"))
        // When only the failed copy remains, this own-row IS its successful
        // retry landing.
        assertEquals("failed", store.outboxDeleteFirstMatching("c1", "same"))
    }

    @Test
    fun deleteFirstMatchingScopedToConvoAndBody() = runBlocking {
        val store = makeStore()
        store.outboxInsert("a", "c1", "hi")
        store.outboxMarkAttempt("a")
        assertNull(store.outboxDeleteFirstMatching("c2", "hi"))
        assertNull(store.outboxDeleteFirstMatching("c1", "other"))
        assertEquals("a", store.outboxDeleteFirstMatching("c1", "hi"))
    }

    @Test
    fun wipePreservesOutbox() = runBlocking {
        val store = makeStore()
        store.applyJournal(ownText(1, "hi", sender = "agent:dev-2"))
        store.outboxInsert("a", "c1", "queued")
        store.wipe()
        assertEquals(0L, store.cursor())
        assertTrue(store.events("c1").isEmpty())
        assertEquals(
            "snapshot_required wipe must not eat unsent messages",
            listOf("a"), store.outboxPending().map { it.localID },
        )
    }

    @Test
    fun wipeOutboxClears() = runBlocking {
        val store = makeStore()
        store.outboxInsert("a", "c1", "queued")
        store.wipeOutbox()
        assertTrue(store.outboxPending().isEmpty())
    }

    @Test
    fun applyJournalDeletesOutboxRowAtomically() = runBlocking {
        // Delivery-confirmed deletion lives INSIDE applyJournal's transaction
        // so the confirming row and the outbox delete commit together.
        val store = makeStore()
        store.outboxInsert("A", "c1", "hello")
        store.outboxMarkAttempt("A")
        assertTrue(store.applyJournal(ownText(1, "hello")))
        assertTrue(store.outboxRows("c1").isEmpty())
    }

    @Test
    fun insertHistoryConfirmsPostSnapshotOutboxRow() = runBlocking {
        // After a snapshot_required wipe the cursor jumps past the confirming
        // frames — the history refill must confirm attempted rows instead.
        val store = makeStore()
        store.outboxInsert("A", "c1", "hello", now = 1000)
        store.outboxMarkAttempt("A")
        store.insertHistory(listOf(ownText(2, "hello"))) // ts = 2000, after the row
        assertTrue(store.outboxRows("c1").isEmpty())
    }

    @Test
    fun oldHistoryEventDoesNotConfirmFreshSend() = runBlocking {
        // An identical body sent LONG AGO replayed by pagination must not eat
        // a fresh queued send: the confirming event can't predate its row.
        val store = makeStore()
        store.outboxInsert("A", "c1", "hello", now = 5000)
        store.outboxMarkAttempt("A")
        store.insertHistory(listOf(ownText(1, "hello"))) // ts = 1000, before the row
        assertEquals(listOf("A"), store.outboxRows("c1").map { it.localID })
    }

    @Test
    fun insertHistoryFromOtherSenderKeepsOutboxRow() = runBlocking {
        val store = makeStore()
        store.outboxInsert("A", "c1", "hello", now = 1000)
        store.outboxMarkAttempt("A")
        store.insertHistory(listOf(ownText(2, "hello", sender = "agent:a")))
        assertEquals(listOf("A"), store.outboxRows("c1").map { it.localID })
    }

    @Test
    fun applyJournalFromOtherSenderKeepsOutboxRow() = runBlocking {
        val store = makeStore()
        store.outboxInsert("A", "c1", "hello")
        store.outboxMarkAttempt("A")
        assertTrue(store.applyJournal(ownText(1, "hello", sender = "agent:a")))
        assertEquals(listOf("A"), store.outboxRows("c1").map { it.localID })
    }

    @Test
    fun replayedDuplicateFrameDoesNotDeleteOutboxRow() = runBlocking {
        // applyJournal's outbox deletion sits BEHIND the seq > cursor guard: a
        // replayed/duplicate own-text frame is a no-op and must not retire a
        // live queued row (post-wipe cold start jumps the cursor to
        // /snapshot's headSeq, so history is never re-applied through
        // applyJournal either — pagination uses insertHistory, which never
        // touches the outbox).
        val store = makeStore()
        val own = ownText(1, "dup")
        assertTrue(store.applyJournal(own)) // cursor → 1
        store.outboxInsert("Q", "c1", "dup")
        store.outboxMarkAttempt("Q")
        assertFalse("duplicate frame is a no-op", store.applyJournal(own))
        assertEquals(
            "a replayed frame must not retire a live queued send",
            listOf("Q"), store.outboxRows("c1").map { it.localID },
        )
    }

    @Test
    fun outboxFlowScopedToConvoAndReflectsChanges() = runBlocking {
        val store = makeStore()
        store.outboxInsert("a", "c1", "hi")
        store.outboxInsert("x", "c2", "other")
        assertEquals(listOf("a"), store.outboxFlow("c1").first().map { it.localID })
        store.outboxInsert("b", "c1", "again")
        assertEquals(listOf("a", "b"), store.outboxFlow("c1").first().map { it.localID })
    }
}
