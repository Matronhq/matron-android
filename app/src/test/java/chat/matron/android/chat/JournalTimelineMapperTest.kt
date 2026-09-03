package chat.matron.android.chat

import chat.matron.android.events.AskUserEvent
import chat.matron.android.journal.ActivityUpdate
import chat.matron.android.journal.JournalEvent
import java.time.Instant
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/// Ported from matron-apple's `JournalTimelineMapperTests`.
class JournalTimelineMapperTest {
    private val server = "https://chat.example.com".toHttpUrl()

    private fun ev(
        seq: Long,
        type: String,
        sender: String = "agent:dev-2",
        ts: Instant = Instant.ofEpochSecond(1000),
        payload: JsonObject,
    ) = JournalEvent(seq, "c1", ts, sender, type, payload)

    private fun map(e: JournalEvent) = JournalTimelineMapper.timelineItem(e, "user:dan", server)

    @Test fun textEvent() {
        val item = map(ev(5, "text", payload = buildJsonObject { put("body", "hello") }))!!
        assertEquals("5", item.id)
        assertEquals("dev-2", item.sender)
        assertFalse(item.isOwn)
        assertEquals(TimelineItem.Kind.Text("hello", null), item.kind)
    }

    @Test fun ownDetection() {
        val item = map(ev(1, "text", sender = "user:dan", payload = buildJsonObject { put("body", "x") }))!!
        assertTrue(item.isOwn)
        assertEquals("dan", item.sender)
    }

    @Test fun toolOutputFallbackConstruction() {
        val item = map(ev(2, "tool_output", payload = buildJsonObject {
            put("tool_name", "Bash"); put("snippet", "ls -la"); put("truncated", true)
        }))!!
        val kind = item.kind as TimelineItem.Kind.ToolCall
        assertEquals("2", kind.eventID)
        assertEquals("Bash", kind.event.tool)
        assertEquals("ls -la", kind.event.resultText)
        assertTrue(kind.event.resultTruncated)
        assertEquals(chat.matron.android.events.ToolCallEvent.Status.OK, kind.event.status)
    }

    @Test fun toolOutputWithViewerURLBecomesLiveOutput() {
        val item = map(ev(3, "tool_output", payload = buildJsonObject {
            put("tool_use_id", "toolu_01ABC")
            put("command", "grep -rn \"needle\" src/ | head -40")
            put("viewer_url", "https://viewer2.example.com/live?token=eyJhbGc")
            put("expires_at", 1_760_000_000L)
        }))!!
        val kind = item.kind as TimelineItem.Kind.LiveOutput
        assertEquals("3", kind.eventID)
        assertEquals("toolu_01ABC", kind.event.toolUseID)
        assertEquals("grep -rn \"needle\" src/ | head -40", kind.event.command)
        assertEquals(Instant.ofEpochSecond(1_760_000_000), kind.event.expiresAt)
    }

    @Test fun toolOutputCommandWithoutViewerURLStaysToolCall() {
        val item = map(ev(3, "tool_output", payload = buildJsonObject {
            put("tool_use_id", "toolu_01ABC")
            put("command", "grep -rn \"needle\" src/ | head -40")
        }))!!
        val kind = item.kind as TimelineItem.Kind.ToolCall
        assertEquals("grep", kind.event.tool)
        assertEquals("grep -rn \"needle\" src/ | head -40", kind.event.argsJSON)
    }

    @Test fun toolOutputFreshShapeRendersSnippetAndExit() {
        val item = map(ev(6, "tool_output", ts = Instant.now(), payload = buildJsonObject {
            put("message_ref", "toolu_01A"); put("command", "make test")
            put("exit_code", 0); put("denied", false); put("truncated", true)
            put("snippet", "$ make test\nAll 12 tests passed")
            put("blob_ref", "blob-1"); put("live_log", true)
        }))!!
        val e = (item.kind as TimelineItem.Kind.ToolCall).event
        assertEquals("make", e.tool)
        assertEquals("make test", e.argsJSON)
        assertEquals(chat.matron.android.events.ToolCallEvent.Status.OK, e.status)
        assertEquals("$ make test\nAll 12 tests passed", e.resultText)
        assertTrue(e.resultTruncated)
        assertEquals(0, e.exitCode)
        assertFalse(e.denied)
        assertFalse(e.expired)
    }

