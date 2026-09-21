package chat.matron.android.models

import chat.matron.android.journal.MatronJson
import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Wire decoding of the tracker's item/comment shapes. Ported from the
/// model half of matron-apple's `ItemsAPITests`.
class TrackerItemTest {
    companion object {
        const val ITEM_JSON = """{
            "id":"it_1","user_id":1,"num":12,"kind":"question","state":"open","resolution":null,
            "awaiting":"user","rank":1024.0,"title":"Which auth?","body":"A or B","labels":["auth"],
            "links":[{"url":"https://x","title":"issue"}],
            "attachments":[{"blob_ref":"b1","mime":"image/png","name":"s.png","size":10}],
            "supersedes":null,"origin_convo_id":"c1","origin_device_id":3,"created_by":"agent",
            "idem_key":null,"created_at":1700000000000,"updated_at":1700000001000,"closed_at":null,
            "comment_count":2,"last_comment_at":1700000001000,"has_image":1
        }"""
    }

    private fun obj(text: String) = parseJsonObjectOrNull(text)!!

    @Test
    fun trackerItemDecodes() {
        val item = TrackerItem.fromJson(obj(ITEM_JSON))
        assertNotNull(item); item!!
        assertEquals("it_1", item.id); assertEquals(12, item.num); assertEquals(ItemKind.QUESTION, item.kind)
        assertEquals(ItemState.OPEN, item.state); assertNull(item.resolution); assertEquals(ItemAwaiting.USER, item.awaiting)
        assertEquals(1024.0, item.rank, 0.0); assertEquals(listOf("auth"), item.labels)
        assertEquals("https://x", item.links.first().url)
        assertEquals("b1", item.attachments.first().blobRef); assertTrue(item.attachments.first().isImage)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), item.createdAt); assertNull(item.closedAt)
        assertEquals(2, item.commentCount); assertTrue(item.hasImage); assertTrue(item.needsUser)
        assertEquals(ItemAuthor.AGENT, item.createdBy); assertEquals("c1", item.originConvoID)
        assertNull(item.missionID); assertNull(item.missionNum)
    }

    @Test
    fun trackerItemRejectsMissingOrUnknownKeys() {
        assertNull(TrackerItem.fromJson(obj(ITEM_JSON.replace("\"kind\":\"question\"", "\"kind\":\"bug\""))))
        assertNull(TrackerItem.fromJson(obj(ITEM_JSON.replace("\"num\":12,", ""))))
    }

    @Test
    fun hasImageAcceptsBooleanAndInteger() {
        assertTrue(TrackerItem.fromJson(obj(ITEM_JSON.replace("\"has_image\":1", "\"has_image\":true")))!!.hasImage)
        assertFalse(TrackerItem.fromJson(obj(ITEM_JSON.replace("\"has_image\":1", "\"has_image\":0")))!!.hasImage)
        assertFalse(TrackerItem.fromJson(obj(ITEM_JSON.replace(",\"has_image\":1", "")))!!.hasImage)
    }

    @Test
    fun missionFieldsDecodeWhenPresent() {
        val withMission = ITEM_JSON.replace("\"has_image\":1", "\"has_image\":1,\"mission_id\":\"ms_1\",\"mission_num\":61")
        val item = TrackerItem.fromJson(obj(withMission))!!
        assertEquals("ms_1", item.missionID); assertEquals(61, item.missionNum)
    }

    @Test
    fun trackerCommentDecodesStatusMeta() {
        val json = """{
            "id":"ic_1","item_id":"it_1","user_id":1,"author":"user","device_id":9,"kind":"status",
            "body":"no","attachments":[],
            "meta":{"from":{"state":"open","resolution":null,"awaiting":"user"},
                    "to":{"state":"closed","resolution":"reversed","awaiting":null}},
            "idem_key":null,"created_at":1700000002000
        }"""
        val c = TrackerComment.fromJson(obj(json))!!
        assertEquals(TrackerComment.Kind.STATUS, c.kind); assertEquals(ItemAuthor.USER, c.author)
        assertEquals(ItemResolution.REVERSED, c.statusTo?.resolution)
        assertEquals(ItemAwaiting.USER, c.statusFrom?.awaiting); assertNull(c.statusTo?.awaiting)
        assertEquals(9L, c.deviceID)
        assertEquals(Instant.ofEpochMilli(1_700_000_002_000), c.createdAt)
    }

    @Test
    fun trackerCommentWithoutMetaHasNoSnapshots() {
        val json = """{"id":"ic_2","item_id":"it_1","author":"agent","device_id":1,"kind":"comment",
            "body":"hi","attachments":[],"meta":null,"created_at":1}"""
        val c = TrackerComment.fromJson(obj(json))!!
        assertNull(c.statusFrom); assertNull(c.statusTo)
        assertNull("unknown author is rejected", TrackerComment.fromJson(obj(json.replace("\"agent\"", "\"bot\""))))
    }

    @Test
    fun attachmentTranscriptStatusAndFailure() {
        val pending = TrackerAttachment.fromJson(obj("""{"blob_ref":"b","mime":"audio/mp4","transcript_status":"pending"}"""))!!
        assertTrue(pending.isAudio); assertFalse(pending.transcriptionFailed)
        val failed = pending.copy(transcriptStatus = "failed")
        assertTrue(failed.transcriptionFailed)
        assertFalse("words present ⇒ not failed", failed.copy(transcript = "hello").transcriptionFailed)
        assertNull("blob_ref is required", TrackerAttachment.fromJson(obj("""{"mime":"audio/mp4"}""")))
    }

    @Test
    fun attachmentOutgoingJsonStripsTranscript() {
        val a = TrackerAttachment("b1", "audio/m4a", "note.m4a", 42, transcript = "secret", transcriptStatus = "done")
        val out = a.outgoingJson()
        assertNull(out["transcript"]); assertNull(out["transcript_status"])
        assertEquals("b1", out["blob_ref"]!!.toString().trim('"'))
        assertNotNull("the mirror's full shape keeps it", a.toJson()["transcript"])
    }

    @Test
    fun attachmentListRoundTripsThroughKotlinxSerialization() {
        val list = listOf(
            TrackerAttachment("b1", "image/png", "s.png", 10),
            TrackerAttachment("b2", "audio/mp4", "v.m4a", 5, transcript = "t", transcriptStatus = "done"),
        )
        val encoded = MatronJson.encodeToString(ListSerializer(TrackerAttachment.serializer()), list)
        assertTrue("wire names, not Kotlin names", encoded.contains("\"blob_ref\"") && encoded.contains("\"transcript_status\""))
        assertEquals(list, MatronJson.decodeFromString(ListSerializer(TrackerAttachment.serializer()), encoded))
    }
}
