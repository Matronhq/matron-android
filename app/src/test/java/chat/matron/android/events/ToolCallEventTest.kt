package chat.matron.android.events

import chat.matron.android.journal.parseJsonObjectOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallEventTest {
    private fun content(json: String): JsonObject = parseJsonObjectOrNull(json)!!

    @Test
    fun parsesRunningEvent() {
        val evt = ToolCallEvent.parse(content("""{"tool":"Read","args":{"file_path":"/etc/hosts"},"status":"running","started_at":1745000000000}"""))!!
        assertEquals("Read", evt.tool)
        assertEquals(ToolCallEvent.Status.RUNNING, evt.status)
        assertNull(evt.resultText)
        assertNull(evt.endedAt)
        assertFalse(evt.resultTruncated)
        assertEquals(Instant.ofEpochMilli(1_745_000_000_000), evt.startedAt)
    }

    @Test
    fun parsesIntegerTimestampsFromRealJSON() {
        val evt = ToolCallEvent.parse(content("""{"tool":"Read","args":{},"status":"ok","result":"x","started_at":1745000000000,"ended_at":1745000001000}"""))!!
        assertEquals(Instant.ofEpochMilli(1_745_000_000_000), evt.startedAt)
        assertEquals(Instant.ofEpochMilli(1_745_000_001_000), evt.endedAt)
    }

    @Test
    fun parsesOkWithStringResult() {
        val evt = ToolCallEvent.parse(content("""{"tool":"Read","args":{"file_path":"/etc/hosts"},"status":"ok","result":"127.0.0.1 localhost","started_at":1745000000000,"ended_at":1745000001000}"""))!!
        assertEquals(ToolCallEvent.Status.OK, evt.status)
        assertEquals("127.0.0.1 localhost", evt.resultText)
        assertEquals(Instant.ofEpochMilli(1_745_000_001_000), evt.endedAt)
    }

    @Test
    fun parsesErrorWithStructuredObjectResult() {
        val evt = ToolCallEvent.parse(content("""{"tool":"Bash","args":{"command":"ls /nope"},"status":"error","result":{"exit_code":2,"stderr":"no such file"},"result_truncated":true,"started_at":1745000000000,"ended_at":1745000002000}"""))!!
        assertEquals(ToolCallEvent.Status.ERROR, evt.status)
        assertTrue(evt.resultTruncated)
        // Sorted keys → `exit_code` before `stderr`, deterministically.
        val expected = "{\n    \"exit_code\": 2,\n    \"stderr\": \"no such file\"\n}"
        assertEquals(expected, evt.resultText)
    }

    @Test
    fun argsJSONIsSortedAndPrettyPrinted() {
        val evt = ToolCallEvent.parse(content("""{"tool":"Edit","args":{"new_string":"Y","file_path":"/x","old_string":"X"},"status":"running","started_at":1745000000000}"""))!!
        val expected = "{\n    \"file_path\": \"/x\",\n    \"new_string\": \"Y\",\n    \"old_string\": \"X\"\n}"
        assertEquals(expected, evt.argsJSON)
    }

    @Test
    fun argsJSONEmptyDictWhenArgsMissing() {
        val cases = listOf(
            """{"tool":"Now","status":"ok","result":"x","started_at":1745000000000}""",
            """{"tool":"Now","args":{},"status":"ok","result":"x","started_at":1745000000000}""",
        )
        for (c in cases) {
            assertEquals("{}", ToolCallEvent.parse(content(c))!!.argsJSON)
        }
    }

    @Test
    fun returnsNullWhenMissingRequiredFields() {
        assertNull(ToolCallEvent.parse(content("""{"tool":"Read"}""")))
    }

    @Test
    fun returnsNullWhenStatusIsUnknownString() {
        assertNull(ToolCallEvent.parse(content("""{"tool":"Read","args":{},"status":"weird","started_at":1745000000000}""")))
    }

    @Test
    fun returnsNullWhenStartedAtIsString() {
        assertNull(ToolCallEvent.parse(content("""{"tool":"Read","args":{},"status":"running","started_at":"1745000000000"}""")))
    }

    @Test
    fun argSummaryBashCommandShapeShowsCommandVerbatim() {
        val evt = makeEvent("""{ "command": "ls -la /tmp" }""")
        assertEquals("ls -la /tmp", evt.argSummary)
        assertEquals("ls -la /tmp", evt.commandString)
    }

    @Test
    fun argSummarySingleStringValueShowsKeyColonValue() {
        val evt = makeEvent("""{ "file_path": "/etc/hosts" }""")
        assertEquals("file_path: /etc/hosts", evt.argSummary)
        assertNull(evt.commandString)
    }

    @Test
    fun argSummaryMultiKeyObjectFallsBackToTrimmedJSON() {
        val json = "{\n    \"new_string\": \"Y\",\n    \"old_string\": \"X\"\n}"
        val evt = makeEvent(json)
        assertEquals(json.replace("\n", " "), evt.argSummary)
        assertNull(evt.commandString)
    }

    @Test
    fun argSummaryTruncatesTo80Chars() {
        val long = "x".repeat(200)
        val evt = makeEvent("""{ "command": "$long" }""")
        assertEquals("x".repeat(77) + "…", evt.argSummary)
    }

    @Test
    fun argSummaryEmptyArgsIsBlank() {
        assertEquals("", makeEvent("{}").argSummary)
    }

    @Test
    fun argSummaryFlattenedCommandIsNotAnObject() {
        val evt = makeEvent("make test")
        assertEquals("make test", evt.argSummary)
        assertNull(evt.commandString)
    }

    @Test
    fun equatablePinsAllFields() {
        val a = ToolCallEvent("Read", "{}", ToolCallEvent.Status.OK, "x", false,
            Instant.ofEpochMilli(1), null)
        val b = ToolCallEvent("Read", "{}", ToolCallEvent.Status.OK, "x", false,
            Instant.ofEpochMilli(1), null)
        val c = ToolCallEvent("Read", "{}", ToolCallEvent.Status.ERROR, "x", false,
            Instant.ofEpochMilli(1), null)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    private fun makeEvent(argsJSON: String): ToolCallEvent =
        ToolCallEvent("Bash", argsJSON, ToolCallEvent.Status.OK, null, false,
            Instant.ofEpochMilli(1), null)
}
