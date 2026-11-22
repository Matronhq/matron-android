package chat.matron.android.journal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class JournalSearchDecodingTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun decodesChatsModeRows() {
        val chats = JournalSearchDecoding.chats(
            obj(
                """{"chats":[
                  {"convo_id":"c","title":"T","parent_convo_id":null,"parent_title":null,"count":2,"exact":true,"live":false,
                   "mission_num":null,"top":{"seq":42,"ts":1790805900902,"sender":"user:alice","excerpt":"…release notes…"}},
                  {"convo_id":"d","count":1,"top":{"seq":7,"ts":1000,"sender":"agent:x"}},
                  {"convo_id":"no-top","count":1},
                  {"count":3,"top":{"seq":1,"ts":1,"sender":"x"}}
                ]}""",
            ),
        )
        assertEquals(listOf("c", "d"), chats.map { it.convoID })
        assertEquals(42L, chats[0].top.seq)
        assertEquals("c", chats[0].top.convoID)
        assertEquals(Instant.ofEpochMilli(1_790_805_900_902), chats[0].top.ts)
        assertEquals("…release notes…", chats[0].excerpt)
        assertTrue(chats[0].isExact)
        assertEquals(false, chats[1].isExact)
        assertEquals("", chats[1].excerpt)
    }

    @Test
    fun decodesRecentModeHits() {
        val hits = JournalSearchDecoding.hits(
            obj(
                """{"hits":[
                  {"convo_id":"r","seq":30,"ts":30000,"sender":"agent:x","mission_num":null},
                  {"convo_id":"r","seq":"bad","ts":1,"sender":"x"},
                  {"seq":10,"ts":10000,"sender":"user:alice"}
                ]}""",
            ),
        )
        assertEquals(listOf(JournalSearchHit("r", 30, Instant.ofEpochSecond(30), "agent:x")), hits)
    }

    @Test
    fun aMissingArrayIsNoResults() {
        assertEquals(emptyList<JournalSearchChat>(), JournalSearchDecoding.chats(obj("""{"error":"x"}""")))
        assertEquals(emptyList<JournalSearchHit>(), JournalSearchDecoding.hits(null))
    }
}
