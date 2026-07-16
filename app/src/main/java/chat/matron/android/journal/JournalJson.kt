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

/// Pretty-printing variant used only by the "View source" DTO dump.
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

/// Convenience: each element of an array read as an object (skipping non-objects).
fun JsonArray.objects(): List<JsonObject> = mapNotNull { it as? JsonObject }
