package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.util.AppJson
import com.rhazenz0.f1info.util.TimeFormat
import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.elements
import com.rhazenz0.f1info.util.entries
import com.rhazenz0.f1info.util.int
import com.rhazenz0.f1info.util.text
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Car channels sampled at roughly 3.7 Hz. Times are epoch milliseconds. */
class CarChannels(
    val times: LongArray,
    val rpm: IntArray,
    val speed: IntArray,
    val gear: IntArray,
    val throttle: IntArray,
    val brake: IntArray,
) {
    val size: Int get() = times.size
}

class PositionTrack(val times: LongArray, val x: IntArray, val y: IntArray) {
    val size: Int get() = times.size
}

class TelemetryData(
    val car: Map<String, CarChannels>,
    val position: Map<String, PositionTrack>,
)

/** Telemetry for one lap, aligned by distance from the start of the lap. */
data class LapTrace(
    val driver: String,
    val lap: Int,
    val distance: FloatArray,
    val time: FloatArray,
    val speed: FloatArray,
    val throttle: FloatArray,
    val brake: FloatArray,
    val rpm: FloatArray,
    val gear: FloatArray,
    val mapX: FloatArray,
    val mapY: FloatArray,
    val mapSpeed: FloatArray,
) {
    val maxSpeed: Float get() = speed.maxOrNull() ?: 0f
    val fullThrottlePercent: Float
        get() = if (throttle.isEmpty()) 0f else throttle.count { it >= 98f } * 100f / throttle.size
    val brakingPercent: Float
        get() = if (brake.isEmpty()) 0f else brake.count { it > 0f } * 100f / brake.size
}

private class LongBuf { var a = LongArray(1024); var n = 0
    fun add(v: Long) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    fun done() = a.copyOf(n)
}

private class IntBuf { var a = IntArray(1024); var n = 0
    fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
    fun done() = a.copyOf(n)
}

private class CarBuf { val t = LongBuf(); val rpm = IntBuf(); val speed = IntBuf(); val gear = IntBuf(); val thr = IntBuf(); val brk = IntBuf()
    fun done() = CarChannels(t.done(), rpm.done(), speed.done(), gear.done(), thr.done(), brk.done())
}

private class PosBuf { val t = LongBuf(); val x = IntBuf(); val y = IntBuf()
    fun done() = PositionTrack(t.done(), x.done(), y.done())
}

object TelemetryParser {

    /** Channel numbers used by the CarData feed. */
    private const val CH_RPM = "0"
    private const val CH_SPEED = "2"
    private const val CH_GEAR = "3"
    private const val CH_THROTTLE = "4"
    private const val CH_BRAKE = "5"

    /** Collects telemetry from CarData.z and Position.z stream lines. */
    class Collector {
        private val cars = HashMap<String, CarBuf>()
        private val positions = HashMap<String, PosBuf>()

        fun addCarData(decoded: JsonElement) {
            for (entry in decoded.at("Entries").elements()) {
                val utc = TimeFormat.parseUtc(entry.at("Utc").text())?.toEpochMilli() ?: continue
                for ((driver, car) in entry.at("Cars").entries()) {
                    val ch = car.at("Channels") as? JsonObject ?: continue
                    val buf = cars.getOrPut(driver) { CarBuf() }
                    val last = buf.t.n
                    if (last > 0 && buf.t.a[last - 1] >= utc) continue
                    buf.t.add(utc)
                    buf.rpm.add(ch[CH_RPM].int() ?: 0)
                    buf.speed.add(ch[CH_SPEED].int() ?: 0)
                    buf.gear.add(ch[CH_GEAR].int() ?: 0)
                    buf.thr.add((ch[CH_THROTTLE].int() ?: 0).coerceIn(0, 100))
                    val brake = ch[CH_BRAKE].int() ?: 0
                    buf.brk.add(if (brake > 0) 100 else 0)
                }
            }
        }

