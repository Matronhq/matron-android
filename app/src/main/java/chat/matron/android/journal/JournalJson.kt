package chat.matron.android.journal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/// Shared JSON codec for the whole protocol layer. `ignoreUnknownKeys` keeps
/// the client forward-compatible with server additions; `encodeDefaults = false`
/// so absent-means-unchanged fields don't get serialized as nulls.
val MatronJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/// Pretty-printing variant used by the "View source" DTO dump and by
/// [chat.matron.android.events.ToolCallEvent]'s sorted-key JSON rendering of
/// tool-call args/results.
val MatronJsonPretty: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    prettyPrint = true
}

/// Parses `text` into a JSON object, returning `null` for non-JSON input or a
/// non-object top level — the Kotlin analogue of Swift's
/// `JSONSerialization.jsonObject(...) as? [String: Any]`.
fun parseJsonObjectOrNull(text: String): JsonObject? =
    runCatching { MatronJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

// The accessors below mirror Swift's `as?` bridging semantics on top of a
// `JSONSerialization`-decoded dictionary: a string field only reads from a JSON
// string, a numeric field only from a JSON number (never a numeric string), and
// a JSON `null` reads as absent. This keeps parse-failure behavior identical to
// the Swift originals (e.g. a string `started_at` must fail the parse).

private fun JsonObject.primitive(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

fun JsonObject.stringOrNull(key: String): String? =
    primitive(key)?.takeIf { it.isString }?.content

fun JsonObject.boolOrNull(key: String): Boolean? {
    val p = primitive(key) ?: return null
    if (p.isString) return null
    return when (p.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

fun JsonObject.doubleOrNull(key: String): Double? {
    val p = primitive(key) ?: return null
    if (p.isString) return null
    return p.content.toDoubleOrNull()
}

fun JsonObject.intOrNull(key: String): Int? {
    val p = primitive(key) ?: return null
    if (p.isString) return null
    return p.content.toIntOrNull() ?: p.content.toDoubleOrNull()?.toInt()
}

fun JsonObject.longOrNull(key: String): Long? {
    val p = primitive(key) ?: return null
    if (p.isString) return null
    return p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.toLong()
}

fun JsonObject.objectOrNull(key: String): JsonObject? =
    (this[key] as? JsonObject)

fun JsonObject.arrayOrNull(key: String): JsonArray? =
    (this[key] as? JsonArray)

/// An array of JSON numbers read as longs, or `null` when the key is absent,
/// not an array, or ANY element isn't numeric — mirroring the Swift originals'
/// all-or-nothing `as? [NSNumber]` cast (a partially-numeric array must not
/// half-parse into a shorter list).
fun JsonObject.longArrayOrNull(key: String): List<Long>? {
    val array = arrayOrNull(key) ?: return null
    val longs = ArrayList<Long>(array.size)
    for (element in array) {
        val p = (element as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return null
        if (p.isString) return null
        longs.add(p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.toLong() ?: return null)
    }
    return longs
}

/// Convenience: each element of an array read as an object (skipping non-objects).
fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }
