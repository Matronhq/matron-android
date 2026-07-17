package chat.matron.android.events

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.doubleOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/// Decoded form of a structured bot question. In production this is built
/// directly from a journal `prompt` or `permission_request` event's payload by
/// [chat.matron.android.chat.JournalTimelineMapper] (`askUserEvent` /
/// the `permission_request` branch) — [parse] and [parseButtons] below decode a
/// different, Matrix-era wire shape (`chat.matron.ask_user` custom events and
/// `chat.matron.buttons` content keys) that this journal client never receives;
/// they're unreachable from production and exercised only by `AskUserEventTest`.
///
/// Every reply — regardless of [kind] — goes out as a journal `prompt_reply` op.
/// [replyChannel] records which field the answer must be sent in: `choice=` for
/// a picked option's value, `text=` for free text. [expiresAt] is optional
/// (indefinitely-valid prompts omit it).
data class AskUserEvent(
    val prompt: String,
    val kind: InputKind,
    val expiresAt: Instant?,
    val replyChannel: ReplyChannel = ReplyChannel.TEXT_REPLY,
) {
    sealed interface InputKind {
        data object Text : InputKind
        data object Boolean : InputKind
        data class Choice(val options: List<Option>, val allowOther: kotlin.Boolean) : InputKind
        data class MultiChoice(val options: List<Option>, val allowOther: kotlin.Boolean) : InputKind
    }

    /// Which field of the journal `prompt_reply` op the answer must be sent in.
    enum class ReplyChannel {
        /// `prompt_reply { text: "<free text>" }`.
        TEXT_REPLY,
        /// `prompt_reply { choice: "<option value>" }` (or comma-joined values
        /// for a multi-choice answer).
        CHOICE_REPLY,
    }

    data class Option(
        val id: String,
        val label: String,
        /// The string sent back when this option is chosen. For `ask_user`
        /// options this equals `label`; for buttons it's the wire `value`, which
        /// can differ from the label.
        val value: String,
    ) {
        constructor(id: String, label: String) : this(id, label, label)
    }

    companion object {
        /// Parse a `chat.matron.ask_user` content object — a Matrix-era shape
        /// this journal client never receives (production builds [AskUserEvent]
        /// straight from journal `prompt` payloads instead; see the class doc).
        /// Dead in production; kept only because `AskUserEventTest` still
        /// exercises it. Returns `null` if `prompt` / `input.kind` are missing or
        /// `kind` is unknown.
        fun parse(content: JsonObject): AskUserEvent? {
            val prompt = content.stringOrNull("prompt") ?: return null
            val inputDict = content.objectOrNull("input") ?: return null
            val kindRaw = inputDict.stringOrNull("kind") ?: return null
            val allowOther = inputDict.boolOrNull("allow_other") ?: false
            val options = (inputDict.arrayOrNull("options")?.objects() ?: emptyList())
                .mapNotNull { dict ->
                    val id = dict.stringOrNull("id") ?: return@mapNotNull null
                    val label = dict.stringOrNull("label") ?: return@mapNotNull null
                    Option(id, label)
                }
            val kind = when (kindRaw) {
                "text" -> InputKind.Text
                "choice" -> InputKind.Choice(options, allowOther)
                "multi_choice" -> InputKind.MultiChoice(options, allowOther)
                "boolean" -> InputKind.Boolean
                else -> return null
            }
            val expiresAt = content.doubleOrNull("expires_at")?.let { Instant.ofEpochMilli(it.toLong()) }
            return AskUserEvent(prompt = prompt, kind = kind, expiresAt = expiresAt)
        }

        /// Parse the content of a message carrying a `chat.matron.buttons` key —
        /// another Matrix-era shape this journal client never receives (the
        /// journal server's `permission_request` event is the live equivalent,
        /// mapped separately; see the class doc). Dead in production; kept only
        /// because `AskUserEventTest` still exercises it. `mode` must be
        /// `pick_one`/`pick_many`, `prompt` must be present, and at least one
        /// button must parse with all three of `id`/`label`/`value` — otherwise
        /// `null`.
        fun parseButtons(content: JsonObject): AskUserEvent? {
            val buttonsData = content.objectOrNull(MatronEventType.BUTTONS) ?: return null
            val mode = buttonsData.stringOrNull("mode") ?: return null
            val prompt = buttonsData.stringOrNull("prompt") ?: return null
            val buttonsArr = buttonsData.arrayOrNull("buttons") ?: return null
            val options = buttonsArr.objects().mapNotNull { dict ->
                val id = dict.stringOrNull("id") ?: return@mapNotNull null
                val label = dict.stringOrNull("label") ?: return@mapNotNull null
                val value = dict.stringOrNull("value") ?: return@mapNotNull null
                Option(id, label, value)
            }
            if (options.isEmpty()) return null
            val kind = when (mode) {
                "pick_one" -> InputKind.Choice(options, allowOther = false)
                "pick_many" -> InputKind.MultiChoice(options, allowOther = false)
                else -> return null
            }
            return AskUserEvent(
                prompt = prompt,
                kind = kind,
                expiresAt = null,
                replyChannel = ReplyChannel.CHOICE_REPLY,
            )
        }
    }
}
