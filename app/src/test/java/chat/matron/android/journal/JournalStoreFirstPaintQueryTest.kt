package chat.matron.android.journal

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Pins what ONE chat-list read costs in SQL statements, and which tables
/// those statements touch, against a mirror whose conversations are all
/// stale (newest message a >24 h live-log tool_output). This is the
/// first-paint query: `JournalStore.conversations()` / `conversationsFlow()`
/// feed `JournalChatService.chatSummaries()`.
///
/// Before the v7 columns (apple #212) the same read over 200 stale
/// conversations issued 401 queries, 400 of them on `event` (a `MAX(seq)`
/// plus a row fetch per stale row). It is now exactly one query, on
/// `conversation` alone, however stale the list is.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class JournalStoreFirstPaintQueryTest {
    /// Counts SELECTs only. Room's invalidation tracker also runs a
    /// `room_table_modification_log` refresh (inside its own transaction)
    /// after the seeding writes; that is tracker bookkeeping, not the read.
    private class Counter {
        val queries = AtomicInteger(0)
        val eventReads = AtomicInteger(0)
        var recording = false
        fun record(sql: String) {
            if (!recording) return
            val lower = sql.lowercase()
            if (!lower.startsWith("select") || lower.contains("room_table_modification_log")) return
            queries.incrementAndGet()
            if (lower.contains(" event")) eventReads.incrementAndGet()
        }
    }

    @Test
    fun staleConversationsReadIsOneConversationQueryAndNoEventReads() = runBlocking {
        val counter = Counter()
        val direct = Executor { it.run() }
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MatronDatabase::class.java,
        ).setQueryCallback({ sql, _ -> counter.record(sql) }, direct).build()
        val store = JournalStore(db, ownSender = "user:dan")
        val n = 200
        for (i in 1..n) {
            store.applyJournal(
                JournalEvent(
                    seq = i.toLong(), convoID = "c$i", ts = Instant.ofEpochMilli(i * 1000L),
                    sender = "agent:dev-2", type = "tool_output",
                    payload = buildJsonObject {
                        put("command", "make test $i"); put("live_log", true); put("snippet", "out")
                    },
                )
            )
        }
        counter.recording = true
        val now = n * 1000L + 25 * 3600 * 1000L
        val rows = store.conversations(now = now)
        counter.recording = false
        assertEquals(n, rows.size)
        assertEquals("$ make test 1", rows.last().snippet)
        println(
            "FIRST-PAINT READ over $n stale conversations: " +
                "${counter.queries.get()} queries, ${counter.eventReads.get()} of them reading `event`",
        )
        assertEquals("the list read must not touch `event`", 0, counter.eventReads.get())
        assertEquals("one conversation query, regardless of how many rows are stale", 1, counter.queries.get())
    }
}
