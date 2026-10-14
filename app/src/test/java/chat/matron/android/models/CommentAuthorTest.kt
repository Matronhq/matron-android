package chat.matron.android.models

import chat.matron.android.journal.parseJsonObjectOrNull
import org.junit.Assert.*
import org.junit.Test

class CommentAuthorTest {
    private fun decode(fields: String = "") = TrackerComment.fromJson(parseJsonObjectOrNull("""{
        "id":"ic_1","item_id":"it_1","author":"agent","kind":"comment",
        "body":"Done.","created_at":1700000002000 $fields
    }""")!!)!!

    @Test
    fun decodesTheBoxAndConversationAndFlattensTheTitle() {
        val comment = decode(""", "device_name":"  box-a  ","convo_id":"c1","convo_title":"Audit\n the\t runners"""")
        assertEquals("  box-a  ", comment.deviceName)
        assertEquals("c1", comment.convoID)
        assertEquals("Audit\n the\t runners", comment.convoTitle)
        assertEquals("box-a", comment.authorName)
        assertEquals("c1", comment.authorConversation?.id)
        assertEquals("Audit the runners", comment.authorConversation?.title)
    }

    @Test
    fun unicodeWhitespaceInATitleAlsoFlattensToOneLine() {
        val comment = decode().copy(convoID = "c1", convoTitle = "Audit\u2028 the\u00a0 runners\u2003")
        assertEquals("Audit the runners", comment.authorConversation?.title)
    }

    @Test
    fun olderAndNullFieldsKeepTheAgentFallback() {
        for (comment in listOf(decode(), decode(""", "device_name":null,"convo_id":null,"convo_title":null"""))) {
            assertNull(comment.deviceName)
            assertNull(comment.convoID)
            assertNull(comment.convoTitle)
            assertEquals("Agent", comment.authorName)
            assertNull(comment.authorConversation)
        }
    }

    @Test
    fun blankOrIncompleteMetadataDoesNotOfferAConversation() {
        val comment = decode().copy(deviceName = " \n ", convoID = "c1", convoTitle = " \n\t ")
        assertEquals("Agent", comment.authorName)
        assertNull(comment.authorConversation)
        assertNull(comment.copy(convoTitle = null).authorConversation)
        for (id in listOf(null, "", " \t ")) {
            assertNull(comment.copy(convoID = id, convoTitle = "Audit").authorConversation)
        }
        assertEquals("Audit", comment.copy(convoTitle = "Audit").authorConversation?.title)
    }

    @Test
    fun usersOwnCommentsAlwaysReadYouWithoutAConversation() {
        val comment = decode().copy(author = ItemAuthor.USER, deviceName = "box-a", convoID = "c1", convoTitle = "Audit")
        assertEquals("You", comment.authorName)
        assertNull(comment.authorConversation)
    }

    @Test
    fun answeringACommentKeepsItsAttribution() {
        val comment = decode().copy(deviceName = "box-a", convoID = "c1", convoTitle = "Audit", actions = listOf("Go"))
        val chosen = comment.copy(chosenAction = "Go")
        assertEquals(comment.authorName, chosen.authorName)
        assertEquals(comment.authorConversation, chosen.authorConversation)
    }
}
