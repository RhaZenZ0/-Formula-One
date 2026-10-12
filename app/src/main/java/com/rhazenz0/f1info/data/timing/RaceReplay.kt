package com.rhazenz0.f1info.data.timing

import kotlin.math.abs

/** Where every car was at one moment of a finished session, from real car coordinates. */
class RaceReplay(
    private val telemetry: TelemetryData,
    private val laps: Map<String, List<LapRecord>>,
    val outline: TrackOutline,
) {
    data class Car(
        val driver: String,
        val x: Float,
        val y: Float,
        /** Laps completed plus the fraction of the current lap. */
        val distance: Double,
        val position: Int,
        val gapToLeader: Double?,
        val lapsBehind: Int,
    )

    private val lapEnds: Map<String, LongArray> = laps.mapValues { (_, list) ->
        list.mapNotNull { it.end?.toEpochMilli() }.sorted().toLongArray()
    }

    val startMs: Long = laps.values.flatten().filter { it.lap == 1 }.mapNotNull { it.start?.toEpochMilli() }.minOrNull()
        ?: telemetry.position.values.mapNotNull { it.times.firstOrNull() }.minOrNull() ?: 0L

    val endMs: Long = run {
        val lastLap = lapEnds.values.mapNotNull { it.lastOrNull() }.maxOrNull()
        val lastSample = telemetry.position.values.mapNotNull { it.times.lastOrNull() }.maxOrNull()
        listOfNotNull(lastLap?.plus(60_000), lastSample).minOrNull() ?: startMs
    }

    /** A typical lap time of the session, used to turn distance gaps into seconds. */
    private val typicalLap: Double = laps.values.flatten()
        .mapNotNull { it.lapTime }.sorted().let { if (it.isEmpty()) 95.0 else it[it.size / 2] }

    fun lapOfLeader(cars: List<Car>): Int = (cars.firstOrNull()?.distance?.toInt() ?: 0) + 1

    fun at(tMs: Long): List<Car> {
        val raw = telemetry.position.mapNotNull { (driver, track) ->
            val (x, y) = interpolate(track, tMs) ?: return@mapNotNull null
            val ends = lapEnds[driver] ?: LongArray(0)
            val done = TelemetryParser.lowerBound(ends, tMs + 1)
            var frac = outline.project(x, y).toDouble()
            // Near the line the projection and the lap count can disagree for a moment.
            val lastEnd = if (done > 0) ends[done - 1] else null
            val nextEnd = if (done < ends.size) ends[done] else null
            if (frac > 0.8 && lastEnd != null && tMs - lastEnd < 20_000) frac -= 1.0
            else if (frac < 0.2 && nextEnd != null && nextEnd - tMs < 20_000) frac += 1.0
            Triple(driver, x to y, done + frac)
        }.sortedByDescending { it.third }

        val leader = raw.firstOrNull()?.third ?: 0.0
        return raw.mapIndexed { i, (driver, xy, distance) ->
            val behind = leader - distance
            Car(
                driver = driver,
                x = xy.first,
                y = xy.second,
                distance = distance,
                position = i + 1,
                gapToLeader = if (i == 0) null else behind * typicalLap,
                lapsBehind = behind.toInt(),
            )
        }
    }

    /** Car position at [tMs], or null when the car is not reporting (garage, retired). */
    private fun interpolate(track: PositionTrack, tMs: Long): Pair<Float, Float>? {
        if (track.size == 0) return null
        val i = TelemetryParser.lowerBound(track.times, tMs)
        if (i <= 0) return if (abs(track.times[0] - tMs) < 5_000) track.x[0].toFloat() to track.y[0].toFloat() else null
        if (i >= track.size) {
            val last = track.size - 1
            return if (tMs - track.times[last] < 5_000) track.x[last].toFloat() to track.y[last].toFloat() else null
        }
        val t0 = track.times[i - 1]; val t1 = track.times[i]
        if (t1 - t0 > 10_000) return null
        val f = ((tMs - t0).toFloat() / (t1 - t0).coerceAtLeast(1)).coerceIn(0f, 1f)
        return (track.x[i - 1] + (track.x[i] - track.x[i - 1]) * f) to (track.y[i - 1] + (track.y[i] - track.y[i - 1]) * f)
    }
}
