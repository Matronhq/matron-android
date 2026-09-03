package chat.matron.android.chat

import chat.matron.android.events.AgentChatRequest
import chat.matron.android.events.AgentSpawnRequest
import chat.matron.android.events.AskUserEvent
import chat.matron.android.events.DiffEvent
import chat.matron.android.events.LiveOutputEvent
import chat.matron.android.events.SpawnOutcome
import chat.matron.android.events.ToolCallEvent
import chat.matron.android.journal.ActivityUpdate
import chat.matron.android.journal.JournalEvent
import chat.matron.android.journal.JournalEventType
import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.body
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.intOrNull
import chat.matron.android.journal.longOrNull
import chat.matron.android.journal.stringOrNull
import chat.matron.android.models.TimelineSendState
import java.time.Instant
import okhttp3.HttpUrl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/// Pure mapping from journal events to the render model. Unknown types get a
/// labeled fallback so the protocol can grow without lockstep upgrades. Ported
/// from matron-apple's `JournalTimelineMapper`.
object JournalTimelineMapper {
    /// The journal server's tool-log TTL: live-streamed output is purged
    /// server-side 24h after the event; the client rules bind the same TTL on
    /// local caches.
    const val TOOL_LOG_TTL_SECONDS: Long = 24L * 3600

    /// Payload `kind` shared by a bridge busy-queue card (`prompt`) and its
    /// durable release (`prompt_reply`).
    const val QUEUED_RELEASE_KIND = "queued_release"

    /// The hidden answer-row key a `queued_release` reply is filed under:
    /// namespaced so a bridge prompt id can never collide with an event seq.
    fun queuedReleaseAnswerKey(releasePromptID: String): String = "qr:$releasePromptID"

    fun displayName(sender: String): String {
        val colon = sender.indexOf(':')
        if (colon >= 0 && sender.substring(0, colon) in setOf("user", "agent")) {
            return sender.substring(colon + 1)
        }
        return sender
    }

