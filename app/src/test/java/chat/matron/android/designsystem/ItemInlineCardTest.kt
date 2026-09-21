package chat.matron.android.designsystem

import chat.matron.android.events.ItemMarkerEvent
import chat.matron.android.models.ItemAuthor
import chat.matron.android.models.ItemAwaiting
import chat.matron.android.models.ItemKind
import chat.matron.android.models.ItemResolution
import chat.matron.android.models.TrackerAttachment
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Pins the inline card's pure UI logic — the port of matron-apple's
/// `ItemInlineCardSnapshotTests` variants (#186) and `ItemInlineCardTests`
/// (#210) as pure-function tests, this repo's convention.
class ItemInlineCardTest {
    private fun marker(
        action: ItemMarkerEvent.Action,
        kind: ItemKind = ItemKind.QUESTION,
        by: ItemAuthor = ItemAuthor.AGENT,
        awaiting: ItemAwaiting? = ItemAwaiting.USER,
        resolution: ItemResolution? = null,
        comment: ItemMarkerEvent.Comment? = null,
    ) = ItemMarkerEvent(
        itemID = "it_1", num = 12, kind = kind, title = "Which auth library?", action = action, by = by,
        awaiting = awaiting, resolution = resolution, comment = comment,
    )

    @Test
    fun createdAndClosedAreCardsTheRestAreNotes() {
        assertEquals(ItemInlineShape.CARD, itemInlineShape(marker(ItemMarkerEvent.Action.CREATED)))
        assertEquals(ItemInlineShape.CARD, itemInlineShape(marker(ItemMarkerEvent.Action.CLOSED)))
        assertEquals(ItemInlineShape.NOTE, itemInlineShape(marker(ItemMarkerEvent.Action.COMMENTED)))
        assertEquals(ItemInlineShape.NOTE, itemInlineShape(marker(ItemMarkerEvent.Action.REOPENED)))
    }

    @Test
    fun pillNamesWhoseCourtOrDone() {
        assertEquals("Needs you" to true, itemInlinePill(marker(ItemMarkerEvent.Action.CREATED)))
        assertEquals("With the agent" to false, itemInlinePill(marker(ItemMarkerEvent.Action.CREATED, awaiting = ItemAwaiting.AGENT)))
        assertNull("an open marker awaiting nobody has no pill", itemInlinePill(marker(ItemMarkerEvent.Action.CREATED, kind = ItemKind.DECISION, awaiting = null)))
        assertEquals("Done · Answered" to false, itemInlinePill(marker(ItemMarkerEvent.Action.CLOSED, awaiting = null, resolution = ItemResolution.ANSWERED)))
        assertEquals("Done" to false, itemInlinePill(marker(ItemMarkerEvent.Action.CLOSED, awaiting = null)))
        assertEquals(
            "a closed marker is never 'needs you', whatever awaiting says",
            "Done · Done" to false, itemInlinePill(marker(ItemMarkerEvent.Action.CLOSED, awaiting = ItemAwaiting.USER, resolution = ItemResolution.DONE)),
        )
    }

    @Test
    fun noteTextNamesTheAuthorAndTheAct() {
        assertEquals("You replied on #12 · Which auth library?", itemInlineNoteText(marker(ItemMarkerEvent.Action.COMMENTED, by = ItemAuthor.USER)))
        assertEquals("Agent replied on #12 · Which auth library?", itemInlineNoteText(marker(ItemMarkerEvent.Action.COMMENTED)))
        assertEquals("You reopened #12 · Which auth library?", itemInlineNoteText(marker(ItemMarkerEvent.Action.REOPENED, by = ItemAuthor.USER)))
        assertEquals("Agent reopened #12 · Which auth library?", itemInlineNoteText(marker(ItemMarkerEvent.Action.REOPENED)))
    }