    @Test fun toolOutputNonzeroExitIsError() {
        val item = map(ev(7, "tool_output", ts = Instant.now(), payload = buildJsonObject {
            put("message_ref", "toolu_01B"); put("command", "make test")
            put("exit_code", 2); put("denied", false); put("truncated", false)
            put("snippet", "error: no rule"); put("live_log", true)
        }))!!
        val e = (item.kind as TimelineItem.Kind.ToolCall).event
        assertEquals(chat.matron.android.events.ToolCallEvent.Status.ERROR, e.status)
        assertEquals(2, e.exitCode)
        assertEquals("error: no rule", e.resultText)
    }

    @Test fun toolOutputDeniedIsError() {
        val item = map(ev(8, "tool_output", payload = buildJsonObject {
            put("message_ref", "toolu_01C"); put("command", "rm -rf /")
            put("denied", true); put("truncated", false); put("live_log", true)
        }))!!
        val e = (item.kind as TimelineItem.Kind.ToolCall).event
        assertEquals(chat.matron.android.events.ToolCallEvent.Status.ERROR, e.status)
        assertTrue(e.denied)
    }

    @Test fun toolOutputTombstoneRendersExpired() {
        val item = map(ev(9, "tool_output", payload = buildJsonObject {
            put("message_ref", "toolu_01D"); put("command", "make"); put("exit_code", 0)
            put("denied", false); put("truncated", false); put("live_log", true); put("expired", true)
        }))!!
        val e = (item.kind as TimelineItem.Kind.ToolCall).event
        assertTrue(e.expired)
        assertNull(e.resultText)
        assertEquals(0, e.exitCode)
        assertEquals(chat.matron.android.events.ToolCallEvent.Status.OK, e.status)
    }

    @Test fun toolOutputLocalTTLExpiresStaleCachedSnippet() {
        val payload = buildJsonObject {
            put("message_ref", "toolu_01E"); put("command", "ls")
            put("exit_code", 0); put("snippet", "file.txt"); put("live_log", true)
        }
        val ts = Instant.ofEpochSecond(1000)
        val fresh = JournalTimelineMapper.toolCallEvent(payload, ts, ts.plusSeconds(23 * 3600))
        assertFalse(fresh.expired)
        assertEquals("file.txt", fresh.resultText)

        val stale = JournalTimelineMapper.toolCallEvent(payload, ts, ts.plusSeconds(25 * 3600))
        assertTrue(stale.expired)
        assertNull(stale.resultText)
    }

    @Test fun toolOutputTTLDoesNotTouchNonLiveLog() {
        val payload = buildJsonObject { put("command", "ls"); put("snippet", "file.txt") }
        val ts = Instant.ofEpochSecond(1000)
        val old = JournalTimelineMapper.toolCallEvent(payload, ts, ts.plusSeconds(48 * 3600))
        assertFalse(old.expired)
        assertEquals("file.txt", old.resultText)
    }

    @Test fun toolOutputMultilineCommandLabelIsFirstToken() {
        val item = map(ev(4, "tool_output", payload = buildJsonObject {
            put("command", "cd /home/x\necho hi\ngrep foo bar")
        }))!!
        assertEquals("cd", (item.kind as TimelineItem.Kind.ToolCall).event.tool)
    }

    @Test fun diffEventMapsToRichDiffKind() {
        val item = map(ev(42, "diff", payload = buildJsonObject {
            put("file_path", "/w/Sources/A.swift"); put("display_path", "Sources/A.swift")
            put("viewer_url", "https://v.example/view?token=t"); put("tool", "Edit")
            put("diff", "@@ -1,1 +1,1 @@\n-a\n+b"); put("added", 1); put("removed", 1)
            put("truncated", false); put("new_file", false)
        }))!!
        val kind = item.kind as TimelineItem.Kind.Diff
        assertEquals("42", kind.eventID)
        assertEquals("A.swift", kind.event.filename)
        assertEquals(1, kind.event.added)
        assertFalse(item.isOwn)
    }