    fun timelineItem(event: JournalEvent, ownSender: String, serverURL: HttpUrl): TimelineItem? {
        val payload = event.payload
        var inReplyTo: String? = null

        val kind: TimelineItem.Kind = when (event.type) {
            JournalEventType.READ_MARKER, JournalEventType.EDIT,
            JournalEventType.SESSION_STATUS, JournalEventType.CONVO_META,
            // Summary passes are TOC entries (the summaries sheet), never
            // transcript rows — without this they'd render as
            // "[unsupported event: summary]" noise.
            JournalEventType.SUMMARY -> return null

            JournalEventType.TEXT ->
                TimelineItem.Kind.Text(event.body() ?: "", null)

            JournalEventType.TOOL_OUTPUT -> {
                // A tool_output carrying a viewer_url is a live command-output
                // announcement — render the streaming tile. Everything else
                // stays a static card.
                val live = LiveOutputEvent.parse(payload)
                if (live != null) {
                    TimelineItem.Kind.LiveOutput(event.seq.toString(), live)
                } else {
                    TimelineItem.Kind.ToolCall(event.seq.toString(), toolCallEvent(payload, event.ts))
                }
            }

            JournalEventType.DIFF ->
                TimelineItem.Kind.Diff(event.seq.toString(), DiffEvent.parse(payload))

            JournalEventType.PROMPT ->
                TimelineItem.Kind.AskUser(event.seq.toString(), askUserEvent(payload))

            JournalEventType.PERMISSION_REQUEST -> {
                // The agent-chat consent card first: it carries none of the
                // keys the generic branch reads, so it used to render as the
                // literal string "Permission request" with Allow/Deny buttons
                // that answered over `prompt_reply` — a channel that never
                // reaches the parked row. The tap did nothing and the ask
                // expired 24h later. Agent-spawn is tried next, same reasoning.
                val agentChat = AgentChatRequest.parse(payload)
                val agentSpawn = AgentSpawnRequest.parse(payload)
                when {
                    agentChat != null ->
                        TimelineItem.Kind.AgentChatRequestCard(event.seq.toString(), agentChat)

                    agentSpawn != null ->
                        TimelineItem.Kind.AgentSpawnRequestCard(event.seq.toString(), agentSpawn)

                    // A consent-kind payload the parser rejected (malformed —
                    // e.g. no request_id). It answers over HTTP
                    // (`POST /agent-spawn/answer` / `/agent-chat/answer`),
                    // never `prompt_reply`, so the generic branch below would
                    // draw Allow/Deny buttons wired to a channel nothing
                    // reads — dead taps until the ask expires. Render an
                    // inert notice instead; web renders the spawn card
                    // read-only for the same case.
                    payload.stringOrNull("kind") in listOf("agent_spawn", "agent_chat") -> {
                        val headline = payload.stringOrNull("topic")?.takeIf { it.isNotBlank() }
                            ?: payload.stringOrNull("task")
                                ?.substringBefore('\n')?.takeIf { it.isNotBlank() }
                        TimelineItem.Kind.StateChange(
                            if (headline != null) {
                                "Agent request that can't be answered here: $headline"
                            } else {
                                "Agent request that can't be answered here"
                            },
                        )
                    }

                    else -> {
                        val description = payload.stringOrNull("description") ?: "Permission request"
                        val arr = payload.arrayOrNull("options")
                        val optionValues = if (arr != null && arr.all { it is JsonPrimitive && it.isString }) {
                            arr.map { (it as JsonPrimitive).content }
                        } else {
                            listOf("Allow", "Deny")
                        }
                        TimelineItem.Kind.AskUser(
                            event.seq.toString(),
                            AskUserEvent(
                                prompt = description,
                                kind = AskUserEvent.InputKind.Choice(
                                    optionValues.map { AskUserEvent.Option(it, it) },
                                    allowOther = false,
                                ),
                                // The journal protocol carries no expiry on
                                // permission_request/prompt payloads — always null
                                // here (same as AskUserEvent.swift; expiry is a
                                // legacy Matrix-era field the VMs still honor).
                                expiresAt = null,
                                replyChannel = AskUserEvent.ReplyChannel.CHOICE_REPLY,
                            ),
                        )
                    }
                }
            }

            JournalEventType.PROMPT_REPLY -> {
                if (payload.stringOrNull("kind") == QUEUED_RELEASE_KIND) {
                    // A bridge-authored queued_release resolution: prompt_id +
                    // action, no target_seq and no choice. Hide it as an answer
                    // row namespaced by the bridge prompt id ("qr:pr_…") so a
                    // flush retires every sent card's buttons on every device —
                    // the generic branches below would render it as an empty
                    // text bubble and resolve nothing. A release is never meant
                    // to be visible, so a malformed one (no prompt_id / no
                    // action) drops entirely rather than falling through to
                    // that empty bubble (port of apple #162).
                    val releasePromptID = payload.stringOrNull("prompt_id") ?: return null
                    val action = payload.stringOrNull("action") ?: return null
                    TimelineItem.Kind.AskUserAnswer(queuedReleaseAnswerKey(releasePromptID), listOf(action))
                } else {
                    val ir = payload.longOrNull("target_seq")?.toString()
                    inReplyTo = ir
                    val choice = payload.stringOrNull("choice")
                    when {
                        choice != null && ir != null -> TimelineItem.Kind.AskUserAnswer(ir, listOf(choice))
                        choice != null -> TimelineItem.Kind.Unknown(JournalEventType.PROMPT_REPLY)
                        else -> TimelineItem.Kind.Text(payload.stringOrNull("text") ?: "", null)
                    }
                }
            }

            JournalEventType.FILE, JournalEventType.IMAGE -> {
                val url = payload.stringOrNull("blob_ref")?.let {
                    serverURL.newBuilder().addPathSegment("media").addPathSegment(it).build().toString()
                }
                val size = payload.longOrNull("size")
                val caption = payload.stringOrNull("caption")
                if (event.type == JournalEventType.IMAGE) {
                    TimelineItem.Kind.Image(url, caption, size)
                } else {
                    // `name`, not `filename`: the key the media-send contract
                    // defines and both producers emit.
                    TimelineItem.Kind.File(url, payload.stringOrNull("name") ?: "file", caption, size)
                }
            }

            JournalEventType.SPAWN_OUTCOME -> {
                val outcome = SpawnOutcome.parse(payload)
                if (outcome != null) {
                    TimelineItem.Kind.SpawnOutcomeRow(event.seq.toString(), outcome)
                } else {
                    TimelineItem.Kind.Unknown(event.type)
                }
            }

            else -> TimelineItem.Kind.Unknown(event.type)
        }

        return TimelineItem(
            id = event.seq.toString(),
            sender = displayName(event.sender),
            timestamp = event.ts,
            kind = kind,
            isOwn = event.sender == ownSender,
            sendState = TimelineSendState.Sent,
            inReplyToEventID = inReplyTo,
        )
    }

