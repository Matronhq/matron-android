package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemAwaiting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `ItemMarkerEventTests`.
class ItemMarkerEventTest {
    private fun obj(text: String) = parseJsonObjectOrNull(text)!!

    @Test
    fun parsesCommentedMarker() {
        val payload = obj(
            """{"item_id":"it_1","num":12,"kind":"question","title":"Which auth?","action":"commented",
                "by":"user","awaiting":"agent","resolution":null,
                "comment":{"id":"ic_1","body":"use A","attachments":[{"blob_ref":"b","mime":"audio/mp4","name":"v.m4a","size":1,"transcript":null}]}}""",
        )
        val m = ItemMarkerEvent.parse(payload)
        assertNotNull(m); m!!
        assertEquals(12, m.num); assertEquals(ItemMarkerEvent.Action.COMMENTED, m.action); assertEquals(ItemAuthor.USER, m.by)
        assertEquals(ItemAwaiting.AGENT, m.awaiting); assertNull(m.resolution)
        assertEquals("use A", m.comment?.body); assertTrue(m.comment!!.attachments[0].isAudio)
    }

    @Test
    fun rejectsUnknownActionOrMissingKeys() {
        assertNull(ItemMarkerEvent.parse(obj("""{"item_id":"it_1","num":1,"kind":"task","title":"t","action":"exploded","by":"agent"}""")))
        assertNull(ItemMarkerEvent.parse(obj("""{"num":1,"kind":"task","title":"t","action":"created","by":"agent"}""")))
    }

    @Test
    fun parsesUpdatedMarkerWithoutComment() {
        val m = ItemMarkerEvent.parse(obj("""{"item_id":"it_1","num":3,"kind":"task","title":"Ship it","action":"updated","by":"agent"}"""))
        assertEquals(ItemMarkerEvent.Action.UPDATED, m?.action)
        assertNull(m?.comment)
    }

    @Test
    fun commentWithoutIdIsDropped() {
        val m = ItemMarkerEvent.parse(
            obj("""{"item_id":"it_1","num":3,"kind":"task","title":"T","action":"closed","by":"agent","resolution":"done","comment":{"body":"x"}}"""),
        )
        assertNotNull(m)
        assertNull("a comment object without an id is not a comment", m!!.comment)
    }
}
