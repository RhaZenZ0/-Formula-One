package com.rhazenz0.f1info.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared lenient JSON configuration. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Child of an object by key, or null. */
fun JsonElement?.at(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** Follows a path of object keys. */
fun JsonElement?.at(vararg path: String): JsonElement? {
    var cur: JsonElement? = this
    for (p in path) cur = cur.at(p)
    return cur
}

/** Primitive content as text; null for missing, JSON null or non-primitives. */
fun JsonElement?.text(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

/** Non-blank text, otherwise null. */
fun JsonElement?.textOrNull(): String? = text()?.takeIf { it.isNotBlank() }

fun JsonElement?.int(): Int? {
    val t = text() ?: return null
    return t.toIntOrNull() ?: t.toDoubleOrNull()?.toInt()
}

fun JsonElement?.double(): Double? = text()?.toDoubleOrNull()

fun JsonElement?.bool(): Boolean? = when (text()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

/**
 * The feed sends lists either as JSON arrays or, in patches, as objects keyed by index.
 * Returns the elements in index order for both shapes.
 */
fun JsonElement?.elements(): List<JsonElement> = when (this) {
    is JsonArray -> this.toList()
    is JsonObject -> this.entries
        .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }
        .sortedBy { it.first }
        .map { it.second }
    else -> emptyList()
}

/** Indexed entries for both array and index-keyed object shapes. */
fun JsonElement?.indexedElements(): List<Pair<Int, JsonElement>> = when (this) {
    is JsonArray -> this.mapIndexed { i, e -> i to e }
    is JsonObject -> this.entries.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.sortedBy { it.first }
    else -> emptyList()
}

/** Object entries, or empty. */
fun JsonElement?.entries(): Set<Map.Entry<String, JsonElement>> = (this as? JsonObject)?.entries ?: emptySet()
