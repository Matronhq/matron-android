package chat.matron.android.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/// Decoding the opened plaintext, as matron-apple's `PushContent` does.
class PushContentTest {
    private fun parse(json: String) = PushContent.parse((json + "   ").toByteArray())

    @Test
    fun decodesTheMinimalContent() {
        assertEquals(
            PushContent("Inbox", null, "hello", "c1", null, "", null),
            parse("""{"v":1,"title":"Inbox","body":"hello","convo_id":"c1"}"""),
        )
    }

    @Test
    fun refusesAnotherVersionOrMissingFields() {
        assertNull(parse("""{"v":2,"title":"t","body":"b","convo_id":"c1"}"""))
        assertNull(parse("""{"v":"1","title":"t","body":"b","convo_id":"c1"}"""))
        assertNull(parse("""{"title":"t","body":"b","convo_id":"c1"}"""))
        assertNull(parse("""{"v":1,"body":"b","convo_id":"c1"}"""))
        assertNull(parse("""{"v":1,"title":"t","convo_id":"c1"}"""))
        assertNull(parse("""{"v":1,"title":"t","body":"b","convo_id":""}"""))
        assertNull(parse("not json"))
        assertNull(parse("[1,2]"))
    }

    @Test
    fun ignoresUnknownKeysAndBadOptionalOnes() {
        val content = parse("""{"v":1,"title":"t","body":"b","convo_id":"c1","seq":-4,"subtitle":"","later":true,"item":{"id":""}}""")!!
        assertNull(content.seq)
        assertNull(content.subtitle)
        assertNull(content.item)
    }

    @Test
    fun keepsAtMostFourNonEmptyStringActions() {
        val content = parse(
            """{"v":1,"title":"t","body":"b","convo_id":"c1","item":{"id":"it_1","num":3,"kind":"task",
               "actions":["A","",7,"B","C","D","E"],"reply_to":""}}""",
        )!!
        assertEquals(PushContent.Item("it_1", 3, "task", listOf("A", "B", "C", "D"), null), content.item)
    }
}
