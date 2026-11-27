package talq.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// The server is PHP: an empty map arrives as `[]`, numbers sometimes as strings, booleans as
// 0/1. Models are therefore read by hand through these tolerant accessors instead of through
// generated serializers, which would throw on the first such surprise.

internal val TalkJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal val EmptyJsonObject = JsonObject(emptyMap())

/**
 * Whether [text] nests arrays and objects deeper than [limit]: one pass over it, nothing is built. Asked before
 * JSON that another person's client wrote is parsed. The reader refuses by itself at 400 levels, with an exception
 * (`JsonConfiguration.maxNestingDepth`); this is a lower limit. Text that is no JSON may be counted wrong, but only
 * past the place where the reader gives up on it.
 */
internal fun jsonDeeperThan(text: String, limit: Int): Boolean {
    var depth = 0
    var inString = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            // The character after it says nothing, a quote included.
            if (c == '\\') i++ else if (c == '"') inString = false
        } else if (c == '"') {
            inString = true
        } else if (c == '[' || c == '{') {
            depth++
            if (depth > limit) return true
        } else if ((c == ']' || c == '}') && depth > 0) {
            depth--
        }
        i++
    }
    return false
}

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

internal fun JsonElement?.asText(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

internal fun JsonObject.str(key: String, default: String = ""): String = this[key].asText() ?: default

internal fun JsonObject.long(key: String, default: Long = 0L): Long {
    val p = this[key] as? JsonPrimitive ?: return default
    if (p is JsonNull) return default
    return p.content.whole() ?: p.content.toDoubleOrNull()?.toLong() ?: p.booleanOrNull?.let { if (it) 1L else 0L } ?: default
}

/** Not the library's `longOrNull`, which throws on a text that begins with a quote (`"5`). */
private fun String.whole(): Long? = trim().toLongOrNull()

internal fun JsonObject.int(key: String, default: Int = 0): Int = long(key, default.toLong()).toInt()

internal fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
    val p = this[key] as? JsonPrimitive ?: return default
    if (p is JsonNull) return default
    return p.booleanOrNull ?: p.content.whole()?.let { it != 0L } ?: when (p.content.lowercase()) {
        "true", "yes" -> true
        "false", "no", "" -> false
        else -> default
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray = this[key].asArray()

internal fun JsonObject.strings(key: String): List<String> = arr(key).mapNotNull { it.asText() }