        fun addPosition(decoded: JsonElement) {
            for (sample in decoded.at("Position").elements()) {
                val utc = TimeFormat.parseUtc(sample.at("Timestamp").text())?.toEpochMilli() ?: continue
                for ((driver, p) in sample.at("Entries").entries()) {
                    val x = p.at("X").int() ?: continue
                    val y = p.at("Y").int() ?: continue
                    if (x == 0 && y == 0) continue
                    val buf = positions.getOrPut(driver) { PosBuf() }
                    val last = buf.t.n
                    if (last > 0 && buf.t.a[last - 1] >= utc) continue
                    buf.t.add(utc); buf.x.add(x); buf.y.add(y)
                }
            }
        }

        fun build() = TelemetryData(cars.mapValues { it.value.done() }, positions.mapValues { it.value.done() })
    }

    /** Parses the raw text of CarData.z.jsonStream and Position.z.jsonStream files. */
    fun parseArchive(carStream: String?, positionStream: String?): TelemetryData {
        val c = Collector()
        carStream?.let { text ->
            for (line in FeedCodec.parseStream(text)) {
                runCatching { c.addCarData(AppJson.parseToJsonElement(FeedCodec.inflate(line.payload))) }
            }
        }
        positionStream?.let { text ->
            for (line in FeedCodec.parseStream(text)) {
                runCatching { c.addPosition(AppJson.parseToJsonElement(FeedCodec.inflate(line.payload))) }
            }
        }
        return c.build()
    }

    /** Lap time window: ends at the lap's end and spans its lap time when known. */
    fun lapWindow(lap: LapRecord): Pair<Instant, Instant>? {
        val end = lap.end ?: return null
        val start = lap.lapTime?.let { end.minusMillis((it * 1000).toLong()) } ?: lap.start ?: return null
        return start to end
    }

    fun lapTrace(data: TelemetryData, lap: LapRecord): LapTrace? {
        val (startI, endI) = lapWindow(lap) ?: return null
        val start = startI.toEpochMilli()
        val end = endI.toEpochMilli()
        val car = data.car[lap.driver] ?: return null
        val from = lowerBound(car.times, start)
        val to = lowerBound(car.times, end + 1)
        val n = to - from
        if (n < 5) return null

        val distance = FloatArray(n)
        val time = FloatArray(n)
        var d = 0.0
        for (i in 0 until n) {
            val idx = from + i
            if (i > 0) {
                val dt = (car.times[idx] - car.times[idx - 1]) / 1000.0
                val v = (car.speed[idx] + car.speed[idx - 1]) / 2.0 / 3.6
                d += v * dt
            }
            distance[i] = d.toFloat()
            time[i] = ((car.times[idx] - start) / 1000.0).toFloat()
        }

        val pos = data.position[lap.driver]
        var mx = FloatArray(0); var my = FloatArray(0); var ms = FloatArray(0)
        if (pos != null) {
            val pf = lowerBound(pos.times, start)
            val pt = lowerBound(pos.times, end + 1)
            val m = (pt - pf).coerceAtLeast(0)
            mx = FloatArray(m); my = FloatArray(m); ms = FloatArray(m)
            for (i in 0 until m) {
                val idx = pf + i
                mx[i] = pos.x[idx].toFloat()
                my[i] = pos.y[idx].toFloat()
                val ci = nearest(car.times, pos.times[idx])
                ms[i] = if (ci >= 0) car.speed[ci].toFloat() else 0f
            }
        }

        fun slice(src: IntArray) = FloatArray(n) { src[from + it].toFloat() }
        return LapTrace(
            driver = lap.driver,
            lap = lap.lap,
            distance = distance,
            time = time,
            speed = slice(car.speed),
            throttle = slice(car.throttle),
            brake = slice(car.brake),
            rpm = slice(car.rpm),
            gear = slice(car.gear),
            mapX = mx,
            mapY = my,
            mapSpeed = ms,
        )
    }

    internal fun lowerBound(a: LongArray, key: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun nearest(a: LongArray, key: Long): Int {
        if (a.isEmpty()) return -1
        val i = lowerBound(a, key)
        if (i <= 0) return 0
        if (i >= a.size) return a.size - 1
        return if (key - a[i - 1] <= a[i] - key) i - 1 else i
    }
}
