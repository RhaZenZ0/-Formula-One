package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.data.net.Http
import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.double
import com.rhazenz0.f1info.util.elements
import com.rhazenz0.f1info.util.int
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Circuit outlines from the MultiViewer circuit API. The outlines use the same coordinate
 * system as F1's car position feed, so live and archived car positions line up with them.
 */
class CircuitClient(
    private val fetch: suspend (String) -> String? = { url -> Http.getString(url, missingIsNull = true, immutable = true) },
) {
    private val lock = Mutex()
    private val cache = HashMap<Int, TrackOutline?>()

    suspend fun outline(circuitKey: Int, year: Int): TrackOutline? = lock.withLock {
        if (cache.containsKey(circuitKey)) return cache[circuitKey]
        var result: TrackOutline? = null
        for (y in listOf(year, year - 1, year - 2)) {
            val text = runCatching { fetch("https://api.multiviewer.app/api/v1/circuits/$circuitKey/$y") }.getOrNull() ?: continue
            result = runCatching { parse(text) }.getOrNull()
            if (result != null) break
        }
        cache[circuitKey] = result
        result
    }

    companion object {
        fun parse(text: String): TrackOutline? {
            val json = FeedCodec.parseJson(text)
            val xs = json.at("x").elements().mapNotNull { it.double()?.toFloat() }
            val ys = json.at("y").elements().mapNotNull { it.double()?.toFloat() }
            if (xs.size != ys.size || xs.size < 3) return null
            val corners = json.at("corners").elements().mapNotNull { c ->
                val n = c.at("number").int() ?: return@mapNotNull null
                val x = c.at("trackPosition", "x").double()?.toFloat() ?: return@mapNotNull null
                val y = c.at("trackPosition", "y").double()?.toFloat() ?: return@mapNotNull null
                Corner(n, x, y)
            }
            return TrackOutline.fromLap(
                xs.toFloatArray(), ys.toFloatArray(),
                rotationDeg = json.at("rotation").double()?.toFloat() ?: 0f,
                corners = corners,
            )
        }
    }
}
