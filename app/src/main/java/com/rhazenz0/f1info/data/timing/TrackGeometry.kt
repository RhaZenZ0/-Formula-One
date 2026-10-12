package com.rhazenz0.f1info.data.timing

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

data class Corner(val number: Int, val x: Float, val y: Float)

/**
 * A closed circuit outline in F1 position coordinates (decimetres), starting at the
 * start/finish line and running in the direction of travel.
 */
class TrackOutline(
    val xs: FloatArray,
    val ys: FloatArray,
    /** Degrees to rotate the map so it matches the official circuit orientation. */
    val rotationDeg: Float = 0f,
    val corners: List<Corner> = emptyList(),
) {
    /** Cumulative distance at each point; the last entry closes the loop back to point 0. */
    private val cumulative: FloatArray
    val length: Float

    init {
        require(xs.size == ys.size && xs.size >= 3) { "An outline needs at least three points." }
        cumulative = FloatArray(xs.size + 1)
        for (i in 1..xs.size) {
            val a = i - 1
            val b = i % xs.size
            cumulative[i] = cumulative[i - 1] + dist(xs[a], ys[a], xs[b], ys[b])
        }
        length = cumulative[xs.size]
    }

    /** Point at [fraction] of a lap (wraps around). */
    fun pointAt(fraction: Float): Pair<Float, Float> {
        var f = fraction - floor(fraction)
        if (f.isNaN()) f = 0f
        val target = f * length
        var lo = 0
        var hi = xs.size
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= target) lo = mid else hi = mid - 1
        }
        val a = lo.coerceAtMost(xs.size - 1)
        val b = (a + 1) % xs.size
        val seg = cumulative[a + 1] - cumulative[a]
        val t = if (seg > 0f) (target - cumulative[a]) / seg else 0f
        return (xs[a] + (xs[b] - xs[a]) * t) to (ys[a] + (ys[b] - ys[a]) * t)
    }

    /** Lap fraction of the outline point nearest to (x, y). */
    fun project(x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        var bestDist = 0f
        for (a in xs.indices) {
            val b = (a + 1) % xs.size
            val dx = xs[b] - xs[a]
            val dy = ys[b] - ys[a]
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0f) (((x - xs[a]) * dx + (y - ys[a]) * dy) / len2).coerceIn(0f, 1f) else 0f
            val px = xs[a] + dx * t
            val py = ys[a] + dy * t
            val d = (x - px) * (x - px) + (y - py) * (y - py)
            if (d < best) {
                best = d
                bestDist = cumulative[a] + (cumulative[a + 1] - cumulative[a]) * t
            }
        }
        return if (length > 0f) bestDist / length else 0f
    }

    /** Distance in metres between two lap fractions, taking the shorter way round. */
    fun gapMetres(a: Float, b: Float): Float {
        var d = kotlin.math.abs(a - b) % 1f
        if (d > 0.5f) d = 1f - d
        return d * length / 10f
    }

    /** Rotates a point for display by [rotationDeg]. */
    fun rotate(x: Float, y: Float): Pair<Float, Float> {
        if (rotationDeg == 0f) return x to y
        val r = Math.toRadians(rotationDeg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return (x * c - y * s) to (x * s + y * c)
    }

    companion object {
        private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x2 - x1
            val dy = y2 - y1
            return sqrt(dx * dx + dy * dy)
        }

        /** Builds an outline from one lap of car positions, dropping near-duplicate points. */
        fun fromLap(xs: FloatArray, ys: FloatArray, rotationDeg: Float = 0f, corners: List<Corner> = emptyList()): TrackOutline? {
            val px = ArrayList<Float>(xs.size)
            val py = ArrayList<Float>(ys.size)
            for (i in xs.indices) {
                if (px.isNotEmpty() && dist(px.last(), py.last(), xs[i], ys[i]) < 5f) continue
                px.add(xs[i]); py.add(ys[i])
            }
            if (px.size < 3) return null
            return TrackOutline(px.toFloatArray(), py.toFloatArray(), rotationDeg, corners)
        }
    }
}

/** Where a driver is in their current lap, as far as the timing feed shows. */
data class LapProgress(
    val driver: String,
    /** Server time at which the current lap started. */
    val lapStart: java.time.Instant?,
    /** Mini-sectors completed in the current lap. */
    val segmentsPassed: Int,
    val segmentsTotal: Int,
    /** Expected lap time in seconds. */
    val referenceLap: Double?,
    val inPit: Boolean,
    val out: Boolean,
)

/**
 * Estimates track position from public timing data, for when real car coordinates are not
 * available. Time since the lap started gives the estimate; completed mini-sectors keep it
 * within one mini-sector of where the timing loops last saw the car.
 *
 * Tuned against real car positions from the 2026 Singapore GP: median error about 60 m.
 */
object TrackEstimator {
    private const val DEFAULT_LAP_SECONDS = 95.0

    /** The feed reports a lap a few seconds after the car crosses the line. */
    internal const val LAP_START_LAG_SECONDS = 3.0

    /** Mini-sectors are not equal in length, so the bounds allow one either side. */
    private const val SLACK_SEGMENTS = 1

    fun lapFraction(p: LapProgress, serverNow: java.time.Instant): Float {
        val total = p.segmentsTotal
        // The feed keeps every mini-sector lit for a few seconds after the line; that is a new lap.
        val passed = if (total > 0 && p.segmentsPassed >= total) 0 else p.segmentsPassed
        val lap = (p.referenceLap ?: DEFAULT_LAP_SECONDS).coerceAtLeast(30.0)
        val byTime = p.lapStart?.let {
            val elapsed = java.time.Duration.between(it, serverNow).toMillis() / 1000.0 + LAP_START_LAG_SECONDS
            (elapsed / lap).toFloat()
        }
        if (total == 0) return byTime ?: 0f
        val low = ((passed - SLACK_SEGMENTS).toFloat() / total).coerceAtLeast(0f)
        val high = ((passed + 1 + SLACK_SEGMENTS).toFloat() / total).coerceAtMost(0.999f)
        return (byTime ?: (passed.toFloat() / total)).coerceIn(low, high)
    }
}
