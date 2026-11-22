package chat.matron.android.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The one quiet line a bulky timeline card folds to: a reminder's Send now /
/// Cancel card always, a consent card once it is answered. Port of
/// matron-apple's `CompactCardLineTests`.
class CompactCardLineTest {
    private fun reminder(prompt: String, id: Int = 7) = AskUserEvent(
        prompt = prompt,
        kind = AskUserEvent.InputKind.Choice(
            listOf(
                AskUserEvent.Option("timer-send-$id", "\uD83D\uDCE4 Send now", "timer:send:$id"),
                AskUserEvent.Option("timer-cancel-$id", "\uD83D\uDEAB Cancel timer", "timer:cancel:$id"),
            ),
            allowOther = false,
        ),
        expiresAt = null,
        replyChannel = AskUserEvent.ReplyChannel.CHOICE_REPLY,
    )

    @Test
    fun aReminderCardIsOneLineWithItsGlyphAsTheIcon() {
        val event = reminder("\u23F0 The agent set itself reminder #12 \u2014 in 10m:\n\"Check the build on box-a.\"")
        assertTrue(event.isReminderCard)
        assertEquals(
            CompactCardLine("\u23F0", "The agent set itself reminder #12 \u2014 in 10m: \"Check the build on box-a.\"", null),
            event.compactLine,
        )
    }

    @Test
    fun aReminderWithoutAGlyphGetsTheClock() {
        assertEquals("\u23F0", reminder("Reminder #3 in 2h").compactLine.icon)
        assertEquals("Reminder #3 in 2h", reminder("Reminder #3 in 2h").compactLine.summary)
        assertEquals("\u23F0", reminder("# 3 in 2h").compactLine.icon)
        assertEquals("# 3 in 2h", reminder("# 3 in 2h").compactLine.summary)
    }

    @Test
    fun otherAsksAreNotReminderCards() {
        val queued = AskUserEvent(
            prompt = "Message queued. Send now or cancel?",
            kind = AskUserEvent.InputKind.Choice(
                listOf(AskUserEvent.Option("s", "Send", "send:0"), AskUserEvent.Option("c", "Cancel", "cancel:0")),
                allowOther = false,
            ),
            expiresAt = null,
        )
        assertFalse(queued.isReminderCard)
        assertFalse(AskUserEvent("Name?", AskUserEvent.InputKind.Text, null).isReminderCard)
        val mixed = AskUserEvent(
            prompt = "Pick",
            kind = AskUserEvent.InputKind.Choice(
                listOf(AskUserEvent.Option("a", "A", "timer:send:1"), AskUserEvent.Option("b", "B", "b")),
                allowOther = false,
            ),
            expiresAt = null,
        )
        assertFalse("one timer button among others is not the reminder card", mixed.isReminderCard)
        val many = AskUserEvent(
            prompt = "Pick",
            kind = AskUserEvent.InputKind.MultiChoice(listOf(AskUserEvent.Option("a", "A", "timer:send:1")), allowOther = false),
            expiresAt = null,
        )
        assertFalse("a multi-choice is not the reminder card", many.isReminderCard)
    }

    private val spawn = AgentSpawnRequest(
        requestId = "r1", fromDeviceId = 1, fromName = "box-a", fromConvoId = null, fromConvoTitle = null,
        targetDeviceId = 2, targetName = "box-b", workdir = "", task = "Run the release build\nand report back.", topic = null,
    )

    @Test
    fun anOpenSpawnRequestStaysACard() {
        assertNull(spawn.compactLine(AgentSpawnCardState.Idle))
        assertNull(spawn.compactLine(AgentSpawnCardState.Sending))
        assertNull(spawn.compactLine(AgentSpawnCardState.Failed("offline")))
        assertNull(spawn.compactLine(AgentSpawnCardState.Unavailable))
    }

    private fun resolved(outcome: String) = AgentSpawnCardState.Resolved(SpawnOutcome(requestId = "r1", outcome = outcome, roomId = "!r"))

    @Test
    fun aResolvedSpawnRequestFoldsToItsOutcome() {
        assertEquals(
            CompactCardLine("\uD83D\uDE80", "Spawn on box-b: Run the release build", "Started"),
            spawn.compactLine(resolved("started")),
        )
        assertEquals("Declined", spawn.compactLine(resolved("declined"))?.status)
        assertEquals("\u231B", spawn.compactLine(resolved("expired"))?.icon)
        assertEquals("Failed", spawn.compactLine(resolved("failed"))?.status)
        assertEquals("Approved", spawn.compactLine(resolved("approved"))?.status)
        assertEquals(
            "an outcome this build does not know still folds",
            CompactCardLine("\u2728", "Spawn on box-b: Run the release build", "Resolved"),
            spawn.compactLine(resolved("teleported")),
        )
    }

    private val chat = AgentChatRequest(
        ask = AgentChatRequest.Ask.INVITE, roomID = "!r", fromDeviceID = 1, fromName = "box-a",
        targetDeviceID = 2, toName = "box-b", topic = null, justification = null,
    )

    @Test
    fun anOpenChatRequestStaysACard() {
        assertNull(chat.compactLine(AgentChatCardState.Idle))
        assertNull(chat.compactLine(AgentChatCardState.Sending))
        assertNull(chat.compactLine(AgentChatCardState.Failed("offline")))
    }

    @Test
    fun anAnsweredChatRequestFoldsToItsAnswer() {
        assertEquals(
            CompactCardLine("\uD83D\uDC65", "box-a wants to start a chat with box-b.", "Approved"),
            chat.compactLine(AgentChatCardState.Answered(true)),
        )
        assertEquals("Declined", chat.compactLine(AgentChatCardState.Answered(false))?.status)
        assertEquals("Approved by the Coordinator", chat.compactLine(AgentChatCardState.Answered(true, byCoordinator = true))?.status)
        assertEquals("Declined by the Coordinator", chat.compactLine(AgentChatCardState.Answered(false, byCoordinator = true))?.status)
        assertEquals("No longer waiting", chat.compactLine(AgentChatCardState.Expired)?.status)
    }

    @Test
    fun leadingGlyphNeedsOneSymbolAndASpace() {
        assertEquals("\u23F0" to "In 10m", CompactCardLine.leadingGlyph("\u23F0 In 10m"))
        assertEquals("a multi-part emoji is one glyph", "\uD83D\uDC69\u200D\uD83D\uDCBB" to "Dev", CompactCardLine.leadingGlyph("\uD83D\uDC69\u200D\uD83D\uDCBB Dev"))
        assertEquals(null to "\u23F0In", CompactCardLine.leadingGlyph("\u23F0In"))
        assertEquals(null to "\u23F0 ", CompactCardLine.leadingGlyph("\u23F0 "))
        assertEquals(null to "1 apple", CompactCardLine.leadingGlyph("1 apple"))
        assertEquals(null to "- item", CompactCardLine.leadingGlyph("- item"))
    }
}
