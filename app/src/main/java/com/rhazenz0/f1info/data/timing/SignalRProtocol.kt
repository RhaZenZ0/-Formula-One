package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.util.AppJson
import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.int
import com.rhazenz0.f1info.util.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Minimal ASP.NET Core SignalR JSON hub protocol, as used by livetiming.formula1.com/signalrcore. */
object SignalRProtocol {
    const val RECORD_SEPARATOR = '\u001e'

    sealed interface Message {
        data class HandshakeResponse(val error: String?) : Message
        data class Invocation(val target: String, val arguments: List<JsonElement>) : Message
        data class Completion(val invocationId: String, val result: JsonElement?, val error: String?) : Message
        data object Ping : Message
        data class Close(val error: String?) : Message
        data class Other(val type: Int?) : Message
    }

    fun handshake(): String = """{"protocol":"json","version":1}$RECORD_SEPARATOR"""

    fun ping(): String = """{"type":6}$RECORD_SEPARATOR"""

    fun invocation(id: String, target: String, arguments: List<JsonElement>): String {
        val msg = buildJsonObject {
            put("type", 1)
            put("invocationId", id)
            put("target", target)
            put("arguments", JsonArray(arguments))
        }
        return msg.toString() + RECORD_SEPARATOR
    }

    fun subscribe(id: String, topics: List<String>): String =
        invocation(id, "Subscribe", listOf(JsonArray(topics.map { JsonPrimitive(it) })))

    /** Splits a transport payload into individual frames (without separators). */
    fun frames(payload: String): List<String> =
        payload.split(RECORD_SEPARATOR).filter { it.isNotBlank() }

    fun parse(frame: String): Message {
        val json = runCatching { AppJson.parseToJsonElement(frame) }.getOrNull() as? JsonObject
            ?: return Message.Other(null)
        val type = json.at("type").int()
        return when (type) {
            null -> Message.HandshakeResponse(json.at("error").text())
            1 -> Message.Invocation(
                target = json.at("target").text().orEmpty(),
                arguments = (json["arguments"] as? JsonArray)?.toList().orEmpty(),
            )
            3 -> Message.Completion(
                invocationId = json.at("invocationId").text().orEmpty(),
                result = json["result"]?.takeUnless { it is JsonNull },
                error = json.at("error").text(),
            )
            6 -> Message.Ping
            7 -> Message.Close(json.at("error").text())
            else -> Message.Other(type)
        }
    }
}
