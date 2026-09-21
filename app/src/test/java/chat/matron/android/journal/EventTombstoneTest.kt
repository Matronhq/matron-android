package chat.matron.android.journal

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// The tombstone rules, as a table. Both insert paths and both sweeps route
/// through this one function, so everything either side of the launch path
/// agrees about what an aged-out payload looks like. Ported from
/// matron-apple's `EventTombstoneTests`.
class EventTombstoneTest {
    private val ts = 1_000_000_000L
    private val hour = 3600_000L
    private val day = 24 * hour

    private fun toolOutput(command: String = "make test", liveLog: Boolean = true, expired: Boolean? = null) =
        buildJsonObject {
            put("message_ref", "toolu_1"); put("command", command)
            put("exit_code", 1); put("denied", false); put("truncated", false)
            put("snippet", "output text"); put("blob_ref", "blob-1")
            if (liveLog) put("live_log", true)
            if (expired != null) put("expired", expired)
        }

    private fun diff() = buildJsonObject {
        put("file_path", "/w/Sources/A.swift"); put("display_path", "Sources/A.swift")
        put("tool", "Edit"); put("added", 2); put("removed", 1); put("truncated", false); put("new_file", false)
        put("diff", "@@ -1 +1 @@\n-a\n+b"); put("snippet", "short form")
    }

    private fun apply(payload: JsonObject, type: String, now: Long) = EventTombstone.apply(payload, type, ts, now)

    // MARK: Nothing to do

    @Test fun freshToolOutputIsUntouched() {
        assertNull(apply(toolOutput(), JournalEventType.TOOL_OUTPUT, ts + hour))
    }

    @Test fun freshDiffIsUntouched() {
        assertNull("a diff has no 24h rule — only the 30-day retention one",
            apply(diff(), JournalEventType.DIFF, ts + 25 * hour))
    }

    @Test fun otherTypesAreNeverRewritten() {
        assertNull(apply(buildJsonObject { put("body", "hi") }, JournalEventType.TEXT, ts + 400 * day))
    }

    @Test fun nonLiveLogToolOutputSurvivesThe24hRule() {
        assertNull("offloaded/legacy payloads keep their durable snippet until retention",
            apply(toolOutput(liveLog = false), JournalEventType.TOOL_OUTPUT, ts + 25 * hour))
    }

    @Test fun alreadyExpiredRowReturnsNull() {
        val tombstoned = JsonObject(
            toolOutput(liveLog = true, expired = true).toMutableMap().apply {
                remove("snippet"); remove("live_log"); put("blob_ref", JsonNull)
            }
        )
        assertNull("a second sweep over the same row must do no work",
            apply(tombstoned, JournalEventType.TOOL_OUTPUT, ts + 25 * hour))
    }

    /// A row still carrying `live_log` while flagged expired (a server
    /// tombstone that kept the key) is stripped once, then left alone.
    @Test fun expiredRowThatStillCarriesLiveLogIsStrippedOnceThenLeftAlone() {
        val once = apply(toolOutput(liveLog = true, expired = true), JournalEventType.TOOL_OUTPUT, ts + 25 * hour)
        assertNotNull(once)
        assertFalse("live_log" in once!!)
        assertNull(apply(once, JournalEventType.TOOL_OUTPUT, ts + 26 * hour))
    }

    // MARK: The 24h tool-log rule

    @Test fun staleLiveLogLosesItsBodyAndGainsExpired() {
        val out = apply(toolOutput(), JournalEventType.TOOL_OUTPUT, ts + 25 * hour)!!
        assertFalse("snippet" in out)
        assertFalse("live_log" in out)
        assertEquals("the shipped tombstone shape nulls the blob ref", JsonNull, out["blob_ref"])
        assertEquals(true, out.boolOrNull("expired"))
        assertEquals("what ran survives the 24h rule in full", "make test", out.stringOrNull("command"))
        assertEquals(1, out.intOrNull("exit_code"))
        assertEquals(false, out.boolOrNull("denied"))
        assertEquals(false, out.boolOrNull("truncated"))
        assertEquals("toolu_1", out.stringOrNull("message_ref"))
    }