    @Test fun bareDiffPayloadStillRenders() {
        val item = map(ev(43, "diff", payload = buildJsonObject { put("diff", "+only") }))!!
        val kind = item.kind as TimelineItem.Kind.Diff
        assertEquals("+only", kind.event.diff)
        assertNull(kind.event.filename)
    }

    @Test fun convoMetaIsSkipped() {
        assertNull(map(ev(5, "convo_meta", payload = buildJsonObject { put("title", "New title") })))
    }

    @Test fun promptWithOptions() {
        val item = map(ev(3, "prompt", payload = buildJsonObject {
            put("question", "Deploy?")
            putJsonArray("options") {
                addJsonObject { put("id", "y"); put("label", "Yes") }
                addJsonObject { put("id", "n"); put("label", "No") }
            }
            put("allows_free_text", true)
        }))!!
        val kind = item.kind as TimelineItem.Kind.AskUser
        assertEquals("3", kind.eventID)
        assertEquals("Deploy?", kind.event.prompt)
        assertEquals(AskUserEvent.ReplyChannel.CHOICE_REPLY, kind.event.replyChannel)
        val choice = kind.event.kind as AskUserEvent.InputKind.Choice
        assertEquals(listOf("Yes", "No"), choice.options.map { it.label })
        assertTrue(choice.allowOther)
    }

    @Test fun promptWithoutOptionsIsFreeText() {
        val item = map(ev(4, "prompt", payload = buildJsonObject { put("question", "Name?") }))!!
        val kind = item.kind as TimelineItem.Kind.AskUser
        assertEquals(AskUserEvent.ReplyChannel.TEXT_REPLY, kind.event.replyChannel)
        assertEquals(AskUserEvent.InputKind.Text, kind.event.kind)
    }

    @Test fun permissionRequestWithStringOptionsUsesThemVerbatim() {
        val item = map(ev(13, "permission_request", payload = buildJsonObject {
            put("description", "Allow network access?")
            putJsonArray("options") { add(JsonPrimitive("Allow")); add(JsonPrimitive("Deny once")) }
        }))!!
        val kind = item.kind as TimelineItem.Kind.AskUser
        assertEquals("13", kind.eventID)
        assertEquals("Allow network access?", kind.event.prompt)
        assertEquals(AskUserEvent.ReplyChannel.CHOICE_REPLY, kind.event.replyChannel)
        val choice = kind.event.kind as AskUserEvent.InputKind.Choice
        assertEquals(listOf("Allow", "Deny once"), choice.options.map { it.label })
        assertEquals(listOf("Allow", "Deny once"), choice.options.map { it.value })
        assertFalse(choice.allowOther)
    }

    @Test fun permissionRequestWithoutOptionsFallsBackToAllowDeny() {
        val item = map(ev(14, "permission_request", payload = buildJsonObject {
            put("description", "Run this command?")
        }))!!
        val kind = item.kind as TimelineItem.Kind.AskUser
        val choice = kind.event.kind as AskUserEvent.InputKind.Choice
        assertEquals(listOf("Allow", "Deny"), choice.options.map { it.label })
        assertFalse(choice.allowOther)
    }

    @Test fun permissionRequestWithNonStringOptionsFallsBackToAllowDeny() {
        val item = map(ev(15, "permission_request", payload = buildJsonObject {
            put("description", "Run this command?")
            putJsonArray("options") {
                addJsonObject { put("id", "y"); put("label", "Yes") }
                addJsonObject { put("id", "n"); put("label", "No") }
            }
        }))!!
        val kind = item.kind as TimelineItem.Kind.AskUser
        val choice = kind.event.kind as AskUserEvent.InputKind.Choice
        assertEquals(listOf("Allow", "Deny"), choice.options.map { it.label })
    }

