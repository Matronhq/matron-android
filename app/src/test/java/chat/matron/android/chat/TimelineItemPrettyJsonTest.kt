package chat.matron.android.chat

import chat.matron.android.events.AskUserEvent
import chat.matron.android.events.DiffEvent
import chat.matron.android.events.LiveOutputEvent
import chat.matron.android.events.ToolCallEvent
import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.parseJsonObjectOrNull
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/// Covers the `kindAsJson` branches [TimelineItemTest]'s "prettyJSON()" section
/// doesn't reach (ToolCall, Diff, LiveOutput, AskUser's four input kinds,
/// AskUserAnswer, ActivityIndicator, ToolStreamLive, Unknown, StateChange), plus
/// the malformed-JSON-content path: several `Kind`s carry opaque strings
/// (`argsJSON`, a diff's `diff` text) that may themselves be invalid or
/// arbitrary JSON-ish text — `prettyJSON()` must embed them as escaped string
/// values rather than choke on or re-parse them.
class TimelineItemPrettyJsonTest {
    private fun item(id: String, kind: TimelineItem.Kind, isOwn: Boolean = false) = TimelineItem(
        id = id,
        sender = "@a:s",
        timestamp = Instant.ofEpochSecond(0),
        kind = kind,
        isOwn = isOwn,
    )

    private fun kindOf(item: TimelineItem) = parseJsonObjectOrNull(item.prettyJSON())!!.objectOrNull("kind")!!

    @Test
    fun stateChangeKindIncludesText() {
        val kind = kindOf(item("$1", TimelineItem.Kind.StateChange("alice joined")))
        assertEquals("stateChange", kind.stringOrNull("type"))
        assertEquals("alice joined", kind.stringOrNull("text"))
    }

    @Test
    fun toolCallKindIncludesAllFieldsWhenPresent() {
        val evt = ToolCallEvent(
            tool = "Bash",
            argsJSON = "{\"command\":\"make test\"}",
            status = ToolCallEvent.Status.OK,
            resultText = "ok output",
            resultTruncated = true,
            startedAt = Instant.ofEpochSecond(1000),
            endedAt = Instant.ofEpochSecond(1005),
        )
        val kind = kindOf(item("$2", TimelineItem.Kind.ToolCall("$2", evt)))
        assertEquals("toolCall", kind.stringOrNull("type"))
        assertEquals("$2", kind.stringOrNull("eventID"))
        assertEquals("Bash", kind.stringOrNull("tool"))
        assertEquals("ok", kind.stringOrNull("status"))
        assertEquals(evt.argsJSON, kind.stringOrNull("argsJSON"))
        assertEquals("ok output", kind.stringOrNull("resultText"))
        assertEquals(true, kind.boolOrNull("resultTruncated"))
        assertNotNull(kind.stringOrNull("startedAt"))
        assertNotNull(kind.stringOrNull("endedAt"))
    }

    @Test
    fun toolCallKindNullOptionalFieldsSerializeAsExplicitJsonNull() {
        val evt = ToolCallEvent(
            tool = "Bash",
            argsJSON = "{}",
            status = ToolCallEvent.Status.RUNNING,
            resultText = null,
            resultTruncated = false,
            startedAt = Instant.ofEpochSecond(0),
            endedAt = null,
        )
        val kind = kindOf(item("$3", TimelineItem.Kind.ToolCall("$3", evt)))
        // encodeDefaults=false only elides @Serializable default params; these
        // are explicit buildJsonObject `put`s, so the keys stay present as null.
        assertEquals(JsonNull, kind["resultText"])
        assertEquals(JsonNull, kind["endedAt"])
    }

    @Test
    fun toolCallKindWithMalformedArgsJSONEmbedsItAsAnOpaqueEscapedString() {
        // argsJSON is a raw String field on ToolCallEvent; nothing guarantees the
        // bridge sent well-formed JSON in it. prettyJSON() must not attempt to
        // re-parse/re-embed it as raw JSON (which would break the overall output
        // the moment argsJSON was malformed) — it goes in as a JSON string value.
        val malformed = "{not: valid, json"
        val evt = ToolCallEvent(
            tool = "Bash",
            argsJSON = malformed,
            status = ToolCallEvent.Status.ERROR,
            resultText = null,
            resultTruncated = false,
            startedAt = Instant.ofEpochSecond(0),
            endedAt = null,
        )
        val output = item("$4", TimelineItem.Kind.ToolCall("$4", evt)).prettyJSON()
        val parsed = parseJsonObjectOrNull(output)
        assertNotNull("overall prettyJSON output must stay valid JSON despite malformed argsJSON content", parsed)
        val kind = parsed!!.objectOrNull("kind")!!
        assertEquals(malformed, kind.stringOrNull("argsJSON"))
    }

    @Test
    fun diffKindPrefersDisplayPathOverFilePath() {
        val evt = DiffEvent(filePath = "/abs/path/file.txt", displayPath = "short/file.txt", diff = "diff-body")
        val kind = kindOf(item("$5", TimelineItem.Kind.Diff("$5", evt)))
        assertEquals("short/file.txt", kind.stringOrNull("file"))
    }

    @Test
    fun diffKindFallsBackToFilePathWhenDisplayPathMissing() {
        val evt = DiffEvent(filePath = "/abs/path/file.txt", displayPath = null, diff = "diff-body")
        val kind = kindOf(item("$6", TimelineItem.Kind.Diff("$6", evt)))
        assertEquals("/abs/path/file.txt", kind.stringOrNull("file"))
    }

