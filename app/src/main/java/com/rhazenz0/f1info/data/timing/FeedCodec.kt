package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.util.AppJson
import com.rhazenz0.f1info.util.TimeFormat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/** One line of a ".jsonStream" archive: a time offset from the stream start plus a JSON patch. */
data class StreamLine(val offsetMs: Long, val payload: String)

/** Byte-order mark that prefixes many timing archive files. */
internal val BOM: String = Char(0xFEFF).toString()

object FeedCodec {
    private const val OFFSET_LENGTH = 12 // "HH:MM:SS.mmm"

    /** Splits a ".jsonStream" file into lines, skipping blanks and malformed rows. */
    fun parseStream(text: String): List<StreamLine> {
        val out = ArrayList<StreamLine>()
        text.removePrefix(BOM).lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r').removePrefix(BOM)
            if (line.length <= OFFSET_LENGTH) return@forEach
            val offset = TimeFormat.parseStreamOffset(line.substring(0, OFFSET_LENGTH)) ?: return@forEach
            out.add(StreamLine(offset, line.substring(OFFSET_LENGTH)))
        }
        return out
    }

    fun parseJson(text: String): JsonElement = AppJson.parseToJsonElement(text.removePrefix(BOM))

    /**
     * Topics ending in ".z" carry base64-encoded raw-deflate JSON. Accepts either the JSON string
     * literal (with quotes) or the bare base64 text.
     */
    fun inflate(payload: String): String {
        val b64 = payload.trim().removeSurrounding("\"")
        val bytes = Base64.getDecoder().decode(b64)
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 6)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buffer, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }

    /** Decodes a ".z" topic element (a JSON string) into its JSON content. */
    fun inflateElement(element: JsonElement): JsonElement? {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return runCatching { parseJson(inflate(text)) }.getOrNull()
    }
}