    @Test fun permissionRequestWithMixedOptionsFallsBackToAllowDeny() {
        val item = map(ev(16, "permission_request", payload = buildJsonObject {
            put("description", "Run this command?")
            putJsonArray("options") { add(JsonPrimitive("Allow")); add(JsonPrimitive(1)) }
        }))!!
        val kind = item.kind as TimelineItem.Kind.AskUser
        val choice = kind.event.kind as AskUserEvent.InputKind.Choice
        assertEquals(listOf("Allow", "Deny"), choice.options.map { it.label })
    }

    @Test fun permissionRequestMissingDescriptionUsesDefaultPrompt() {
        val item = map(ev(17, "permission_request", payload = buildJsonObject { }))!!
        val kind = item.kind as TimelineItem.Kind.AskUser
        assertEquals("Permission request", kind.event.prompt)
    }

    @Test fun permissionRequestAllowOtherIsAlwaysFalse() {
        // Even with a rich options array (which for `prompt` would leave
        // allowOther to the payload's `allows_free_text`), permission_request
        // never exposes a free-text escape hatch — options are the only path.
        val item = map(ev(18, "permission_request", payload = buildJsonObject {
            put("description", "Overwrite file?")
            putJsonArray("options") { add(JsonPrimitive("Yes")); add(JsonPrimitive("No")) }
        }))!!
        val kind = item.kind as TimelineItem.Kind.AskUser
        val choice = kind.event.kind as AskUserEvent.InputKind.Choice
        assertFalse(choice.allowOther)
    }

    @Test fun promptReplyWithChoiceHidesAsAnswer() {
        val item = map(ev(6, "prompt_reply", sender = "user:dan", payload = buildJsonObject {
            put("target_seq", 3); put("choice", "Yes")
        }))!!
        val kind = item.kind as TimelineItem.Kind.AskUserAnswer
        assertEquals("3", kind.promptEventID)
        assertEquals(listOf("Yes"), kind.selectedValues)
        assertEquals("3", item.inReplyToEventID)
    }

    @Test fun promptReplyWithTextRendersAsReply() {
        val item = map(ev(7, "prompt_reply", sender = "user:dan", payload = buildJsonObject {
            put("target_seq", 4); put("text", "call it matron")
        }))!!
        assertEquals(TimelineItem.Kind.Text("call it matron", null), item.kind)
        assertEquals("4", item.inReplyToEventID)
    }

    @Test fun promptReplyWithoutTargetFallsBackToUnknown() {
        val item = map(ev(12, "prompt_reply", sender = "user:dan", payload = buildJsonObject {
            put("choice", "Yes")
        }))!!
        assertEquals(TimelineItem.Kind.Unknown("prompt_reply"), item.kind)
        assertNull(item.inReplyToEventID)
    }

    // MARK: - queued_release (bridge busy-queue cards, apple #162)

    @Test fun queuedReleasePromptCarriesItsPromptID() {
        val item = map(ev(20, "prompt", payload = buildJsonObject {
            put("kind", "queued_release"); put("prompt_id", "pr_abc")
            put("question", "Send all 2 queued messages now, or cancel this one?")
            put("options", buildJsonArray {
                add(buildJsonObject { put("id", "send"); put("label", "⚡ Send all now"); put("value", "send") })
                add(buildJsonObject { put("id", "cancel"); put("label", "✕ Cancel this"); put("value", "cancel") })
            })
            put("mode", "pick_one")
        }))!!
        val kind = item.kind as TimelineItem.Kind.AskUser
        assertEquals("pr_abc", kind.event.queuedReleasePromptID)
    }

    @Test fun ordinaryPromptHasNoQueuedReleasePromptID() {
        val item = map(ev(3, "prompt", payload = buildJsonObject {
            put("question", "Deploy?")
            put("options", buildJsonArray { add(buildJsonObject { put("id", "y"); put("label", "Yes") }) })
        }))!!
        assertNull((item.kind as TimelineItem.Kind.AskUser).event.queuedReleasePromptID)
    }

