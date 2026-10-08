package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.models.ItemAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The `memory` marker (protocol.md "Memories → Marker event").
class MemoryMarkerEventTest {
    private fun obj(text: String) = parseJsonObjectOrNull(text)!!

    @Test
    fun fullPayloadParses() {
        val m = MemoryMarkerEvent.parse(obj("""{"memory_id":"me_1","name":"avoid-atlas","type":"feedback",
            "description":"Never use atlas.","action":"saved","created":true,"by":"agent"}"""))!!
        assertEquals("me_1", m.memoryID); assertEquals(MemoryMarkerEvent.Action.SAVED, m.action)
        assertTrue(m.created); assertEquals(ItemAuthor.AGENT, m.by); assertEquals("avoid-atlas", m.name)
    }

    @Test
    fun privacyStrippedPayloadStillParsesWithoutAName() {
        val m = MemoryMarkerEvent.parse(obj("""{"memory_id":"me_2","action":"deleted","created":false,"by":"user"}"""))!!
        assertEquals(MemoryMarkerEvent.Action.DELETED, m.action); assertFalse(m.created)
        assertEquals(ItemAuthor.USER, m.by); assertNull(m.name)
    }

    @Test
    fun junkIsNull() {
        assertNull(MemoryMarkerEvent.parse(obj("""{"action":"saved"}""")))
        assertNull(MemoryMarkerEvent.parse(obj("""{"memory_id":"me_1","action":"renamed"}""")))
    }
}
