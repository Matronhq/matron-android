package chat.matron.android.events

import chat.matron.android.journal.arrayOrNull
import chat.matron.android.journal.boolOrNull
import chat.matron.android.journal.doubleOrNull
import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.objects
import chat.matron.android.journal.stringOrNull
import java.time.Instant
import kotlinx.serialization.json.JsonObject

/// Decoded form of a structured bot question, from either of the two wire
/// protocols:
///
/// - `chat.matron.ask_user` custom events via [parse] — the forward-looking
///   contract for the bridge.
/// - `chat.matron.buttons` content keys on ordinary messages via
///   [parseButtons] — the protocol the bridge emits today.
///
/// [replyChannel] records which protocol the prompt arrived on so the reply
/// path can answer in kind. [expiresAt] is optional (indefinitely-valid prompts
/// omit it; the buttons protocol has no expiry field — always null there).
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

    /// How the user's answer must be sent back so the bot can correlate it.
    enum class ReplyChannel {
        /// Ordinary text reply pointing at the prompt event (`ask_user`).
        TEXT_REPLY,
        /// `chat.matron.button_response: { selected_values }` (Matron X buttons).
        BUTTON_RESPONSE,
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
        /// Parse a `chat.matron.ask_user` content object. Returns `null` if
        /// `prompt` / `input.kind` are missing or `kind` is unknown.
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

        /// Parse the content of a message carrying a `chat.matron.buttons` key
        /// (the bridge's live protocol). `mode` must be `pick_one`/`pick_many`,
        /// `prompt` must be present, and at least one button must parse with all
        /// three of `id`/`label`/`value` — otherwise `null`.
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
                replyChannel = ReplyChannel.BUTTON_RESPONSE,
            )
        }
    }
}
