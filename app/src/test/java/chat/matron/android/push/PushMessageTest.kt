package chat.matron.android.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// The journal's FCM data messages (docs/protocol.md "Android push (FCM)").
class PushMessageTest {
    @Test
    fun parsesASealedAlert() {
        assertEquals(
            PushMessage.Alert("c1", 7, "attention", "it_1", "Ym94", null, null, 3),
            PushMessage.parse(mapOf("type" to "alert", "convo_id" to "c1", "seq" to "7", "kind" to "attention", "item_id" to "it_1", "box" to "Ym94", "badge" to "3")),
        )
    }

    @Test
    fun parsesAClearAlertAndARead() {
        assertEquals(
            PushMessage.Alert("c1", null, "done", null, null, "Inbox", "finished", null),
            PushMessage.parse(mapOf("type" to "alert", "convo_id" to "c1", "seq" to "x", "kind" to "done", "title" to "Inbox", "body" to "finished", "badge" to "-1")),
        )
        assertEquals(PushMessage.Read("c2", 0), PushMessage.parse(mapOf("type" to "read", "convo_id" to "c2", "badge" to "0")))
    }

    @Test
    fun ignoresWhatIsNotTheJournals() {
        assertNull(PushMessage.parse(emptyMap()))
        assertNull(PushMessage.parse(mapOf("type" to "alert")))
        assertNull(PushMessage.parse(mapOf("type" to "wake", "convo_id" to "c1")))
        assertNull(PushMessage.parse(mapOf("convo_id" to "c1")))
    }

    @Test
    fun displayPrefersTheBoxThenClearTextThenFixedText() {
        val sealed = PushMessage.Alert("c1", 7, "attention", "it_1", "Ym94", null, null, 2)
        val content = PushContent("Ship it?", "Question · Docs", "Now?", "c1", 7, "attention",
            PushContent.Item("it_1", 1, "question", listOf("Ship", "Wait"), "cm_1"))
        val opened = PushDisplay.of(sealed, content)
        assertEquals("Ship it?", opened.title)
        assertEquals("Question · Docs", opened.subtitle)
        assertEquals(listOf("Ship", "Wait"), opened.actions)
        assertEquals("cm_1", opened.replyTo)
        assertEquals("item:it_1", opened.tag)
        assertEquals(2, opened.badge)

        val unopened = PushDisplay.of(sealed.copy(title = "leak", body = "leak"), null)
        assertEquals("Matron", unopened.title)
        assertEquals("Your agent needs you", unopened.body)
        assertEquals(emptyList<String>(), unopened.actions)

        val clear = PushDisplay.of(PushMessage.Alert("c1", 7, "done", null, null, "Inbox", "all done", null), null)
        assertEquals("Inbox", clear.title)
        assertEquals("all done", clear.body)
        assertEquals("convo:c1", clear.tag)

        assertEquals("New activity from your agent", PushDisplay.of(PushMessage.Alert("c1", null, "activity", null, null, null, null, null), null).body)
        assertEquals("Your agent finished its turn", PushDisplay.fallbackBody("done"))
    }
}
