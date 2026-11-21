package talq.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// The server is PHP: an empty map arrives as `[]`, numbers sometimes as strings, booleans as
// 0/1. Models are therefore read by hand through these tolerant accessors instead of through
// generated serializers, which would throw on the first such surprise.

internal val TalkJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal val EmptyJsonObject = JsonObject(emptyMap())

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

internal fun JsonElement?.asText(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.str(key: String, default: String = ""): String = this[key].asText() ?: default

internal fun JsonObject.long(key: String, default: Long = 0L): Long {
    val p = this[key] as? JsonPrimitive ?: return default
    if (p is JsonNull) return default
    return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.booleanOrNull?.let { if (it) 1L else 0L } ?: default
}

internal fun JsonObject.int(key: String, default: Int = 0): Int = long(key, default.toLong()).toInt()

internal fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
    val p = this[key] as? JsonPrimitive ?: return default
    if (p is JsonNull) return default
    return p.booleanOrNull ?: p.longOrNull?.let { it != 0L } ?: when (p.content.lowercase()) {
        "true", "yes" -> true
        "false", "no", "" -> false
        else -> default
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray = this[key].asArray()

internal fun JsonObject.strings(key: String): List<String> = arr(key).mapNotNull { it.asText() }