    fun toolCallEvent(payload: JsonObject, ts: Instant, now: Instant = Instant.now()): ToolCallEvent {
        // Rich payloads (bridge keeps chat.matron.tool_call keys) parse directly.
        ToolCallEvent.parse(payload)?.let { return it }
        // Command-completion shape (and its server tombstone): command as the
        // tool's args, snippet as the result, exit_code/denied driving status.
        val command = payload.stringOrNull("command")
        if (!command.isNullOrEmpty()) {
            val exitCode = payload.intOrNull("exit_code")
            val denied = payload.boolOrNull("denied") ?: false
            var expired = payload.boolOrNull("expired") ?: false
            // Binding client TTL rule: a cached live_log snippet must stop
            // rendering once ts + 24h passes locally, without waiting for the
            // server's tombstone re-sync.
            if (!expired && payload.boolOrNull("live_log") == true &&
                !now.isBefore(ts.plusSeconds(TOOL_LOG_TTL_SECONDS))
            ) {
                expired = true
            }
            return ToolCallEvent(
                tool = commandLabel(command),
                argsJSON = command,
                status = if (denied || (exitCode ?: 0) != 0) ToolCallEvent.Status.ERROR else ToolCallEvent.Status.OK,
                resultText = if (expired) null else payload.stringOrNull("snippet"),
                resultTruncated = payload.boolOrNull("truncated") ?: false,
                startedAt = ts,
                endedAt = null,
                exitCode = exitCode,
                denied = denied,
                expired = expired,
            )
        }
        return ToolCallEvent(
            tool = payload.stringOrNull("tool_name") ?: "tool",
            argsJSON = "{}",
            status = ToolCallEvent.Status.OK,
            resultText = payload.stringOrNull("snippet"),
            resultTruncated = payload.boolOrNull("truncated") ?: false,
            startedAt = ts,
            endedAt = null,
        )
    }

    /// A short label for a shell command: the first whitespace-delimited token
    /// of the first non-empty line, bounded to 24 chars; empty input →
    /// "command".
    fun commandLabel(command: String): String {
        val firstLine = command.split(Regex("[\\n\\r]")).firstOrNull { it.isNotEmpty() } ?: ""
        val token = firstLine.split(Regex("\\s+")).firstOrNull { it.isNotEmpty() } ?: ""
        return if (token.isEmpty()) "command" else token.take(24)
    }

