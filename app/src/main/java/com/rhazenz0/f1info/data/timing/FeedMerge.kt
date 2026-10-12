package com.rhazenz0.f1info.data.timing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Applies F1 live timing patches.
 *
 * The feed sends partial updates: objects merge recursively, an object keyed by
 * numeric strings patches the matching list items (appending past the end),
 * "_deleted" lists keys or indices to remove, and anything else replaces the value.
 */
object FeedMerge {
    private const val DELETED = "_deleted"

    fun merge(base: JsonElement?, patch: JsonElement): JsonElement {
        if (patch !is JsonObject) return patch
        return when (base) {
            is JsonObject -> mergeObject(base, patch)
            is JsonArray -> mergeArray(base, patch)
            else -> clean(patch)
        }
    }

    private fun mergeObject(base: JsonObject, patch: JsonObject): JsonObject {
        val result = LinkedHashMap<String, JsonElement>(base)
        for ((key, value) in patch) {
            if (key == DELETED) {
                deletedKeys(value).forEach { result.remove(it) }
                continue
            }
            val existing = result[key]
            result[key] = if (existing == null || existing is JsonNull) clean(value) else merge(existing, value)
        }
        return JsonObject(result)
    }

    private fun mergeArray(base: JsonArray, patch: JsonObject): JsonArray {
        val list = base.toMutableList()
        for ((key, value) in patch) {
            if (key == DELETED) continue
            val index = key.toIntOrNull() ?: continue
            if (index < list.size) {
                list[index] = merge(list[index], value)
            } else {
                while (list.size < index) list.add(JsonNull)
                list.add(clean(value))
            }
        }
        val deleted = patch[DELETED]?.let { deletedKeys(it) }?.mapNotNull { it.toIntOrNull() }
        if (!deleted.isNullOrEmpty()) {
            deleted.sortedDescending().forEach { if (it in list.indices) list.removeAt(it) }
        }
        return JsonArray(list)
    }

    /** Removes "_deleted" markers from a value inserted fresh. */
    private fun clean(value: JsonElement): JsonElement {
        if (value !is JsonObject) return value
        if (value.isEmpty()) return value
        val map = LinkedHashMap<String, JsonElement>(value.size)
        for ((k, v) in value) if (k != DELETED) map[k] = clean(v)
        return JsonObject(map)
    }

    private fun deletedKeys(value: JsonElement): List<String> = when (value) {
        is JsonArray -> value.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        is JsonObject -> value.values.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        else -> emptyList()
    }
}
