package chat.matron.android.search

import chat.matron.android.journal.JournalSearchChat
import chat.matron.android.journal.JournalSearchHit
import chat.matron.android.journal.JournalSearching
import chat.matron.android.viewmodels.FakeSearchService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant

/// [ServerFirstSearchService]: the journal answers; the local index only
/// when it can't.
class ServerFirstSearchServiceTest {
    private class ScriptedRemote : JournalSearching {
        var chats: List<JournalSearchChat> = emptyList()
        var recent: List<JournalSearchHit> = emptyList()
        var down: IOException? = null
        data class ChatCall(val query: String, val limit: Int, val excludeSubagents: Boolean)
        data class RecentCall(val query: String, val convoID: String?, val limit: Int, val excludeSubagents: Boolean)
        val chatCalls = mutableListOf<ChatCall>()
        val recentCalls = mutableListOf<RecentCall>()

        override suspend fun searchChats(query: String, limit: Int, excludeSubagents: Boolean): List<JournalSearchChat> {
            chatCalls.add(ChatCall(query, limit, excludeSubagents))
            down?.let { throw it }
            return chats
        }

        override suspend fun searchRecent(
            query: String,
            convoID: String?,
            limit: Int,
            excludeSubagents: Boolean,
        ): List<JournalSearchHit> {
            recentCalls.add(RecentCall(query, convoID, limit, excludeSubagents))
            down?.let { throw it }
            return recent
        }
    }

    private fun hit(id: String, room: String, ts: Long = 1) =
        SearchHit(id = id, roomID = room, sender = "agent:x", timestamp = Instant.ofEpochSecond(ts), snippet = "")

    @Test
    fun groupedResultsComeFromTheServerWithThePreviewCutFromTheExcerpt() = runTest {
        val remote = ScriptedRemote().apply {
            chats = listOf(
                JournalSearchChat(
                    convoID = "c1",
                    count = 5,
                    isExact = true,
                    top = JournalSearchHit("c1", 1_146_809, Instant.ofEpochSecond(1_790_805_900), "user:alice"),
                    excerpt = "Could we keep the release notes short this time around",
                ),
            )
        }
        val local = FakeSearchService(hits = listOf(hit("1", "elsewhere")))
        val groups = ServerFirstSearchService(remote, local).queryGrouped("release notes", limit = 200)
        assertEquals(1, groups.size)
        assertEquals("c1", groups[0].roomID)
        assertEquals(5, groups[0].count)
        assertEquals("1146809", groups[0].newestHit.id)
        assertEquals("user:alice", groups[0].newestHit.sender)
        assertTrue(groups[0].newestHit.snippet, groups[0].newestHit.snippet.contains("<mark>release notes</mark>"))
        assertEquals(ScriptedRemote.ChatCall("release notes", 50, true), remote.chatCalls.single())
        assertEquals("the local index is not asked when the server answers", 0, local.queryCalls)
    }

    @Test
    fun groupedResultsFallBackToTheLocalIndexWithoutSubagentChats() = runTest {
        val remote = ScriptedRemote().apply { down = IOException("offline") }
        val local = FakeSearchService(hits = listOf(hit("2", "main:sub:a1", ts = 2), hit("1", "main")))
        val groups = ServerFirstSearchService(remote, local).queryGrouped("notes", limit = 50)
        assertEquals(listOf("main"), groups.map { it.roomID })
    }

    @Test
    fun withoutALocalIndexAServerFailureIsThrown() = runTest {
        val down = IOException("offline")
        val remote = ScriptedRemote().apply { this.down = down }
        try {
            ServerFirstSearchService(remote, local = null).queryGrouped("notes", limit = 50)
            fail("expected the server error")
        } catch (e: IOException) {
            assertSame(down, e)
        }
    }

    @Test
    fun inChatSearchAsksTheServerForTheConversation() = runTest {
        val remote = ScriptedRemote().apply {
            recent = listOf(
                JournalSearchHit("r", 30, Instant.ofEpochSecond(30), "agent:x"),
                JournalSearchHit("r", 10, Instant.ofEpochSecond(10), "user:alice"),
            )
        }
        val hits = ServerFirstSearchService(remote, FakeSearchService()).query("notes ", "r", 500)
        assertEquals(listOf("30", "10"), hits.map { it.id })
        assertEquals(ScriptedRemote.RecentCall("notes ", "r", 500, true), remote.recentCalls.single())
    }

    @Test
    fun inChatSearchFallsBackToTheLocalIndex() = runTest {
        val remote = ScriptedRemote().apply { down = IOException("offline") }
        val local = FakeSearchService(hits = listOf(hit("7", "r"), hit("8", "other")))
        val hits = ServerFirstSearchService(remote, local).query("notes", "r", 10)
        assertEquals(listOf("7"), hits.map { it.id })
    }

    @Test
    fun unscopedRecentFallbackDropsSubagentChats() = runTest {
        val remote = ScriptedRemote().apply { down = IOException("offline") }
        val local = FakeSearchService(hits = listOf(hit("7", "r"), hit("8", "r:sub:a1")))
        val hits = ServerFirstSearchService(remote, local).query("notes", 10)
        assertEquals(listOf("7"), hits.map { it.id })
        assertEquals(null, remote.recentCalls.single().convoID)
    }

    @Test
    fun nothingSearchableNeverAsksTheServer() = runTest {
        val remote = ScriptedRemote()
        val service = ServerFirstSearchService(remote, local = null)
        assertEquals(emptyList<SearchChatHit>(), service.queryGrouped("  ++ ", 50))
        assertEquals(emptyList<SearchHit>(), service.query("", "r", 50))
        assertTrue(remote.chatCalls.isEmpty() && remote.recentCalls.isEmpty())
    }

    @Test
    fun writesGoToTheLocalIndex() = runTest {
        val service = ServerFirstSearchService(ScriptedRemote(), local = null)
        // No index: writes are dropped, bookkeeping reads answer "nothing".
        service.index("r", "1", "user:alice", Instant.EPOCH, "hello")
        assertEquals(0, service.eventCount("r"))
        assertEquals(false, service.backfillComplete("r"))
    }
}