    fun askUserEvent(payload: JsonObject): AskUserEvent {
        val question = payload.stringOrNull("question") ?: ""
        val allowsFreeText = payload.boolOrNull("allows_free_text") ?: false
        val options = mutableListOf<AskUserEvent.Option>()
        for (raw in payload.arrayOrNull("options") ?: emptyList()) {
            when {
                raw is JsonPrimitive && raw.isString ->
                    options.add(AskUserEvent.Option(raw.content, raw.content))
                raw is JsonObject -> {
                    val label = raw.stringOrNull("label") ?: continue
                    options.add(
                        AskUserEvent.Option(
                            id = raw.stringOrNull("id") ?: label,
                            label = label,
                            value = raw.stringOrNull("value") ?: label,
                        )
                    )
                }
            }
        }
        val kind: AskUserEvent.InputKind = when {
            options.isEmpty() -> AskUserEvent.InputKind.Text
            payload.stringOrNull("mode") == "pick_many" ->
                AskUserEvent.InputKind.MultiChoice(options, allowsFreeText)
            else -> AskUserEvent.InputKind.Choice(options, allowsFreeText)
        }
        // Busy-queue cards carry the bridge-owned prompt id their durable
        // release frames will later name — see the PROMPT_REPLY branch.
        val queuedReleasePromptID =
            if (payload.stringOrNull("kind") == QUEUED_RELEASE_KIND) payload.stringOrNull("prompt_id") else null
        return AskUserEvent(
            prompt = question,
            kind = kind,
            // No expiry field exists in the journal protocol's prompt payload —
            // see the permission_request mapping above.
            expiresAt = null,
            replyChannel = if (options.isEmpty()) AskUserEvent.ReplyChannel.TEXT_REPLY
            else AskUserEvent.ReplyChannel.CHOICE_REPLY,
            queuedReleasePromptID = queuedReleasePromptID,
        )
    }

    fun streamingItem(messageRef: String, text: String, convoTS: Instant): TimelineItem = TimelineItem(
        id = "eph:$messageRef",
        sender = "agent",
        timestamp = convoTS,
        kind = TimelineItem.Kind.Text(text, null),
        isOwn = false,
        sendState = TimelineSendState.Sent,
    )

    /// Human label for an activity indicator. `null` for `.idle` — the caller
    /// renders nothing.
    fun activityLabel(state: ActivityUpdate.State, detail: String?): String? = when (state) {
        ActivityUpdate.State.THINKING -> "Thinking…"
        ActivityUpdate.State.TOOL -> {
            val trimmed = detail?.trim()
            if (!trimmed.isNullOrEmpty()) "Running $trimmed" else "Working…"
        }
        ActivityUpdate.State.IDLE -> null
    }

    fun activityItem(label: String, convoTS: Instant): TimelineItem = TimelineItem(
        id = "activity",
        sender = "agent",
        timestamp = convoTS,
        kind = TimelineItem.Kind.ActivityIndicator(label),
        isOwn = false,
        sendState = TimelineSendState.Sent,
    )

    /// Renders a tool-stream byte buffer for display. Keeps only the last
    /// [displayCapBytes], drops any orphaned continuation bytes at the front of
    /// the cut, and trims any incomplete multibyte sequence at the tail (a chunk
    /// boundary can split a character).
    fun toolStreamText(bytes: ByteArray, displayCapBytes: Int = 65536): String {
        var start = 0
        var end = bytes.size
        if (end - start > displayCapBytes) {
            start = end - displayCapBytes
            while (start < end && (bytes[start].toInt() and 0xC0) == 0x80) start++
        }
        // Walk back over trailing continuation bytes to the lead byte; trim an
        // incomplete sequence.
        var index = end
        var walked = 0
        while (walked < 4 && index > start) {
            val previous = index - 1
            val b = bytes[previous].toInt() and 0xFF
            if (b and 0x80 == 0) break // ASCII tail — complete
            walked++
            if (b and 0xC0 == 0xC0) { // lead byte of a multibyte sequence
                val needed = if (b >= 0xF0) 4 else if (b >= 0xE0) 3 else 2
                if (walked < needed) end = previous
                break
            }
            index = previous
        }
        return String(bytes.copyOfRange(start, end), Charsets.UTF_8)
    }

    fun toolStreamItem(
        messageRef: String,
        command: String?,
        text: String,
        headTruncated: Boolean,
        convoTS: Instant,
    ): TimelineItem = TimelineItem(
        id = "toolstream:$messageRef",
        sender = "agent",
        timestamp = convoTS,
        kind = TimelineItem.Kind.ToolStreamLive(messageRef, command, text, headTruncated),
        isOwn = false,
        sendState = TimelineSendState.Sent,
    )
}