    /// The bridge's durable release frame carries prompt_id + action but no
    /// target_seq and no choice — it must hide as a namespaced answer row
    /// (retiring the card's buttons on every device), not render as an empty
    /// text bubble.
    @Test fun queuedReleaseReplyHidesAsNamespacedAnswer() {
        val item = map(ev(21, "prompt_reply", sender = "agent:bridge", payload = buildJsonObject {
            put("kind", "queued_release"); put("prompt_id", "pr_abc"); put("action", "send")
            put("released", buildJsonArray { add("pr_abc::0") })
        }))!!
        val kind = item.kind as TimelineItem.Kind.AskUserAnswer
        assertEquals("qr:pr_abc", kind.promptEventID)
        assertEquals(listOf("send"), kind.selectedValues)
        assertNull(item.inReplyToEventID)
    }

    /// A release frame is never meant to be visible, so a malformed one (kind
    /// says queued_release but prompt_id or action is missing) must drop
    /// entirely — falling through to the generic prompt_reply path would
    /// render it as an empty text bubble, the exact symptom the release
    /// branch exists to remove.
    @Test fun malformedQueuedReleaseReplyIsDropped() {
        assertNull(map(ev(22, "prompt_reply", sender = "agent:bridge", payload = buildJsonObject {
            put("kind", "queued_release"); put("prompt_id", "pr_abc")
        })))
        assertNull(map(ev(23, "prompt_reply", sender = "agent:bridge", payload = buildJsonObject {
            put("kind", "queued_release"); put("action", "send")
        })))
    }

    @Test fun imageBuildsMediaURL() {
        val item = map(ev(8, "image", payload = buildJsonObject {
            put("blob_ref", "b123"); put("content_type", "image/png")
        }))!!
        val kind = item.kind as TimelineItem.Kind.Image
        assertEquals("https://chat.example.com/media/b123", kind.url)
    }

    @Test fun skippedAndUnknownTypes() {
        assertNull(map(ev(9, "read_marker", payload = buildJsonObject { put("up_to_seq", 5) })))
        assertNull(map(ev(10, "session_status", payload = buildJsonObject { put("state", "done") })))
        val item = map(ev(11, "shiny_new_thing", payload = buildJsonObject { put("x", 1) }))!!
        assertEquals(TimelineItem.Kind.Unknown("shiny_new_thing"), item.kind)
    }

    /// Port of matron-apple `JournalTimelineMapperTests
    /// .testSummaryEventsAreExcludedFromTranscript`: summary passes are TOC
    /// entries, never transcript rows — without the mapper exclusion they'd
    /// render as "[unsupported event: summary]" noise.
    @Test fun summaryEventsAreExcludedFromTranscript() {
        assertNull(map(ev(11, "summary", payload = buildJsonObject {
            put("toc", "Fixed auth"); put("detail", "…"); put("model", "m")
        })))
    }

    @Test fun streamingItem() {
        val item = JournalTimelineMapper.streamingItem("m1", "working…", Instant.ofEpochSecond(99))
        assertEquals("eph:m1", item.id)
        assertEquals(TimelineItem.Kind.Text("working…", null), item.kind)
    }

    @Test fun activityLabels() {
        assertEquals("Thinking…", JournalTimelineMapper.activityLabel(ActivityUpdate.State.THINKING, null))
        assertEquals("Running Bash", JournalTimelineMapper.activityLabel(ActivityUpdate.State.TOOL, "Bash"))
        assertEquals("Working…", JournalTimelineMapper.activityLabel(ActivityUpdate.State.TOOL, "  "))
        assertEquals("Working…", JournalTimelineMapper.activityLabel(ActivityUpdate.State.TOOL, null))
        assertNull(JournalTimelineMapper.activityLabel(ActivityUpdate.State.IDLE, null))
    }

    @Test fun activityItem() {
        val item = JournalTimelineMapper.activityItem("Thinking…", Instant.ofEpochSecond(99))
        assertEquals("activity", item.id)
        assertFalse(item.isOwn)
        assertEquals(TimelineItem.Kind.ActivityIndicator("Thinking…"), item.kind)
    }

    @Test fun toolStreamItemShape() {
        val item = JournalTimelineMapper.toolStreamItem("tu1", "make test", "$ make test\nok\n", false, Instant.ofEpochSecond(5000))
        assertEquals("toolstream:tu1", item.id)
        assertEquals("agent", item.sender)
        assertFalse(item.isOwn)
        assertEquals(Instant.ofEpochSecond(5000), item.timestamp)
        assertEquals(TimelineItem.Kind.ToolStreamLive("tu1", "make test", "$ make test\nok\n", false), item.kind)
    }

