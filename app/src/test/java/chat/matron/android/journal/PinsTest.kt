package chat.matron.android.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/// Pinned desk chats (journal "Pinned desk chats"): parsing, the label and
/// emoji rules, ordering, the glyph and the successor hint. Ported from
/// matron-web's `pins-test.ts`.
class PinsTest {
    private fun rawPin(convoID: String, position: Int, extra: String = "") =
        """{"convo_id":"$convoID","label":"Pin $convoID","emoji":"","position":$position,"device_id":7,"created_at":1,"updated_at":2$extra}"""

    private fun pin(convoID: String, position: Int, deviceID: Long? = 7, successor: ConvoPinSuccessor? = null) =
        ConvoPin(convoID, "Pin $convoID", "", position, deviceID, successor = successor, createdAt = 1, updatedAt = 2)

    private fun obj(text: String) = parseJsonObjectOrNull(text)!!

    // MARK: Parsing

    @Test
    fun readsPinsFromASnapshotInPositionOrderDroppingMalformedRows() {
        val pins = Pins.fromContainer(
            obj(
                """{"conversations":[],"seq":4,"pins":[""" +
                    rawPin("b", 2, ""","missing":true""") + "," +
                    rawPin("a", 1, ""","successor":{"convo_id":"a2","title":"Later","created_at":9}""") + "," +
                    """{"convo_id":"x"},"junk",""" + rawPin("a", 3) + "]}",
            ),
        )!!
        assertEquals(listOf("a", "b"), pins.map { it.convoID })
        assertEquals(ConvoPinSuccessor("a2", "Later", 9), pins[0].successor)
        assertTrue(pins[1].missing)
        assertFalse(pins[0].missing)
        assertEquals(7L, pins[0].deviceID)
    }

    @Test
    fun anAbsentOrUnusablePinsKeyReadsAsUnsupported() {
        assertNull(Pins.fromContainer(obj("""{"conversations":[],"seq":1}""")))
        assertNull(Pins.fromContainer(obj("""{"pins":"nope"}""")))
        assertEquals(emptyList<ConvoPin>(), Pins.fromContainer(obj("""{"pins":[]}""")))
    }

    @Test
    fun aBlankLabelOrMissingIdDropsTheRow() {
        assertNull(Pins.parsePin(obj("""{"convo_id":"a","label":"  "}""")))
        assertNull(Pins.parsePin(obj("""{"convo_id":"","label":"x"}""")))
        assertNull(Pins.parsePin(obj("""{"convo_id":"a","label":"x","successor":{"title":"no id"}}"""))!!.successor)
    }

    @Test
    fun helloOKCarriesPinsAndTheCoordinator() {
        val hello = ServerFrame.decode(
            """{"kind":"control","op":"hello_ok","seq":9,"pins":[${rawPin("a", 0)}],"coordinator_convo_id":"c9"}""",
        ) as ServerFrame.HelloOK
        assertEquals(9L, hello.headSeq)
        assertEquals(listOf("a"), hello.pins!!.map { it.convoID })
        assertEquals(HelloCoordinator.Known("c9"), hello.coordinator)

        val cleared = ServerFrame.decode("""{"kind":"control","op":"hello_ok","seq":1,"coordinator_convo_id":null}""") as ServerFrame.HelloOK
        assertEquals(HelloCoordinator.Known(null), cleared.coordinator)
        assertNull("no pins key: an older journal", cleared.pins)

        val old = ServerFrame.decode("""{"kind":"control","op":"hello_ok","seq":1}""") as ServerFrame.HelloOK
        assertEquals(HelloCoordinator.Absent, old.coordinator)
    }

    @Test
    fun aLivePinsFrameDecodesInOrder() {
        val frame = ServerFrame.decode("""{"kind":"pins","pins":[${rawPin("b", 1)},${rawPin("a", 0)}]}""")
        assertEquals(listOf("a", "b"), (frame as ServerFrame.PinsFrame).pins.map { it.convoID })
        assertNull("a pins frame without a list is skipped", ServerFrame.decode("""{"kind":"pins"}"""))
    }

    @Test
    fun parsesAPinsResponseAndItsLimitRejectingABodyWithNoList() {
        assertEquals(5, Pins.parseResponse(obj("""{"pins":[${rawPin("a", 0)}],"limit":5}""")).limit)
        assertEquals(Pins.DEFAULT_LIMIT, Pins.parseResponse(obj("""{"pins":[]}""")).limit)
        assertEquals(3, Pins.parseResponse(obj("""{"pins":[],"limit":3}""")).limit)
        try {
            Pins.parseResponse(obj("""{"error":"x"}"""))
            fail("expected Transport")
        } catch (_: JournalApiError.Transport) {
        }
    }

