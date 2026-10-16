package chat.matron.android.events

import chat.matron.android.chat.JournalTimelineMapper
import chat.matron.android.chat.TimelineItem
import chat.matron.android.designsystem.grantFileCaption
import chat.matron.android.designsystem.grantFileSize
import chat.matron.android.journal.parseJsonObjectOrNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The person-to-person grant cards: a hand-over (owner's and recipient's
/// side) and a mission share, as protocol.md "Contacts and grants" mints them.
class PersonGrantRequestTest {
    private fun payload(json: String): JsonObject = parseJsonObjectOrNull(json)!!

    private val handoverOut = """
        {"kind":"mission_handover","direction":"out","grant_id":"gr_1","level":"owner",
         "owner":{"user_id":"u_a","name":"alice"},"grantee":{"name":"bob","address":"bob"},
         "subject":{"kind":"mission","id":"ms_1","num":7,"title":"Checkout redesign"},
         "brief":"Where things stand: the **cart** is done.",
         "preview":{"missions":1,"milestones":4,"items":6,"comments":12,
           "attachments":[
             {"media_id":"md_1","name":"spec.pdf","mime":"application/pdf","size":1258291,"item_num":12},
             {"media_id":"md_2","name":"mock.png","mime":"image/png","size":2048,"item_num":13},
             {"media_id":"md_3","name":"notes.txt","mime":"text/plain","size":300}
           ]},
         "from_device_id":3,"from_name":"box-a","from_convo_id":"c1"}
    """.trimIndent()

    private val handoverIn = """
        {"kind":"mission_handover","direction":"in","grant_id":"gr_1","level":"owner",
         "owner":{"user_id":"u_a","name":"alice"},"grantee":{"name":"bob","address":"bob"},
         "subject":{"kind":"mission","title":"Checkout redesign"},"brief":"Over to you.",
         "preview":{"missions":1,"milestones":4,"items":6,"comments":12,"attachments":2}}
    """.trimIndent()

    @Test
    fun parsesTheOwnersHandoverCard() {
        val r = PersonGrantRequest.parse(payload(handoverOut))!!
        assertEquals(PersonGrantRequest.Kind.HANDOVER, r.kind)
        assertEquals(PersonGrantRequest.Direction.OUT, r.direction)
        assertEquals("gr_1", r.grantId)
        assertEquals("owner", r.level)
        assertEquals("alice", r.ownerName)
        assertEquals("bob", r.granteeName)
        assertEquals(PersonGrantRequest.SubjectKind.MISSION, r.subjectKind)
        assertEquals("Checkout redesign", r.subjectTitle)
        assertEquals(7, r.subjectNum)
        assertEquals("Where things stand: the **cart** is done.", r.brief)
        assertEquals("box-a", r.fromName)
        assertEquals(listOf("md_1", "md_2", "md_3"), r.attachmentIds)
        val first = r.preview.attachments.first()
        assertEquals("spec.pdf", first.name)
        assertEquals("application/pdf", first.mime)
        assertEquals(1258291L, first.size)
        assertEquals(12, first.itemNum)
        assertNull(r.preview.attachments[2].itemNum)
        assertEquals(3, r.preview.attachmentCount)
    }

    @Test
    fun ownersHandoverWording() {
        val r = PersonGrantRequest.parse(payload(handoverOut))!!
        assertEquals("Hand over a mission", r.title)
        assertEquals("Your agent asks to hand Checkout redesign to bob.", r.headline)
        assertEquals("1 mission · 4 milestones · 6 items · 12 comments · 3 files", r.preview.countsLine)
        assertEquals("Approve", r.approveLabel)
        assertNull(r.levelLabel)
        assertEquals("Reply on the tracker item to send a note back to your agent.", PersonGrantRequest.OWNER_HINT)
    }

    @Test
    fun parsesTheRecipientsHandoverCardWithACountOfFiles() {
        val r = PersonGrantRequest.parse(payload(handoverIn))!!
        assertEquals(PersonGrantRequest.Direction.IN, r.direction)
        assertEquals("Mission hand-over", r.title)
        assertEquals("alice wants to hand you Checkout redesign.", r.headline)
        assertEquals("Accept", r.approveLabel)
        assertTrue(r.preview.attachments.isEmpty())
        assertEquals(2, r.preview.attachmentCount)
        assertEquals("1 mission · 4 milestones · 6 items · 12 comments · 2 files", r.preview.countsLine)
    }

    @Test
    fun projectHandoverSaysProject() {
        val r = PersonGrantRequest.parse(payload("""
            {"kind":"mission_handover","direction":"out","grant_id":"gr_2",
             "grantee":{"name":"bob"},"subject":{"kind":"project","num":3,"title":"Launch"},
             "preview":{"missions":2,"milestones":1,"items":1,"comments":0,"attachments":[]}}
        """.trimIndent()))!!
        assertEquals("Hand over a project", r.title)
        assertEquals("Your agent asks to hand Launch to bob.", r.headline)
        assertEquals("2 missions · 1 milestone · 1 item · 0 comments · 0 files", r.preview.countsLine)
        val incoming = r.copy(direction = PersonGrantRequest.Direction.IN, ownerName = "alice")
        assertEquals("Project hand-over", incoming.title)
    }

    @Test
    fun lenientWhenDisplayFieldsAreMissing() {
        val r = PersonGrantRequest.parse(payload("""{"kind":"mission_handover","grant_id":"gr_3"}"""))!!
        // No direction, no device, no file list: the recipient's side.
        assertEquals(PersonGrantRequest.Direction.IN, r.direction)
        assertEquals("Someone wants to hand you a mission.", r.headline)
        assertNull(r.brief)
        assertNull(r.preview.countsLine)
        val out = PersonGrantRequest.parse(payload("""{"kind":"mission_handover","grant_id":"gr_3","from_device_id":2}"""))!!
        assertEquals(PersonGrantRequest.Direction.OUT, out.direction)
        assertEquals("Your agent asks to hand a mission to a contact.", out.headline)
    }

    @Test
    fun attachmentsWithoutAMediaIdAreSkipped() {
        val r = PersonGrantRequest.parse(payload("""
            {"kind":"mission_handover","direction":"out","grant_id":"gr_4",
             "preview":{"attachments":[{"name":"x"},{"media_id":"md_9"}]}}
        """.trimIndent()))!!
        assertEquals(listOf("md_9"), r.attachmentIds)
        assertEquals("file", r.preview.attachments.single().name)
    }

    @Test
    fun rejectsOtherKindsAndCardsWithNoGrantId() {
        assertNull(PersonGrantRequest.parse(payload("""{"kind":"agent_spawn","grant_id":"gr_1"}""")))
        assertNull(PersonGrantRequest.parse(payload("""{"kind":"mission_handover"}""")))
        assertNull(PersonGrantRequest.parse(payload("""{"kind":"mission_handover","grant_id":"  "}""")))
        assertNull(PersonGrantRequest.parse(payload("""{"kind":"contact_request","contact_id":"ct_1"}""")))
    }

    @Test
    fun missionShareShowsItsLevel() {
        val contribute = PersonGrantRequest.parse(payload("""
            {"kind":"mission_share","direction":"in","grant_id":"gr_5","level":"contribute",
             "owner":{"user_id":"u_a","name":"alice"},"grantee":{"name":"bob"},
             "mission":{"id":"ms_1","num":7,"title":"Checkout redesign"},
             "preview":{"milestones":4,"items":6,"comments":12,"attachments":3}}
        """.trimIndent()))!!
        assertEquals(PersonGrantRequest.Kind.SHARE, contribute.kind)
        assertEquals("Mission share", contribute.title)
        assertEquals("alice wants to share Checkout redesign with you.", contribute.headline)
        assertEquals("can contribute", contribute.levelLabel)
        assertEquals("4 milestones · 6 items · 12 comments · 3 files", contribute.preview.countsLine)

        val read = PersonGrantRequest.parse(payload("""
            {"kind":"mission_share","direction":"out","grant_id":"gr_6","level":"read",
             "grantee":{"name":"bob"},"mission":{"title":"Checkout redesign"},"from_device_id":3}
        """.trimIndent()))!!
        assertEquals("Share a mission", read.title)
        assertEquals("Your agent asks to share Checkout redesign with bob.", read.headline)
        assertEquals("read-only", read.levelLabel)
        assertNull(shareLevelLabel("owner"))
    }

    @Test
    fun answerBodyCarriesExcludedMediaOnlyOnAnApprovalThatLeavesFilesBehind() {
        assertEquals("""{"decision":"approve","exclude_media":["md_2"]}""", grantAnswerBody(GrantDecision.APPROVE, listOf("md_2")).toString())
        assertEquals("""{"decision":"approve"}""", grantAnswerBody(GrantDecision.APPROVE, emptyList()).toString())
        assertEquals("""{"decision":"approve"}""", grantAnswerBody(GrantDecision.APPROVE).toString())
        // Declining never sends the ticks.
        assertEquals("""{"decision":"decline"}""", grantAnswerBody(GrantDecision.DECLINE, listOf("md_2")).toString())
    }

    @Test
    fun parsesKeepLevelOnBothSidesOfAHandover() {
        val contribute = PersonGrantRequest.parse(payload(handoverOut.replace("\"level\":\"owner\",", "\"level\":\"owner\",\"keep_level\":\"contribute\",")))!!
        assertEquals(KeepLevel.CONTRIBUTE, contribute.keepLevel)
        val read = PersonGrantRequest.parse(payload(handoverIn.replace("\"level\":\"owner\",", "\"level\":\"owner\",\"keep_level\":\"read\",")))!!
        assertEquals(KeepLevel.READ, read.keepLevel)
        val readOut = PersonGrantRequest.parse(payload(handoverOut.replace("\"level\":\"owner\",", "\"level\":\"owner\",\"keep_level\":\"read\",")))!!
        assertEquals(KeepLevel.READ, readOut.keepLevel)
    }

    @Test
    fun aMissingOrUnknownKeepLevelReadsAsRead() {
        assertEquals(KeepLevel.READ, PersonGrantRequest.parse(payload(handoverOut))!!.keepLevel)
        assertEquals(KeepLevel.READ, PersonGrantRequest.parse(payload(handoverIn))!!.keepLevel)
        val odd = PersonGrantRequest.parse(payload(handoverIn.replace("\"level\":\"owner\",", "\"level\":\"owner\",\"keep_level\":\"owner\",")))!!
        assertEquals(KeepLevel.READ, odd.keepLevel)
        assertEquals(KeepLevel.READ, KeepLevel.parse(null))
        assertEquals(KeepLevel.CONTRIBUTE, KeepLevel.parse("contribute"))
    }

    @Test
    fun onlyTheOwnersHandoverCardSendsAKeepLevel() {
        assertTrue(PersonGrantRequest.parse(payload(handoverOut))!!.sendsKeepLevel)
        assertFalse(PersonGrantRequest.parse(payload(handoverIn))!!.sendsKeepLevel)
        val share = PersonGrantRequest(PersonGrantRequest.Kind.SHARE, PersonGrantRequest.Direction.OUT, "gr_2", level = "read")
        assertFalse(share.sendsKeepLevel)
    }

    @Test
    fun keepSwitchLabelAndHint() {
        assertEquals("Keep contributing after the hand-over", KEEP_CONTRIBUTING_LABEL)
        assertEquals("You keep read access to their copy", keepLevelHint(KeepLevel.READ))
        assertEquals("You can keep adding milestones, items and comments", keepLevelHint(KeepLevel.CONTRIBUTE))
    }

    @Test
    fun recipientLineSaysWhatTheOwnerKeeps() {
        val read = PersonGrantRequest.parse(payload(handoverIn))!!
        assertEquals("alice keeps read access.", read.recipientKeepLine)
        val contribute = read.copy(keepLevel = KeepLevel.CONTRIBUTE)
        assertEquals("alice stays on as a contributor; you can end that any time.", contribute.recipientKeepLine)
        // Not on the owner's card, nor on a share.
        assertNull(PersonGrantRequest.parse(payload(handoverOut))!!.recipientKeepLine)
        val share = PersonGrantRequest(PersonGrantRequest.Kind.SHARE, PersonGrantRequest.Direction.IN, "gr_2", ownerName = "alice")
        assertNull(share.recipientKeepLine)
    }

    @Test
    fun answerBodyCarriesKeepLevelOnApprovalOnly() {
        assertEquals(
            """{"decision":"approve","keep_level":"contribute"}""",
            grantAnswerBody(GrantDecision.APPROVE, keepLevel = KeepLevel.CONTRIBUTE).toString(),
        )
        assertEquals(
            """{"decision":"approve","exclude_media":["md_2"],"keep_level":"read"}""",
            grantAnswerBody(GrantDecision.APPROVE, listOf("md_2"), KeepLevel.READ).toString(),
        )
        // Declining sends only the decision, as it already does for the ticks.
        assertEquals("""{"decision":"decline"}""", grantAnswerBody(GrantDecision.DECLINE, listOf("md_2"), KeepLevel.CONTRIBUTE).toString())
    }

    @Test
    fun answeredWording() {
        val out = PersonGrantRequest.parse(payload(handoverOut))!!
        val incoming = PersonGrantRequest.parse(payload(handoverIn))!!
        assertEquals("Approved — waiting for bob to accept", grantAnsweredText(out, true))
        assertEquals("Declined", grantAnsweredText(out, false))
        assertEquals("Accepted", grantAnsweredText(incoming, true))
        assertEquals("Declined", grantAnsweredText(incoming, false))
    }

    @Test
    fun fileCaptions() {
        val r = PersonGrantRequest.parse(payload(handoverOut))!!
        assertEquals("1.2 MB · item #12", grantFileCaption(r.preview.attachments[0]))
        assertEquals("2.0 KB · item #13", grantFileCaption(r.preview.attachments[1]))
        assertEquals("300 B", grantFileCaption(r.preview.attachments[2]))
        assertNull(grantFileCaption(PersonGrantRequest.Attachment("md", "x")))
        assertEquals("512 B", grantFileSize(512))
    }

    @Test
    fun personKindsAreRecognised() {
        for (kind in listOf("contact_request", "mission_share", "session_share", "person_room", "mission_handover")) {
            assertTrue(kind, PersonGrantRequest.isPersonKind(payload("""{"kind":"$kind"}""")))
        }
        assertFalse(PersonGrantRequest.isPersonKind(payload("""{"kind":"agent_spawn"}""")))
        assertFalse(PersonGrantRequest.isPersonKind(payload("""{"description":"Run tests?"}""")))
    }
}