    @Test fun toolStreamTextDropsIncompleteTrailingMultibyte() {
        assertEquals("ok", JournalTimelineMapper.toolStreamText(byteArrayOf(0x6F, 0x6B, 0xC3.toByte())))
        assertEquals("oké", JournalTimelineMapper.toolStreamText(byteArrayOf(0x6F, 0x6B, 0xC3.toByte(), 0xA9.toByte())))
        val emojiMissingLast = byteArrayOf(0x6F) + "😀".toByteArray().dropLast(1).toByteArray()
        assertEquals("o", JournalTimelineMapper.toolStreamText(emojiMissingLast))
        assertEquals("done\n", JournalTimelineMapper.toolStreamText("done\n".toByteArray()))
    }

    @Test fun toolStreamTextCapsDisplayToTail() {
        val bytes = "a".repeat(100).toByteArray()
        assertEquals("a".repeat(10), JournalTimelineMapper.toolStreamText(bytes, 10))
        val multi = "xx😀".toByteArray() // 2 + 4 bytes
        assertEquals("", JournalTimelineMapper.toolStreamText(multi, 3))
    }

    @Test fun fileEventUsesTheNameKey() {
        val item = map(ev(1, "file", payload = buildJsonObject {
            put("blob_ref", "b1"); put("name", "quarterly-report.pdf")
            put("content_type", "application/pdf"); put("size", 1234)
        }))!!
        val kind = item.kind as TimelineItem.Kind.File
        assertEquals("quarterly-report.pdf", kind.filename)
        assertEquals(1234L, kind.sizeBytes)
    }

    @Test fun fileEventWithoutANameFallsBackToPlaceholder() {
        val item = map(ev(1, "file", payload = buildJsonObject { put("blob_ref", "b1") }))!!
        assertEquals("file", (item.kind as TimelineItem.Kind.File).filename)
    }

    @Test fun imageEventCarriesItsCaption() {
        val item = map(ev(1, "image", payload = buildJsonObject {
            put("blob_ref", "b2"); put("name", "cat.png"); put("content_type", "image/png")
            put("caption", "what breed is this?")
        }))!!
        val kind = item.kind as TimelineItem.Kind.Image
        assertEquals("what breed is this?", kind.caption)
        assertEquals("https://chat.example.com/media/b2", kind.url)
    }

    @Test fun fileEventCarriesItsCaption() {
        val item = map(ev(1, "file", payload = buildJsonObject {
            put("blob_ref", "b3"); put("name", "contract.pdf"); put("caption", "review this before Friday")
        }))!!
        assertEquals("review this before Friday", (item.kind as TimelineItem.Kind.File).caption)
    }

    // MARK: Agent-chat consent card

    @Test fun agentChatPermissionRequestMapsToItsOwnKind() {
        val item = map(ev(31, "permission_request", payload = buildJsonObject {
            put("kind", "agent_chat")
            put("request", "invite")
            put("room_id", "room-1")
            put("from_device_id", 4)
            put("from_name", "dev-2")
            put("target_device_id", 7)
            put("topic", "ci triage")
            put("justification", "need the build log")
        }))!!
        val kind = item.kind as TimelineItem.Kind.AgentChatRequestCard
        assertEquals("31", kind.eventID)
        assertEquals("room-1", kind.request.roomID)
        assertEquals(7L, kind.request.targetDeviceID)
    }

    /// The regression this whole kind exists for: the agent-chat card has no
    /// `description`/`options`, so the generic branch rendered it as the literal
    /// words "Permission request" with Allow/Deny buttons that answered over
    /// `prompt_reply` — a channel that never reaches the parked row, so the tap
    /// did nothing at all.
    @Test fun agentChatCardNoLongerFallsBackToAGenericPrompt() {
        val item = map(ev(32, "permission_request", payload = buildJsonObject {
            put("kind", "agent_chat")
            put("request", "join")
            put("room_id", "r")
            put("from_device_id", 4)
            put("target_device_id", 4)
        }))!!
        assertFalse(item.kind is TimelineItem.Kind.AskUser)
    }

