package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.util.TimeFormat
import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.bool
import com.rhazenz0.f1info.util.entries
import com.rhazenz0.f1info.util.indexedElements
import com.rhazenz0.f1info.util.int
import com.rhazenz0.f1info.util.textOrNull
import kotlinx.serialization.json.JsonElement
import java.time.Instant

/**
 * Rebuilds per-lap history (lap time, sectors, speed traps, mini-sectors, pit flags) from the
 * stream of TimingData patches. Values that arrive while a driver has completed N laps belong to
 * lap N+1; the patch that increments NumberOfLaps closes the lap with its sector 3 and lap time.
 */
class LapHistoryBuilder {

    private class Current {
        var start: Instant? = null
        val sectors = arrayOfNulls<Double>(3)
        val segments = Array(3) { sortedMapOf<Int, SegmentStatus>() }
        var i1: Int? = null
        var i2: Int? = null
        var fl: Int? = null
        var st: Int? = null
        var pitOut = false
        var pitIn = false

        fun reset(at: Instant?) {
            start = at
            sectors.fill(null)
            segments.forEach { it.clear() }
            i1 = null; i2 = null; fl = null; st = null
            pitOut = false; pitIn = false
        }
    }

    private val completed = HashMap<String, Int>()
    private val current = HashMap<String, Current>()
    private val laps = HashMap<String, MutableList<LapRecord>>()

    /** Starts all drivers on a fresh lap (used when the race or a session part starts). */
    fun resetAll(at: Instant?) {
        current.values.forEach { it.reset(at) }
    }

    /** Records lap counts from a full TimingData state without creating lap records. */
    fun prime(lines: JsonElement?, at: Instant?) {
        for ((driver, line) in lines.entries()) {
            line.at("NumberOfLaps").int()?.let { completed[driver] = it }
            current.getOrPut(driver) { Current() }.reset(at)
        }
    }

    /** Feeds the "Lines" object of a TimingData patch. */
    fun onLines(lines: JsonElement?, at: Instant?) {
        for ((driver, patch) in lines.entries()) onDriver(driver, patch, at)
    }

    private fun onDriver(driver: String, patch: JsonElement, at: Instant?) {
        val cur = current.getOrPut(driver) { Current().also { it.start = at } }

        applyValues(cur, patch)
        if (patch.at("InPit").bool() == true) cur.pitIn = true
        if (patch.at("PitOut").bool() == true) cur.pitOut = true

        val newCount = patch.at("NumberOfLaps").int() ?: return
        val previous = completed[driver] ?: 0
        if (newCount <= previous) {
            completed[driver] = newCount
            return
        }
        completed[driver] = newCount

        val lapTime = TimeFormat.parseLapTime(patch.at("LastLapTime", "Value").textOrNull())
        val record = LapRecord(
            driver = driver,
            lap = newCount,
            start = cur.start,
            end = at,
            lapTime = lapTime,
            sectors = cur.sectors.toList(),
            i1Speed = cur.i1,
            i2Speed = cur.i2,
            finishSpeed = cur.fl,
            speedTrap = cur.st,
            segments = cur.segments.map { seg ->
                if (seg.isEmpty()) emptyList() else (0..seg.lastKey()).map { seg[it] ?: SegmentStatus.NONE }
            },
            pitOutLap = cur.pitOut,
            pitInLap = cur.pitIn,
        )
        val list = laps.getOrPut(driver) { mutableListOf() }
        list.removeAll { it.lap == newCount }
        list.add(record)
        cur.reset(at)
    }

    private fun applyValues(cur: Current, patch: JsonElement) {
        for ((index, sector) in patch.at("Sectors").indexedElements()) {
            if (index !in 0..2) continue
            TimeFormat.parseLapTime(sector.at("Value").textOrNull())?.let { cur.sectors[index] = it }
            for ((segIndex, seg) in sector.at("Segments").indexedElements()) {
                val status = SegmentStatus.of(seg.at("Status").int())
                if (status != SegmentStatus.NONE) cur.segments[index][segIndex] = status
            }
        }
        val speeds = patch.at("Speeds")
        speeds.at("I1", "Value").textOrNull()?.toIntOrNull()?.let { cur.i1 = it }
        speeds.at("I2", "Value").textOrNull()?.toIntOrNull()?.let { cur.i2 = it }
        speeds.at("FL", "Value").textOrNull()?.toIntOrNull()?.let { cur.fl = it }
        speeds.at("ST", "Value").textOrNull()?.toIntOrNull()?.let { cur.st = it }
    }

    /** Server time at which [driver]'s current lap started, if known. */
    fun currentLapStart(driver: String): Instant? = current[driver]?.start

    fun laps(): Map<String, List<LapRecord>> = laps.mapValues { (_, v) -> v.sortedBy { it.lap } }
}