    @Test
    fun cardAccessibilityLabel() {
        assertEquals("Question 12, Which auth library?. Needs you", itemInlineCardAccessibilityLabel(marker(ItemMarkerEvent.Action.CREATED)))
        assertEquals("Task 12, Which auth library?. Done", itemInlineCardAccessibilityLabel(marker(ItemMarkerEvent.Action.CLOSED, kind = ItemKind.TASK, awaiting = null)))
    }

    // MARK: the reply block (apple #210)

    @Test
    fun replyBlockShowsOnlyWhenTheCommentHasSomething() {
        assertFalse("no comment payload at all", itemInlineHasVisibleComment(marker(ItemMarkerEvent.Action.COMMENTED)))
        assertFalse(
            "a bare status transition parses with an empty comment",
            itemInlineHasVisibleComment(marker(ItemMarkerEvent.Action.REOPENED, comment = ItemMarkerEvent.Comment("c", ""))),
        )
        assertTrue(itemInlineHasVisibleComment(marker(ItemMarkerEvent.Action.COMMENTED, comment = ItemMarkerEvent.Comment("c", "use A"))))
        val audio = TrackerAttachment(blobRef = "b", mime = "audio/m4a", name = "Voice 1.m4a", size = 1)
        assertTrue(
            "an attachment-only reply still renders a block",
            itemInlineHasVisibleComment(marker(ItemMarkerEvent.Action.COMMENTED, comment = ItemMarkerEvent.Comment("c", "", listOf(audio)))),
        )
        assertTrue(
            "a closing comment renders under the closed card too",
            itemInlineHasVisibleComment(marker(ItemMarkerEvent.Action.CLOSED, comment = ItemMarkerEvent.Comment("c", "shipped"))),
        )
    }

    @Test
    fun audioWithTranscriptShowsNameAndTranscript() {
        val a = TrackerAttachment(blobRef = "b1", mime = "audio/m4a", name = "Voice 1.m4a", size = 1, transcript = "use option A")
        assertEquals("Voice note: Voice 1.m4a — use option A", itemInlineAttachmentLine(a))
    }

    @Test
    fun audioPendingOnTheJournalShowsNameOnly() {
        // The card is a frozen marker snapshot: a present-tense "transcribing…"
        // would outlive the job.
        val a = TrackerAttachment(blobRef = "b3", mime = "audio/m4a", name = "Voice 3.m4a", size = 1, transcript = null, transcriptStatus = "pending")
        assertEquals("Voice note: Voice 3.m4a", itemInlineAttachmentLine(a))
    }

    @Test
    fun transcriptStatusRoundTripsAndFlagsFailure() {
        val a = TrackerAttachment.fromJson(buildJsonObject {
            put("blob_ref", "b"); put("mime", "audio/mp4"); put("name", "v"); put("size", 1); put("transcript_status", "failed")
        })!!
        assertEquals("failed", a.transcriptStatus)
        assertTrue(a.transcriptionFailed)
        assertEquals("failed", a.toJson()["transcript_status"]?.toString()?.trim('"'))
        val done = TrackerAttachment(blobRef = "b", mime = "audio/mp4", name = "v", size = 1, transcript = "hi", transcriptStatus = "failed")
        assertFalse("words win", done.transcriptionFailed)
    }

    @Test
    fun audioWithoutTranscriptShowsNameOnly() {
        val a = TrackerAttachment(blobRef = "b2", mime = "audio/m4a", name = "Voice 2.m4a", size = 1, transcript = null)
        assertEquals("Voice note: Voice 2.m4a", itemInlineAttachmentLine(a))
    }

    @Test
    fun imageShowsAttachmentPrefix() {
        val a = TrackerAttachment(blobRef = "b3", mime = "image/png", name = "screenshot.png", size = 1)
        assertEquals("Attachment: screenshot.png", itemInlineAttachmentLine(a))
    }

    @Test
    fun emptyNameFallsBackToGenericAttachment() {
        val a = TrackerAttachment(blobRef = "b4", mime = "application/pdf", name = "", size = 1)
        assertEquals("Attachment: attachment", itemInlineAttachmentLine(a))
    }
}