    @Test
    fun diffKindWithQuotesAndNewlinesInDiffTextStaysValidJSON() {
        // Diff bodies are arbitrary source-code text — quotes, newlines,
        // backslashes — not JSON at all. Must round-trip as an escaped string.
        val body = "-old \"quoted\"\n+new line\\with backslash"
        val evt = DiffEvent(filePath = "a.txt", diff = body)
        val output = item("$7", TimelineItem.Kind.Diff("$7", evt)).prettyJSON()
        val parsed = parseJsonObjectOrNull(output)
        assertNotNull(parsed)
        assertEquals(body, parsed!!.objectOrNull("kind")!!.stringOrNull("diff"))
    }

    @Test
    fun liveOutputKindIncludesViewerURLAndExpiry() {
        val evt = LiveOutputEvent(
            toolUseID = "tu1",
            command = "make test",
            viewerURL = "https://host/live?token=abc",
            expiresAt = Instant.ofEpochSecond(1_800_000_000),
        )
        val kind = kindOf(item("$8", TimelineItem.Kind.LiveOutput("$8", evt)))
        assertEquals("liveOutput", kind.stringOrNull("type"))
        assertEquals("tu1", kind.stringOrNull("toolUseID"))
        assertEquals("make test", kind.stringOrNull("command"))
        assertEquals("https://host/live?token=abc", kind.stringOrNull("viewerURL"))
        assertNotNull(kind["expiresAt"])
    }

    @Test
    fun askUserKindTextInput() {
        val evt = AskUserEvent("Continue?", AskUserEvent.InputKind.Text, null)
        val outer = kindOf(item("$9", TimelineItem.Kind.AskUser("$9", evt)))
        assertEquals("askUser", outer.stringOrNull("type"))
        assertEquals("Continue?", outer.stringOrNull("prompt"))
        assertEquals("text", outer.objectOrNull("kind")!!.stringOrNull("kind"))
    }

    @Test
    fun askUserKindBooleanInput() {
        val evt = AskUserEvent("Proceed?", AskUserEvent.InputKind.Boolean, null)
        val outer = kindOf(item("$10", TimelineItem.Kind.AskUser("$10", evt)))
        assertEquals("boolean", outer.objectOrNull("kind")!!.stringOrNull("kind"))
    }

    @Test
    fun askUserKindChoiceInputIncludesOptions() {
        val options = listOf(AskUserEvent.Option("a", "Option A"), AskUserEvent.Option("b", "Option B"))
        val evt = AskUserEvent("Pick one", AskUserEvent.InputKind.Choice(options, allowOther = true), null)
        val inputKind = kindOf(item("$11", TimelineItem.Kind.AskUser("$11", evt))).objectOrNull("kind")!!
        assertEquals("choice", inputKind.stringOrNull("kind"))
        assertEquals(true, inputKind.boolOrNull("allowOther"))
        val opts = inputKind.arrayOrNull("options")!!.objects()
        assertEquals(2, opts.size)
        assertEquals("a", opts[0].stringOrNull("id"))
        assertEquals("Option A", opts[0].stringOrNull("label"))
        assertEquals("Option A", opts[0].stringOrNull("value"))
    }

    @Test
    fun askUserKindMultiChoiceInputIncludesOptions() {
        val options = listOf(AskUserEvent.Option("x", "X label", "xval"))
        val evt = AskUserEvent("Pick many", AskUserEvent.InputKind.MultiChoice(options, allowOther = false), null)
        val inputKind = kindOf(item("$12", TimelineItem.Kind.AskUser("$12", evt))).objectOrNull("kind")!!
        assertEquals("multiChoice", inputKind.stringOrNull("kind"))
        assertEquals(false, inputKind.boolOrNull("allowOther"))
        assertEquals("xval", inputKind.arrayOrNull("options")!!.objects()[0].stringOrNull("value"))
    }

    @Test
    fun askUserAnswerKindIncludesPromptEventIDAndSelectedValues() {
        val kind = kindOf(
            item(
                "$13",
                TimelineItem.Kind.AskUserAnswer(promptEventID = "$9", selectedValues = listOf("yes", "maybe")),
                isOwn = true,
            ),
        )
        assertEquals("askUserAnswer", kind.stringOrNull("type"))
        assertEquals("$9", kind.stringOrNull("promptEventID"))
        val values = kind.arrayOrNull("selectedValues")!!.map { it.jsonPrimitive.content }
        assertEquals(listOf("yes", "maybe"), values)
    }

    @Test
    fun activityIndicatorKindIncludesLabel() {
        val kind = kindOf(item("$14", TimelineItem.Kind.ActivityIndicator("Thinking…")))
        assertEquals("activityIndicator", kind.stringOrNull("type"))
        assertEquals("Thinking…", kind.stringOrNull("label"))
    }

    @Test
    fun toolStreamLiveKindIncludesFieldsAndNullCommand() {
        val kind = kindOf(
            item(
                "$15",
                TimelineItem.Kind.ToolStreamLive(
                    messageRef = "ref1",
                    command = null,
                    text = "partial output",
                    headTruncated = true,
                ),
            ),
        )
        assertEquals("toolStreamLive", kind.stringOrNull("type"))
        assertEquals("ref1", kind.stringOrNull("messageRef"))
        assertNull(kind.stringOrNull("command"))
        assertEquals("partial output", kind.stringOrNull("text"))
        assertEquals(true, kind.boolOrNull("headTruncated"))
    }

    @Test
    fun unknownKindIncludesEventType() {
        val kind = kindOf(item("$16", TimelineItem.Kind.Unknown("m.custom.thing")))
        assertEquals("unknown", kind.stringOrNull("type"))
        assertEquals("m.custom.thing", kind.stringOrNull("eventType"))
    }
}
