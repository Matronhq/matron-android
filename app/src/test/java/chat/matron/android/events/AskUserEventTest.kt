package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserEventTest {
    private fun content(json: String): JsonObject = parseJsonObjectOrNull(json)!!

    @Test
    fun parsesText() {
        val evt = AskUserEvent.parse(content("""{"prompt":"What's your name?","input":{"kind":"text"}}"""))!!
        assertEquals("What's your name?", evt.prompt)
        assertEquals(AskUserEvent.InputKind.Text, evt.kind)
        assertNull(evt.expiresAt)
    }

    @Test
    fun parsesIntegerExpiresAtFromRealJSON() {
        val evt = AskUserEvent.parse(content("""{"prompt":"Q?","input":{"kind":"text"},"expires_at":1745000600000}"""))!!
        assertEquals(Instant.ofEpochMilli(1_745_000_600_000), evt.expiresAt)
    }

    @Test
    fun parsesChoice() {
        val evt = AskUserEvent.parse(content("""{"prompt":"Which file?","input":{"kind":"choice","allow_other":true,"options":[{"id":"a","label":"main.rs"},{"id":"b","label":"lib.rs"}]}}"""))!!
        val kind = evt.kind as AskUserEvent.InputKind.Choice
        assertEquals(2, kind.options.size)
        assertEquals(AskUserEvent.Option("a", "main.rs"), kind.options[0])
        assertEquals(AskUserEvent.Option("b", "lib.rs"), kind.options[1])
        assertTrue(kind.allowOther)
    }

    @Test
    fun parsesMultiChoiceDefaultsAllowOtherToFalse() {
        val evt = AskUserEvent.parse(content("""{"prompt":"Pick languages","input":{"kind":"multi_choice","options":[{"id":"swift","label":"Swift"},{"id":"rust","label":"Rust"}]}}"""))!!
        val kind = evt.kind as AskUserEvent.InputKind.MultiChoice
        assertEquals(2, kind.options.size)
        assertFalse(kind.allowOther)
    }

    @Test
    fun parsesBoolean() {
        val evt = AskUserEvent.parse(content("""{"prompt":"Continue?","input":{"kind":"boolean"}}"""))!!
        assertEquals(AskUserEvent.InputKind.Boolean, evt.kind)
    }

    @Test
    fun parsesExpiresAt() {
        val evt = AskUserEvent.parse(content("""{"prompt":"x","input":{"kind":"text"},"expires_at":1745000000000}"""))!!
        assertEquals(Instant.ofEpochMilli(1_745_000_000_000), evt.expiresAt)
    }

    @Test
    fun skipsMalformedOptionEntries() {
        val evt = AskUserEvent.parse(content("""{"prompt":"x","input":{"kind":"choice","options":[{"id":"a","label":"Apple"},{"label":"missing id"},{"id":"b","label":"Banana"}]}}"""))!!
        val kind = evt.kind as AskUserEvent.InputKind.Choice
        assertEquals(2, kind.options.size)
        assertEquals(listOf("a", "b"), kind.options.map { it.id })
    }

    @Test
    fun returnsNullWhenPromptMissing() {
        assertNull(AskUserEvent.parse(content("""{"input":{"kind":"text"}}""")))
    }

    @Test
    fun returnsNullWhenInputKindMissing() {
        assertNull(AskUserEvent.parse(content("""{"prompt":"x","input":{}}""")))
    }

    @Test
    fun returnsNullWhenInputKindUnknown() {
        assertNull(AskUserEvent.parse(content("""{"prompt":"x","input":{"kind":"alien"}}""")))
    }

    @Test
    fun parseSetsTextReplyChannel() {
        val evt = AskUserEvent.parse(content("""{"prompt":"x","input":{"kind":"text"}}"""))!!
        assertEquals(AskUserEvent.ReplyChannel.TEXT_REPLY, evt.replyChannel)
    }

    @Test
    fun parseOptionValueDefaultsToLabel() {
        val evt = AskUserEvent.parse(content("""{"prompt":"x","input":{"kind":"choice","options":[{"id":"a","label":"main.rs"}]}}"""))!!
        val kind = evt.kind as AskUserEvent.InputKind.Choice
        assertEquals("main.rs", kind.options[0].value)
    }

    // MARK: parseButtons (Matron X / bridge buttons protocol)

    private fun buttonsContent(mode: String = "pick_one", buttonsJson: String = """[{"id":"a","label":"Yes","value":"yes"}]"""): JsonObject =
        content("""{"msgtype":"m.text","body":"Pick one: Yes","chat.matron.buttons":{"mode":"$mode","prompt":"Proceed?","buttons":$buttonsJson}}""")

    @Test
    fun parseButtonsPickOneMapsToChoice() {
        val evt = AskUserEvent.parseButtons(buttonsContent(
            mode = "pick_one",
            buttonsJson = """[{"id":"a","label":"Send now","value":"interrupt"},{"id":"b","label":"Cancel message 1","value":"cancel:0"}]"""))!!
        assertEquals("Proceed?", evt.prompt)
        assertEquals(AskUserEvent.ReplyChannel.BUTTON_RESPONSE, evt.replyChannel)
        assertNull(evt.expiresAt)
        val kind = evt.kind as AskUserEvent.InputKind.Choice
        assertFalse(kind.allowOther)
        assertEquals(listOf("Send now", "Cancel message 1"), kind.options.map { it.label })
        assertEquals(listOf("interrupt", "cancel:0"), kind.options.map { it.value })
    }

    @Test
    fun parseButtonsPickManyMapsToMultiChoice() {
        val evt = AskUserEvent.parseButtons(buttonsContent(mode = "pick_many"))!!
        assertTrue(evt.kind is AskUserEvent.InputKind.MultiChoice)
    }

    @Test
    fun parseButtonsReturnsNullWhenModeUnknown() {
        assertNull(AskUserEvent.parseButtons(buttonsContent(mode = "pick_some")))
    }

    @Test
    fun parseButtonsReturnsNullWhenNoButtonsKey() {
        assertNull(AskUserEvent.parseButtons(content("""{"msgtype":"m.text","body":"plain message"}""")))
    }

    @Test
    fun parseButtonsReturnsNullWhenAllButtonsMalformed() {
        assertNull(AskUserEvent.parseButtons(buttonsContent(buttonsJson = """[{"id":"a","label":"no value field"}]""")))
    }
}
