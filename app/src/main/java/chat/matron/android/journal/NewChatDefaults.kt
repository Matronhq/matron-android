package chat.matron.android.journal

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/// The user's default model and effort for new chats (journal `GET`/`PUT
/// /defaults`, live as `{kind:"defaults", default_model, default_effort}`).
/// Every bridge applies them to a Claude chat started without a choice of
/// its own; `null` is "Box default": each box's own setting. Ported from
/// matron-apple's `NewChatDefaults`.
data class NewChatDefaults(
    val model: String? = null,
    val effort: String? = null,
) {
    /// The two settings, keyed as the journal names them.
    enum class Key(val wire: String, val title: String, val errorName: String) {
        MODEL("default_model", "Default model", "default model"),
        EFFORT("default_effort", "Default effort", "default effort"),
    }

    operator fun get(key: Key): String? = when (key) {
        Key.MODEL -> model
        Key.EFFORT -> effort
    }

    fun with(key: Key, value: String?): NewChatDefaults = when (key) {
        Key.MODEL -> copy(model = value)
        Key.EFFORT -> copy(effort = value)
    }

    companion object {
        /// A `GET /defaults` answer, a `PUT /defaults` answer, or a live
        /// `defaults` frame (whose `kind` is ignored). A missing, null or
        /// empty value is "Box default"; any other non-string rejects the
        /// whole body, so a malformed frame never half-applies.
        fun decode(obj: JsonObject): NewChatDefaults? {
            var out = NewChatDefaults()
            for (key in Key.entries) {
                when (val raw = obj[key.wire]) {
                    null, is JsonNull -> Unit
                    is JsonPrimitive -> {
                        if (!raw.isString) return null
                        out = out.with(key, raw.content.ifEmpty { null })
                    }
                    else -> return null
                }
            }
            return out
        }
    }
}

/// `GET` / `PUT /defaults`. An interface so `NewChatDefaultsStore` tests
/// fake it.
interface NewChatDefaultsProviding {
    /// Throws [JournalApiError.NotFound] on a journal that predates per-user
    /// defaults.
    suspend fun newChatDefaults(): NewChatDefaults

    /// Sets one key (`null` = Box default) and returns both as the journal
    /// stored them (trimmed and lowercased). The other key is left as is.
    suspend fun setNewChatDefault(key: NewChatDefaults.Key, value: String?): NewChatDefaults
}
