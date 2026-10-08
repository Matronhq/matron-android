package chat.matron.android.journal

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/// One agent box's defaults for new sessions (journal "Box defaults": `GET
/// /devices` `defaults`, `PUT /devices/:id/defaults`, live as `box_defaults`):
/// the agent, model and effort a session started on that box gets when
/// nobody names them. `null` is "Box default": the bridge's own
/// `MATRON_DEFAULT_*` fallback. Ported from matron-apple's `BoxDefaults`.
///
/// The model and effort belong to the box's default agent — a Claude alias
/// on a Claude box, a Codex model id on a Codex box — so the journal clears
/// the model when the agent changes ([applying]).
data class BoxDefaults(
    val agent: String? = null,
    val model: String? = null,
    val effort: String? = null,
) {
    /// The three settings, keyed as `PUT /devices/:id/defaults` names them
    /// ([wire]) and as `GET /devices`'s `defaults` block does ([rosterKey]).
    enum class Key(val wire: String, val rosterKey: String) {
        AGENT("default_agent", "agent"),
        MODEL("default_model", "model"),
        EFFORT("default_effort", "effort"),
    }

    operator fun get(key: Key): String? = when (key) {
        Key.AGENT -> agent
        Key.MODEL -> model
        Key.EFFORT -> effort
    }

    fun with(key: Key, value: String?): BoxDefaults = when (key) {
        Key.AGENT -> copy(agent = value)
        Key.MODEL -> copy(model = value)
        Key.EFFORT -> copy(effort = value)
    }

    /// What the journal stores for a `PUT` of [key] alone: the value, and —
    /// for a new agent — no model, since the old one belonged to the old
    /// agent. Effort is kept. The same agent again is no change.
    fun applying(key: Key, value: String?): BoxDefaults {
        val next = with(key, value)
        return if (key == Key.AGENT && value != agent) next.copy(model = null) else next
    }

    companion object {
        /// A `GET /devices` agent entry's `defaults` block: `{agent, model,
        /// effort}`.
        fun decodeRoster(obj: JsonObject): BoxDefaults? = decode(obj) { it.rosterKey }

        /// A `PUT /devices/:id/defaults` answer or a live `box_defaults`
        /// frame: `{device_id, default_agent, default_model, default_effort}`
        /// (the other keys are ignored).
        fun decodeState(obj: JsonObject): BoxDefaults? = decode(obj) { it.wire }

        /// A missing, null or empty value is "Box default"; any other
        /// non-string rejects the whole block, so a malformed one never
        /// half-applies.
        private fun decode(obj: JsonObject, name: (Key) -> String): BoxDefaults? {
            var defaults = BoxDefaults()
            for (key in Key.entries) {
                when (val raw = obj[name(key)]) {
                    null, is JsonNull -> Unit
                    is JsonPrimitive -> {
                        if (!raw.isString) return null
                        defaults = defaults.with(key, raw.content.ifEmpty { null })
                    }
                    else -> return null
                }
            }
            return defaults
        }
    }
}

/// A live `box_defaults` frame: the full new state of one box's defaults,
/// from any device's or agent's `PUT`, including this device's own echo.
data class BoxDefaultsUpdate(val deviceID: Long, val defaults: BoxDefaults)