    @Test fun the24hRuleDoesNotTruncateALongCommand() {
        val long = "x".repeat(500)
        val out = apply(toolOutput(command = long), JournalEventType.TOOL_OUTPUT, ts + 25 * hour)!!
        assertEquals(long, out.stringOrNull("command"))
    }

    @Test fun applyingTwiceIsIdempotent() {
        val once = apply(toolOutput(), JournalEventType.TOOL_OUTPUT, ts + 25 * hour)!!
        assertNull(apply(once, JournalEventType.TOOL_OUTPUT, ts + 26 * hour))
    }

    // MARK: The 30-day retention rule

    @Test fun retentionTruncatesTheCommandTo200CharactersPlusEllipsis() {
        val long = "x".repeat(500)
        val out = apply(toolOutput(command = long), JournalEventType.TOOL_OUTPUT, ts + 31 * day)!!
        assertEquals("x".repeat(200) + "…", out.stringOrNull("command"))
        assertFalse("snippet" in out)
        assertFalse("live_log" in out)
        assertEquals(true, out.boolOrNull("expired"))
        assertEquals(1, out.intOrNull("exit_code"))
    }

    @Test fun retentionLeavesAShortCommandAlone() {
        val out = apply(toolOutput(), JournalEventType.TOOL_OUTPUT, ts + 31 * day)!!
        assertEquals("make test", out.stringOrNull("command"))
    }

    /// A row tombstoned by the 24h rule keeps its full command; when it
    /// later crosses the retention window the sweep must still shorten it,
    /// so "already expired" cannot short-circuit retention.
    @Test fun retentionStillTruncatesARowThe24hRuleAlreadyTombstoned() {
        val long = "y".repeat(300)
        val dayOld = apply(toolOutput(command = long), JournalEventType.TOOL_OUTPUT, ts + 25 * hour)!!
        val aged = apply(dayOld, JournalEventType.TOOL_OUTPUT, ts + 31 * day)!!
        assertEquals("y".repeat(200) + "…", aged.stringOrNull("command"))
    }

    @Test fun retentionStripsADiffButKeepsEveryOtherKey() {
        val out = apply(diff(), JournalEventType.DIFF, ts + 31 * day)!!
        assertFalse("diff" in out)
        assertFalse("snippet" in out)
        assertEquals(true, out.boolOrNull("expired"))
        assertEquals("/w/Sources/A.swift", out.stringOrNull("file_path"))
        assertEquals("Sources/A.swift", out.stringOrNull("display_path"))
        assertEquals("Edit", out.stringOrNull("tool"))
        assertEquals(2, out.intOrNull("added"))
        assertEquals(1, out.intOrNull("removed"))
        assertEquals(false, out.boolOrNull("new_file"))
    }

    @Test fun retentionOnADiffIsIdempotent() {
        val once = apply(diff(), JournalEventType.DIFF, ts + 31 * day)!!
        assertNull(apply(once, JournalEventType.DIFF, ts + 40 * day))
    }

    /// An absent `blob_ref` must stay absent — the tombstone nulls the key
    /// only when it was there.
    @Test fun absentBlobRefStaysAbsent() {
        val payload = buildJsonObject { put("command", "make test"); put("live_log", true); put("snippet", "out") }
        val out = apply(payload, JournalEventType.TOOL_OUTPUT, ts + 25 * hour)!!
        assertFalse("blob_ref" in out)
        assertTrue(out.boolOrNull("expired") == true)
    }

    @Test fun constantsAreTheOnesTheSpecFixed() {
        assertEquals(24L * 3600 * 1000, EventTombstone.TOOL_LOG_TTL_MS)
        assertEquals(30L * 24 * 3600 * 1000, EventTombstone.RETENTION_WINDOW_MS)
        assertEquals(200, EventTombstone.COMMAND_STUB_LENGTH)
    }
}
