package com.rhazenz0.f1info.data.timing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class TrackEstimatorTest {
    private val square = TrackOutline(floatArrayOf(0f, 100f, 100f, 0f), floatArrayOf(0f, 0f, 100f, 100f))

    @Test
    fun outlineInterpolatesAndProjects() {
        assertEquals(400f, square.length, 1e-3f)
        assertEquals(50f to 0f, square.pointAt(0.125f))
        assertEquals(0f to 50f, square.pointAt(0.875f))
        assertEquals(0f to 0f, square.pointAt(1f))
        assertEquals(0.6f, square.project(60f, 105f), 1e-3f)
        assertEquals(4f, square.gapMetres(0.95f, 0.05f), 1e-3f)
    }

    private fun progress(passed: Int, start: Instant?) =
        LapProgress("1", start, passed, 24, 100.0, inPit = false, out = false)

    @Test
    fun estimateUsesTimeWithinMiniSectorBounds() {
        val start = Instant.parse("2026-10-11T12:00:00Z")
        val lag = TrackEstimator.LAP_START_LAG_SECONDS
        // 27 s (+ feed lag) into a 100 s lap with 7 of 24 mini-sectors done: time decides.
        assertEquals(((27 + lag) / 100).toFloat(), TrackEstimator.lapFraction(progress(7, start), start.plusSeconds(27)), 1e-4f)
        // Time says 50% but only 3 mini-sectors are done: held at most one mini-sector beyond the 4th.
        assertEquals(5f / 24, TrackEstimator.lapFraction(progress(3, start), start.plusSeconds(50)), 1e-4f)
        // Time says 5% but 10 mini-sectors are done: at least one mini-sector short of 10/24.
        assertEquals(9f / 24, TrackEstimator.lapFraction(progress(10, start), start.plusSeconds(2)), 1e-4f)
        // All mini-sectors still lit just after the line means the new lap has begun.
        assertEquals(((1 + lag) / 100).toFloat(), TrackEstimator.lapFraction(progress(24, start), start.plusSeconds(1)), 1e-4f)
    }

    @Test
    fun parsesCircuitOutline() {
        val json = """{"x":[0,100,100,0],"y":[0,0,100,100],"rotation":90,
            "corners":[{"number":1,"trackPosition":{"x":100,"y":50}}]}"""
        val outline = CircuitClient.parse(json)!!
        assertEquals(90f, outline.rotationDeg)
        assertEquals(listOf(Corner(1, 100f, 50f)), outline.corners)
    }

    /**
     * Replays the 2026 Singapore GP timing up to several moments and compares the estimated
     * positions with the real car coordinates. Runs only with F1_FEED_DIR set.
     */
    @Test
    fun estimateIsCloseToRealPositions() = kotlinx.coroutines.runBlocking {
        val dir = System.getenv("F1_FEED_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "Position.z.jsonStream").exists() && File(dir, "circuit_61.json").exists())
        val outline = CircuitClient.parse(File(dir, "circuit_61.json").readText())!!
        val archive = ArchiveClient { url, _ -> File(dir, url.substringAfterLast('/')).takeIf { it.exists() }?.readText() }
        val ref = SessionRef(
            2026, 1296, "Singapore Grand Prix", "", "Marina Bay", "Singapore", "Singapore", 11388, "Race", "Race",
            Instant.parse("2026-10-11T12:00:00Z"), Instant.parse("2026-10-11T14:00:00Z"), "08:00:00",
            "2026/2026-10-11_Singapore_Grand_Prix/2026-10-11_Race/",
        )
        val streams = listOf("Heartbeat", "SessionStatus", "TrackStatus", "TimingData").associateWith {
            File(dir, "$it.jsonStream").takeIf { f -> f.exists() }?.readText()
        }
        val telemetry = archive.telemetry(ref)

        val errors = ArrayList<Float>()
        var moment = Instant.parse("2026-10-11T12:10:00Z")
        while (moment.isBefore(Instant.parse("2026-10-11T13:40:00Z"))) {
            val acc = SessionAccumulator()
            archive.replay(acc, ref, streams, until = moment)
            val snap = acc.snapshot()
            for ((driver, p) in snap.lapProgress) {
                if (p.inPit || p.out) continue
                val track = telemetry.position[driver] ?: continue
                val i = TelemetryParser.lowerBound(track.times, moment.toEpochMilli())
                if (i !in track.times.indices || kotlin.math.abs(track.times[i] - moment.toEpochMilli()) > 1500) continue
                val truth = outline.project(track.x[i].toFloat(), track.y[i].toFloat())
                val estimate = TrackEstimator.lapFraction(p, moment)
                errors.add(outline.gapMetres(truth, estimate))
            }
            moment = moment.plusSeconds(137)
        }
        errors.sort()
        val median = errors[errors.size / 2]
        val p90 = errors[(errors.size * 9) / 10]
        println("Track estimate error over ${errors.size} samples: median ${median.toInt()} m, 90th percentile ${p90.toInt()} m, lap ${(outline.length / 10).toInt()} m")
        assertTrue(errors.size > 500)
        assertTrue("median error $median m", median < 90f)
        assertTrue("90th percentile error $p90 m", p90 < 250f)
    }
}