    @Test
    fun theCacheRoundTrips() {
        val pins = listOf(
            pin("a", 0, successor = ConvoPinSuccessor("a2", "Later", 3)),
            pin("b", 1, deviceID = null).copy(missing = true, emoji = "📮"),
        )
        assertEquals(pins, Pins.decodeList(Pins.encodeList(pins)))
        assertNull(Pins.decodeList("not json"))
    }

    // MARK: Labels, emoji, glyph

    @Test
    fun labelFromTitleStripsTheMarkerAndShortAndClamps() {
        assertEquals("Deploy the thing", Pins.labelFromTitle("🐣 [ab] Deploy the thing"))
        assertEquals("Deploy the thing", Pins.labelFromTitle("[ab] Deploy the thing"))
        assertEquals("An extremely long conver", Pins.labelFromTitle("[ab] An extremely long conversation title here"))
        assertEquals("[WIP] thing", Pins.labelFromTitle("[WIP] thing"))
        assertEquals(Pins.LABEL_FALLBACK, Pins.labelFromTitle("   "))
        assertEquals("line one line two", Pins.labelFromTitle("line one\nline two"))
    }

    @Test
    fun clampLabelCountsCodePointsAndNeverSplitsAnEmoji() {
        assertEquals("a b", Pins.clampLabel("  a \t b  "))
        val emojis = "📮".repeat(30)
        val clamped = Pins.clampLabel(emojis)
        assertEquals(24, clamped.codePointCount(0, clamped.length))
        assertEquals("📮".repeat(24), clamped)
        assertEquals("x".repeat(24), Pins.clampLabel("x".repeat(40)))
    }

    @Test
    fun clampEmojiDropsWhitespaceAndKeepsWholeSurrogatePairs() {
        assertEquals("🚀", Pins.clampEmoji(" 🚀 "))
        assertEquals("", Pins.clampEmoji("   "))
        val clamped = Pins.clampEmoji("a" + "🚀".repeat(10))
        assertTrue(clamped.length <= Pins.EMOJI_MAX)
        assertFalse(clamped.last().isHighSurrogate())
    }

    @Test
    fun drawsTheEmojiElseTheLabelsFirstLetter() {
        assertEquals("🚀", Pins.glyph("🚀", "deploy"))
        assertEquals("D", Pins.glyph("", "deploy"))
        assertEquals("D", Pins.glyph("  ", "  deploy"))
        assertEquals("📮", Pins.glyph("", "📮 mail"))
        assertEquals("?", Pins.glyph("", " "))
    }

    // MARK: Ordering and the successor hint

    @Test
    fun movesAPinOneStepUpOrDown() {
        val pins = listOf(pin("a", 0), pin("b", 1), pin("c", 2))
        assertEquals(listOf("b", "a", "c"), Pins.movedOrder(pins, "b", Pins.Direction.UP))
        assertEquals(listOf("a", "c", "b"), Pins.movedOrder(pins, "b", Pins.Direction.DOWN))
        assertNull(Pins.movedOrder(pins, "a", Pins.Direction.UP))
        assertNull(Pins.movedOrder(pins, "c", Pins.Direction.DOWN))
        assertNull(Pins.movedOrder(pins, "z", Pins.Direction.UP))
    }

    @Test
    fun successorHintNamesThePinsBoxElseThisBox() {
        val successor = ConvoPinSuccessor("next", "Next", 3)
        val boxes = mapOf(7L to "triage-box", 8L to "elm")
        assertEquals(
            "New session on triage-box — move pin here?",
            Pins.successorHintText(pin("a", 0, successor = successor), boxes),
        )
        assertEquals(
            "New session on this box — move pin here?",
            Pins.successorHintText(pin("a", 0, deviceID = null, successor = successor), boxes),
        )
        assertEquals(
            "New session on this box — move pin here?",
            Pins.successorHintText(pin("a", 0, deviceID = 99, successor = successor), boxes),
        )
    }

    // MARK: Errors

    @Test
    fun wordsTheJournalsRefusals() {
        assertEquals("You can pin up to 5 chats.", PinRequestError(409, "pin_limit", 5).message)
        assertEquals("You can pin up to 5 chats.", PinRequestError(409, "pin_limit", null).message)
        assertEquals("That chat is already pinned.", PinRequestError(409, "already_pinned", null).message)
        assertEquals("That chat isn't available to pin any more.", PinRequestError(404, null, null).message)
        assertEquals("That label or emoji isn't allowed.", PinRequestError(400, "bad_label", null).message)
    }
}
