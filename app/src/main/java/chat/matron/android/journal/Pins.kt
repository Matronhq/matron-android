package chat.matron.android.journal

import chat.matron.android.chat.SessionTag
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/// The newer session on the pin's box the app offers to move the pin to
/// ("New session on <box> — move pin here?").
data class ConvoPinSuccessor(
    val convoID: String,
    val title: String,
    val createdAt: Long,
)

/// A journal-backed pinned desk chat (journal protocol "Pinned desk
/// chats"): up to [Pins.DEFAULT_LIMIT] conversations the user names and
/// orders, shared by every client. Ported from matron-web's `ConvoPin`.
data class ConvoPin(
    val convoID: String,
    /// One line, 1–24 code points; the row's primary text.
    val label: String,
    /// A short token, or "" to draw the label's first letter instead.
    val emoji: String,
    val position: Int,
    /// The box the pinned conversation runs on, when the journal knows it.
    val deviceID: Long?,
    /// The pinned conversation's row no longer exists: the entry greys out
    /// and offers only Move pin… and Unpin.
    val missing: Boolean = false,
    val successor: ConvoPinSuccessor? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/// The whole list as every `/pins` route answers it.
data class PinList(val pins: List<ConvoPin>, val limit: Int)

/// A `/pins` write the journal refused with a reason the user can act on
/// (400 bad label/emoji, 404 not owned / not pinned, 409 `pin_limit` or
/// `already_pinned`). [message] is already in words ([Pins.errorMessage]).
class PinRequestError(
    val status: Int,
    val detail: String?,
    val limit: Int?,
) : Exception(Pins.errorMessage(status, detail, limit))

/// Parsing and the small rules every pin surface shares. Ported from
/// matron-web's `src/journal/pins.ts`; a journal that predates pins omits
/// the `pins` key from `GET /snapshot` and `hello_ok`, which reads as
/// "pins unsupported" (null), never as "no pins".
object Pins {
    /// The journal's cap when a response does not say (protocol: max 5).
    const val DEFAULT_LIMIT = 5
    /// A pin label is one line of 1–24 characters (code points).
    const val LABEL_MAX = 24
    /// The emoji field's cap, in UTF-16 units (the journal's rule).
    const val EMOJI_MAX = 16
    const val LABEL_FALLBACK = "Pinned chat"

    private fun parseSuccessor(raw: JsonElement?): ConvoPinSuccessor? {
        val obj = raw as? JsonObject ?: return null
        val id = obj.stringOrNull("convo_id")?.takeIf { it.isNotEmpty() } ?: return null
        return ConvoPinSuccessor(id, obj.stringOrNull("title") ?: "", obj.longOrNull("created_at") ?: 0)
    }

    /// One pin, or null when the row lacks what a row needs (id + label).
    fun parsePin(raw: JsonElement?): ConvoPin? {
        val obj = raw as? JsonObject ?: return null
        val id = obj.stringOrNull("convo_id")?.takeIf { it.isNotEmpty() } ?: return null
        val label = obj.stringOrNull("label")?.takeIf { it.isNotBlank() } ?: return null
        return ConvoPin(
            convoID = id,
            label = label,
            emoji = obj.stringOrNull("emoji") ?: "",
            position = obj.intOrNull("position") ?: 0,
            deviceID = obj.longOrNull("device_id"),
            missing = obj.boolOrNull("missing") == true,
            successor = parseSuccessor(obj["successor"]),
            createdAt = obj.longOrNull("created_at") ?: 0,
            updatedAt = obj.longOrNull("updated_at") ?: 0,
        )
    }

    /// A pin array in `position` order, malformed rows and repeats dropped;
    /// null when [raw] is not an array.
    fun parseList(raw: JsonElement?): List<ConvoPin>? {
        val array = raw as? JsonArray ?: return null
        val seen = mutableSetOf<String>()
        return array.mapNotNull { entry -> parsePin(entry)?.takeIf { seen.add(it.convoID) } }
            .sortedBy { it.position }
    }

    /// The `pins` a snapshot or `hello_ok` body carries: the list when the
    /// journal supports pins, null when the key is absent (an older journal)
    /// or unusable.
    fun fromContainer(container: JsonObject): List<ConvoPin>? {
        if (!container.containsKey("pins")) return null
        return parseList(container["pins"])
    }

    /// A `{pins, limit}` body from any `/pins` route; throws on a body with
    /// no pin array.
    fun parseResponse(obj: JsonObject): PinList {
        val pins = parseList(obj["pins"]) ?: throw JournalApiError.Transport("malformed pins response")
        val limit = obj.intOrNull("limit")?.takeIf { it > 0 } ?: DEFAULT_LIMIT
        return PinList(pins, limit)
    }

    /// The inline copy for a refused pin write: the journal's 409s get their
    /// own words.
    fun errorMessage(status: Int, detail: String?, limit: Int?): String = when {
        status == 409 && detail == "pin_limit" -> "You can pin up to ${limit?.takeIf { it > 0 } ?: DEFAULT_LIMIT} chats."
        status == 409 && detail == "already_pinned" -> "That chat is already pinned."
        status == 404 -> "That chat isn't available to pin any more."
        status == 400 -> "That label or emoji isn't allowed."
        else -> "Couldn't update pins (HTTP $status)."
    }

    /// A label fitting the journal's rule: one line, trimmed, at most 24
    /// code points (an emoji counts once, never split).
    fun clampLabel(text: String): String {
        val line = text.replace(Regex("\\s+"), " ").trim()
        val count = line.codePointCount(0, line.length)
        if (count <= LABEL_MAX) return line
        return line.substring(0, line.offsetByCodePoints(0, LABEL_MAX)).trim()
    }

    /// The emoji field as the journal takes it: no whitespace, at most 16
    /// UTF-16 units, never ending in half a surrogate pair.
    fun clampEmoji(text: String): String {
        val token = text.filterNot { it.isWhitespace() }
        if (token.length <= EMOJI_MAX) return token
        val cut = token.take(EMOJI_MAX)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    /// The label a conversation's title suggests: the bridge's marker
    /// (`🐣 `, `↔️ `…) and `[ab] ` session short peeled off.
    fun labelFromTitle(title: String): String {
        fun peelMarker(text: String): String =
            SessionTag.titleMarkers.firstOrNull { text.startsWith(it) }?.let { text.removePrefix(it) } ?: text
        val (_, rest) = SessionTag.splitTitle(peelMarker(title.trim()))
        return clampLabel(peelMarker(rest)).ifEmpty { LABEL_FALLBACK }
    }

    /// The glyph a pin row leads with: its emoji, else the label's first
    /// letter upper-cased.
    fun glyph(emoji: String, label: String): String {
        emoji.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return "?"
        return String(Character.toChars(trimmed.codePointAt(0))).uppercase()
    }

    fun glyph(pin: ConvoPin): String = glyph(pin.emoji, pin.label)

    enum class Direction { UP, DOWN }

    /// The full order after moving [convoID] one step; null when it cannot
    /// move (an end, or not pinned).
    fun movedOrder(pins: List<ConvoPin>, convoID: String, direction: Direction): List<String>? {
        val order = pins.map { it.convoID }.toMutableList()
        val index = order.indexOf(convoID)
        val target = if (direction == Direction.UP) index - 1 else index + 1
        if (index == -1 || target !in order.indices) return null
        order[index] = order[target].also { order[target] = order[index] }
        return order
    }

    /// "New session on <box> — move pin here?", naming the pin's box (the
    /// journal offers a successor only on the box the pin was made on),
    /// else "this box".
    fun successorHintText(pin: ConvoPin, boxNames: Map<Long, String>): String {
        val name = pin.deviceID?.let { boxNames[it] }?.trim()?.takeIf { it.isNotEmpty() }
        return "New session on ${name ?: "this box"} — move pin here?"
    }

    // MARK: Local cache
    //
    // The last list this device adopted, so a cold start draws the Pinned
    // section before the socket's hello_ok (which then replaces it).

    fun encodeList(pins: List<ConvoPin>): String = buildJsonArray {
        pins.forEach { pin ->
            add(buildJsonObject {
                put("convo_id", pin.convoID)
                put("label", pin.label)
                put("emoji", pin.emoji)
                put("position", pin.position)
                pin.deviceID?.let { put("device_id", it) }
                if (pin.missing) put("missing", true)
                pin.successor?.let { s ->
                    put("successor", buildJsonObject {
                        put("convo_id", s.convoID)
                        put("title", s.title)
                        put("created_at", s.createdAt)
                    })
                }
                put("created_at", pin.createdAt)
                put("updated_at", pin.updatedAt)
            })
        }
    }.toString()

    fun decodeList(text: String): List<ConvoPin>? =
        runCatching { parseList(MatronJson.parseToJsonElement(text)) }.getOrNull()
}