    /// A card whose payload is missing something the answer call needs is
    /// unanswerable — and its answer channel is HTTP, not `prompt_reply`, so
    /// the generic card's Allow/Deny would be dead buttons. Rendered as an
    /// inert notice instead.
    @Test fun unanswerableAgentChatPayloadRendersAsAnInertNotice() {
        val item = map(ev(33, "permission_request", payload = buildJsonObject {
            put("kind", "agent_chat")
            put("request", "invite")
            put("from_device_id", 4)
        }))!!
        assertTrue(item.kind is TimelineItem.Kind.StateChange)
    }

    // MARK: Agent-spawn consent card

    @Test fun agentSpawnPermissionRequestMapsToItsOwnKind() {
        val item = map(ev(40, "permission_request", payload = buildJsonObject {
            put("kind", "agent_spawn")
            put("request_id", "spawn-1")
            put("from_device_id", 4)
            put("from_name", "dev-2")
            put("target_device_id", 7)
            put("workdir", "/home/dev/project")
            put("task", "Fix the failing build")
        }))!!
        val kind = item.kind as TimelineItem.Kind.AgentSpawnRequestCard
        assertEquals("40", kind.eventID)
        assertEquals("spawn-1", kind.request.requestId)
        assertEquals(7L, kind.request.targetDeviceId)
    }

    /// The dispatch order matters: agent-chat is tried first, so a payload
    /// carrying `kind: "agent_chat"` must keep mapping to
    /// `AgentChatRequestCard` even though `AgentSpawnRequest.parse` is now
    /// also in the branch.
    @Test fun agentSpawnDispatchDoesNotShadowAgentChatPriority() {
        val item = map(ev(41, "permission_request", payload = buildJsonObject {
            put("kind", "agent_chat")
            put("request", "invite")
            put("room_id", "room-1")
            put("from_device_id", 4)
            put("target_device_id", 7)
        }))!!
        assertTrue(item.kind is TimelineItem.Kind.AgentChatRequestCard)
    }

    /// A spawn card missing what the answer call needs (`task`) is
    /// unanswerable — the generic card's Allow/Deny would post over
    /// `prompt_reply`, a channel the spawn flow never reads (it answers over
    /// `POST /agent-spawn/answer`), leaving dead buttons until the ask
    /// expires. Rendered as an inert notice instead, same stance as
    /// agent-chat; web renders the spawn card read-only for this case.
    @Test fun unanswerableAgentSpawnPayloadRendersAsAnInertNotice() {
        val item = map(ev(42, "permission_request", payload = buildJsonObject {
            put("kind", "agent_spawn")
            put("request_id", "spawn-1")
            put("from_device_id", 4)
            put("topic", "Flake hunt")
        }))!!
        val kind = item.kind as TimelineItem.Kind.StateChange
        assertTrue(kind.text.contains("Flake hunt"))
    }

    @Test fun spawnOutcomeEventMapsToItsOwnKind() {
        val item = map(ev(43, "spawn_outcome", sender = "journal", payload = buildJsonObject {
            put("request_id", "spawn-1")
            put("outcome", "started")
            put("room_id", "room-9")
            put("child_convo_id", "child-1")
        }))!!
        val kind = item.kind as TimelineItem.Kind.SpawnOutcomeRow
        assertEquals("43", kind.eventID)
        assertEquals("spawn-1", kind.outcome.requestId)
        assertEquals("room-9", kind.outcome.roomId)
    }

    /// A malformed spawn_outcome (missing what's needed to correlate it back
    /// to a card) falls back to the generic unknown-type rendering rather
    /// than crashing.
    @Test fun unparseableSpawnOutcomeFallsBackToUnknown() {
        val item = map(ev(44, "spawn_outcome", sender = "journal", payload = buildJsonObject {
            put("outcome", "started")
        }))!!
        assertEquals(TimelineItem.Kind.Unknown("spawn_outcome"), item.kind)
    }

}
