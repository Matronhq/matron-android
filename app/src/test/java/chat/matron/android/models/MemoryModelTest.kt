package chat.matron.android.models

import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The memory wire shape (protocol.md "Memories") and the form rules the
/// journal enforces, mirrored client-side.
class MemoryModelTest {
    companion object {
        const val MEMORY_JSON = """{"id":"me_cbf8","user_id":1,"name":"eric-fatima-last-resort","type":"feedback",
            "description":"Use eric and fatima only as a last resort.","body":"**Why:** one Codex account.",
            "origin_convo_id":"de14","origin_device_id":65,"origin_private":false,"created_by":"agent","updated_by":"user",
            "created_at":1790550000000,"updated_at":1790560000000}"""
    }

    private fun obj(text: String) = parseJsonObjectOrNull(text)!!

    @Test
    fun parsesTheJournalRow() {
        val m = Memory.fromJson(obj(MEMORY_JSON))
        assertNotNull(m); m!!
        assertEquals("me_cbf8", m.id); assertEquals("eric-fatima-last-resort", m.name)
        assertEquals(MemoryType.FEEDBACK, m.type)
        assertEquals("Use eric and fatima only as a last resort.", m.description)
        assertEquals("**Why:** one Codex account.", m.body)
        assertEquals("de14", m.originConvoID)
        assertEquals(ItemAuthor.AGENT, m.createdBy); assertEquals(ItemAuthor.USER, m.updatedBy)
        assertEquals(Instant.ofEpochMilli(1790550000000), m.createdAt)
        assertEquals(Instant.ofEpochMilli(1790560000000), m.updatedAt)
    }

    @Test
    fun minimalRowDefaultsTheOptionalFields() {
        val m = Memory.fromJson(obj("""{"id":"me_1","name":"a","type":"user","description":"d","created_at":1,"updated_at":2}"""))!!
        assertEquals("", m.body); assertNull(m.originConvoID)
        assertEquals(ItemAuthor.AGENT, m.createdBy); assertEquals(ItemAuthor.AGENT, m.updatedBy)
    }

    @Test
    fun malformedRowsAreNull() {
        assertNull(Memory.fromJson(obj("""{"id":"me_1","name":"a","type":"rule","description":"d","created_at":1,"updated_at":2}""")))
        assertNull(Memory.fromJson(obj("""{"id":"me_1","type":"user","description":"d","created_at":1,"updated_at":2}""")))
        assertNull(Memory.fromJson(obj("""{"id":"me_1","name":"a","type":"user","description":"d","updated_at":2}""")))
    }

    @Test
    fun formErrorMirrorsTheJournalRules() {
        assertNull(Memory.formError("avoid-eric", "d", ""))
        assertNull(Memory.formError("a".repeat(64), "d", "é".repeat(4096)))
        assertTrue(Memory.formError("Bad Name", "d", "")!!.contains("lowercase"))
        assertTrue(Memory.formError("a".repeat(65), "d", "")!!.contains("lowercase"))
        assertTrue(Memory.formError("ok", "   ", "")!!.contains("required"))
        assertTrue(Memory.formError("ok", "a".repeat(201), "")!!.contains("200"))
        assertTrue(Memory.formError("ok", "a\nb", "")!!.contains("single line"))
        assertTrue(Memory.formError("ok", "a b", "")!!.contains("single line"))
        assertTrue(Memory.formError("ok", "d", "é".repeat(4096) + "a")!!.contains("8 KB"))
    }

    @Test
    fun labels() {
        assertEquals("How to work", memoryTypeLabel(MemoryType.FEEDBACK))
        assertEquals("About you", memoryTypeLabel(MemoryType.USER))
        assertEquals("you", memoryAuthorLabel(ItemAuthor.USER))
        assertEquals("an agent", memoryAuthorLabel(ItemAuthor.AGENT))
    }
}
