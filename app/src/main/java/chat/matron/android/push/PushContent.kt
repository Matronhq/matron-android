package chat.matron.android.push

import chat.matron.android.journal.objectOrNull
import chat.matron.android.journal.stringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/// What a notification says, as the journal sealed it for this device
/// (matron-journal `docs/protocol.md`, "Sealed notification content").
/// Unknown keys are ignored; a version this build does not know does not
/// decode, and the notification keeps its fixed text. Port of matron-apple's
/// `PushContent`.
data class PushContent(
    val title: String,
    val subtitle: String?,
    val body: String,
    val convoID: String,
    val seq: Long?,
    val kind: String,
    val item: Item?,
) {
    data class Item(
        val id: String,
        val number: Int,
        val kind: String,
        /// The buttons the item offers now, at most four.
        val actions: List<String>,
        /// The comment the buttons belong to; null for the item's own.
        val replyTo: String?,
    )

    companion object {
        const val SUPPORTED_VERSION = 1L
        const val MAX_ACTIONS = 4

        private val json = Json { ignoreUnknownKeys = true }

        /// Decodes the opened plaintext: UTF-8 JSON, padded with spaces.
        fun parse(plaintext: ByteArray): PushContent? {
            val obj = runCatching { json.parseToJsonElement(plaintext.decodeToString()) as? JsonObject }.getOrNull() ?: return null
            val version = (obj["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (version != SUPPORTED_VERSION) return null
            val title = obj.stringOrNull("title") ?: return null
            val body = obj.stringOrNull("body") ?: return null
            val convoID = obj.stringOrNull("convo_id")?.takeIf { it.isNotEmpty() } ?: return null
            val seq = (obj["seq"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
            val item = obj.objectOrNull("item")?.let { raw ->
                val id = raw.stringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return@let null
                val actions = (raw["actions"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotEmpty() } }
                Item(
                    id = id,
                    number = (raw["num"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.toInt() ?: 0,
                    kind = raw.stringOrNull("kind") ?: "",
                    actions = actions.take(MAX_ACTIONS),
                    replyTo = raw.stringOrNull("reply_to")?.takeIf { it.isNotEmpty() },
                )
            }
            return PushContent(
                title = title,
                subtitle = obj.stringOrNull("subtitle")?.takeIf { it.isNotEmpty() },
                body = body,
                convoID = convoID,
                seq = seq,
                kind = obj.stringOrNull("kind") ?: "",
                item = item,
            )
        }
    }
}
