package chat.matron.android.models

import chat.matron.android.journal.db.ItemCommentEntity
import chat.matron.android.journal.parseJsonObjectOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// An edited reply names the cancelled reply it stands in for (`replaces`),
/// and the thread leaves that reply out.
class ReplacedCommentsTest {
    private fun reply(id: String, body: String, replaces: String? = null) =
        TrackerComment(id = id, itemID = "it_1", author = ItemAuthor.USER, body = body, replaces = replaces)

    @Test
    fun aCommentDecodesTheReplyItReplacesAndTheCacheKeepsIt() {
        val json = """{"id":"ic_2","item_id":"it_1","author":"user","kind":"comment","body":"x","created_at":1000,"replaces":"ic_1"}"""
        val comment = TrackerComment.fromJson(parseJsonObjectOrNull(json)!!)!!
        assertEquals("ic_1", comment.replaces)
        assertEquals("ic_1", ItemCommentEntity.from(comment).toComment().replaces)
        val plain = TrackerComment.fromJson(parseJsonObjectOrNull(json.replace(",\"replaces\":\"ic_1\"", ""))!!)!!
        assertNull(plain.replaces)
        assertNull("a plain reply still caches with no meta", ItemCommentEntity.from(plain).metaJson)
    }

    @Test
    fun theThreadLeavesOutTheCancelledReplyAnEditReplaced() {
        val thread = listOf(reply("ic_1", "Use OAuth"), reply("ic_2", "Use OAuth with PKCE", replaces = "ic_1"))
        assertEquals(listOf("ic_2"), TrackerComment.hidingReplaced(thread).map { it.id })
    }

    @Test
    fun aThreadWithNoEditIsShownWhole() {
        val thread = listOf(reply("ic_1", "Use OAuth"), reply("ic_2", "And another thing"))
        assertEquals(listOf("ic_1", "ic_2"), TrackerComment.hidingReplaced(thread).map { it.id })
    }

    @Test
    fun onlyTheUsersOwnRepliesAreEverLeftOut() {
        val agents = TrackerComment(id = "ic_a", itemID = "it_1", author = ItemAuthor.AGENT, body = "Noted")
        val status = TrackerComment(id = "ic_s", itemID = "it_1", author = ItemAuthor.USER, kind = TrackerComment.Kind.STATUS, body = "")
        val thread = listOf(agents, status, reply("ic_9", "x", replaces = "ic_a"), reply("ic_8", "y", replaces = "ic_s"))
        assertEquals(listOf("ic_a", "ic_s", "ic_9", "ic_8"), TrackerComment.hidingReplaced(thread).map { it.id })
    }
}
