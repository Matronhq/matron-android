package chat.matron.android.chat

import chat.matron.android.events.AskUserEvent
import chat.matron.android.journal.MatronJsonPretty
import chat.matron.android.models.TimelineSendState
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/// Pretty-printed, JSON-shaped dump of the DTO for the long-press / right-click
/// "View source" sheet. Synthesises a JSON record from the DTO so the sheet is a
/// useful developer diagnostic surface. Output is real JSON (parseable), with a
/// nested `kind` object carrying a `type` discriminator and the kind's fields.
fun TimelineItem.prettyJSON(): String {
    val payload = buildJsonObject {
        put("id", id)
        put("sender", sender)
        put("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp))
        put("isOwn", isOwn)
        put("kind", kindAsJson())
        put("sendState", sendStateAsJson())
    }
    return MatronJsonPretty.encodeToString(JsonObject.serializer(), payload)
}

private fun TimelineItem.kindAsJson(): JsonObject = when (val k = kind) {
    is TimelineItem.Kind.Text -> buildJsonObject {
        put("type", "text")
        put("body", k.body)
        put("formattedHTML", k.formattedHTML?.let { JsonPrimitive(it) } ?: JsonNull)
    }
    is TimelineItem.Kind.Image -> buildJsonObject {
        put("type", "image")
        put("url", k.url?.let { JsonPrimitive(it) } ?: JsonNull)
        put("caption", k.caption?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sizeBytes", k.sizeBytes?.let { JsonPrimitive(it) } ?: JsonNull)
    }
    is TimelineItem.Kind.File -> buildJsonObject {
        put("type", "file")
        put("url", k.url?.let { JsonPrimitive(it) } ?: JsonNull)
        put("filename", k.filename)
        put("caption", k.caption?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sizeBytes", k.sizeBytes?.let { JsonPrimitive(it) } ?: JsonNull)
    }
    is TimelineItem.Kind.StateChange -> buildJsonObject {
        put("type", "stateChange")
        put("text", k.text)
    }
    is TimelineItem.Kind.ToolCall -> buildJsonObject {
        put("type", "toolCall")
        put("eventID", k.eventID)
        put("tool", k.event.tool)
        put("status", k.event.status.wire)
        put("argsJSON", k.event.argsJSON)
        put("resultText", k.event.resultText?.let { JsonPrimitive(it) } ?: JsonNull)
        put("resultTruncated", k.event.resultTruncated)
        put("startedAt", DateTimeFormatter.ISO_INSTANT.format(k.event.startedAt))
        put("endedAt", k.event.endedAt?.let { JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(it)) } ?: JsonNull)
    }
    is TimelineItem.Kind.Diff -> buildJsonObject {
        put("type", "diff")
        put("eventID", k.eventID)
        put("file", (k.event.displayPath ?: k.event.filePath)?.let { JsonPrimitive(it) } ?: JsonNull)
        put("tool", k.event.tool?.let { JsonPrimitive(it) } ?: JsonNull)
        put("label", k.event.label?.let { JsonPrimitive(it) } ?: JsonNull)
        put("added", k.event.added?.let { JsonPrimitive(it) } ?: JsonNull)
        put("removed", k.event.removed?.let { JsonPrimitive(it) } ?: JsonNull)
        put("truncated", k.event.truncated)
        put("newFile", k.event.newFile)
        put("diff", k.event.diff)
    }
    is TimelineItem.Kind.LiveOutput -> buildJsonObject {
        put("type", "liveOutput")
        put("eventID", k.eventID)
        put("toolUseID", k.event.toolUseID)
        put("command", k.event.command)
        put("viewerURL", k.event.viewerURL)
        put("expiresAt", k.event.expiresAt?.let { JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(it)) } ?: JsonNull)
    }
    is TimelineItem.Kind.AskUser -> buildJsonObject {
        put("type", "askUser")
        put("eventID", k.eventID)
        put("prompt", k.event.prompt)
        put("kind", askInputKindAsJson(k.event.kind))
        put("expiresAt", k.event.expiresAt?.let { JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(it)) } ?: JsonNull)
    }
    is TimelineItem.Kind.AskUserAnswer -> buildJsonObject {
        put("type", "askUserAnswer")
        put("promptEventID", k.promptEventID)
        put("selectedValues", buildJsonArray { k.selectedValues.forEach { add(JsonPrimitive(it)) } })
    }
    is TimelineItem.Kind.ActivityIndicator -> buildJsonObject {
        put("type", "activityIndicator")
        put("label", k.label)
    }
    is TimelineItem.Kind.ToolStreamLive -> buildJsonObject {
        put("type", "toolStreamLive")
        put("messageRef", k.messageRef)
        put("command", k.command?.let { JsonPrimitive(it) } ?: JsonNull)
        put("text", k.text)
        put("headTruncated", k.headTruncated)
    }
    is TimelineItem.Kind.Unknown -> buildJsonObject {
        put("type", "unknown")
        put("eventType", k.eventType)
    }
}

private fun askInputKindAsJson(kind: AskUserEvent.InputKind): JsonObject = when (kind) {
    is AskUserEvent.InputKind.Text -> buildJsonObject { put("kind", "text") }
    is AskUserEvent.InputKind.Boolean -> buildJsonObject { put("kind", "boolean") }
    is AskUserEvent.InputKind.Choice -> buildJsonObject {
        put("kind", "choice")
        put("allowOther", kind.allowOther)
        put("options", buildJsonArray {
            kind.options.forEach { opt ->
                add(buildJsonObject {
                    put("id", opt.id)
                    put("label", opt.label)
                    put("value", opt.value)
                })
            }
        })
    }
    is AskUserEvent.InputKind.MultiChoice -> buildJsonObject {
        put("kind", "multiChoice")
        put("allowOther", kind.allowOther)
        put("options", buildJsonArray {
            kind.options.forEach { opt ->
                add(buildJsonObject {
                    put("id", opt.id)
                    put("label", opt.label)
                    put("value", opt.value)
                })
            }
        })
    }
}

private fun TimelineItem.sendStateAsJson(): JsonObject = when (val s = sendState) {
    is TimelineSendState.Sent -> buildJsonObject { put("status", "sent") }
    is TimelineSendState.Sending -> buildJsonObject { put("status", "sending") }
    is TimelineSendState.Failed -> buildJsonObject {
        put("status", "failed")
        put("reason", s.reason)
    }
}
