package chat.matron.android.journal

import androidx.test.core.app.ApplicationProvider
import chat.matron.android.journal.db.MatronDatabase
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/// Pins the two read-only queries behind the media & links browser: type
/// filtering, newest-first ordering, per-conversation isolation (sub-chats
/// excluded), and the LIKE '%http%' link prefilter. Ported from matron-apple's
/// `MediaBrowserQueryTests` (apple #142); in-memory Room under Robolectric per
/// the JournalStoreTest precedent.
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MediaBrowserQueryTest {
    private fun makeStore(): JournalStore {
        val db = MatronDatabase.inMemory(ApplicationProvider.getApplicationContext())
        return JournalStore(db, ownSender = "user:dan")
    }

    private fun event(
        seq: Long,
        convo: String = "c1",
        sender: String = "agent:dev-2",
        type: String = "text",
        payload: JsonObject = buildJsonObject { put("body", "hi") },
    ) = JournalEvent(seq, convo, Instant.ofEpochSecond(seq), sender, type, payload)

    /// Ports apple #142 `testAttachmentEventsFiltersTypesAndOrdersNewestFirst`.
    @Test
    fun attachmentEventsFiltersTypesAndOrdersNewestFirst() = runBlocking {
        val store = makeStore()
        store.insertHistory(
            listOf(
                event(1, type = "image", payload = buildJsonObject { put("blob_ref", "b1") }),
                event(2, type = "text", payload = buildJsonObject { put("body", "not an attachment") }),
                event(3, type = "file", payload = buildJsonObject { put("blob_ref", "b3"); put("name", "a.pdf") }),
                event(4, type = "tool_output", payload = buildJsonObject { put("blob_ref", "b4") }),
                event(5, type = "image", payload = buildJsonObject { put("expired", true) }),
            )
        )
        val result = store.attachmentEvents("c1")
        assertEquals("images+files only, newest first", listOf(5L, 3L, 1L), result.map { it.seq })
        assertEquals(listOf("image", "file", "image"), result.map { it.type })
    }

    /// Ports apple #142 `testAttachmentEventsIsolatesConversations`.
    @Test
    fun attachmentEventsIsolatesConversations() = runBlocking {
        val store = makeStore()
        store.insertHistory(
            listOf(
                event(1, type = "image", payload = buildJsonObject { put("blob_ref", "b1") }),
                event(2, convo = "c1:sub:x", type = "image", payload = buildJsonObject { put("blob_ref", "b2") }),
                event(3, convo = "c2", type = "file", payload = buildJsonObject { put("blob_ref", "b3"); put("name", "z") }),
            )
        )
        assertEquals(
            "a parent chat must not pool its sub-chats' media",
            listOf(1L),
            store.attachmentEvents("c1").map { it.seq },
        )
    }

    /// Ports apple #142 `testLinkCandidateEventsPrefilterAndOrdering`.
    @Test
    fun linkCandidateEventsPrefilterAndOrdering() = runBlocking {
        val store = makeStore()
        store.insertHistory(
            listOf(
                event(1, payload = buildJsonObject { put("body", "see https://example.com/a") }),
                event(2, payload = buildJsonObject { put("body", "no links here") }),
                event(3, payload = buildJsonObject { put("body", "also http://plain.example") }),
                event(4, type = "image", payload = buildJsonObject { put("blob_ref", "b"); put("caption", "https://in-caption.example") }),
                event(5, convo = "c2", payload = buildJsonObject { put("body", "https://other-convo.example") }),
            )
        )
        val result = store.linkCandidateEvents("c1")
        assertEquals(
            "text events with an http substring, this convo only, newest first",
            listOf(3L, 1L),
            result.map { it.seq },
        )
    }
}
