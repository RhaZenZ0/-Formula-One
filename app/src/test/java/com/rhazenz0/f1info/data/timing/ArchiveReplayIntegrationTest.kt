package com.rhazenz0.f1info.data.timing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Replays real archive files from the 2026 Singapore GP race. Runs only when the directory in the
 * F1_FEED_DIR environment variable holds the downloaded files (they are too large to commit).
 */
class ArchiveReplayIntegrationTest {
    private val dir = System.getenv("F1_FEED_DIR")?.let(::File)

    private val ref = SessionRef(
        year = 2026, meetingKey = 1296, meetingName = "Singapore Grand Prix", meetingOfficialName = "",
        location = "Marina Bay", country = "Singapore", circuit = "Singapore", sessionKey = 11388,
        sessionName = "Race", sessionType = "Race",
        start = Instant.parse("2026-10-11T12:00:00Z"), end = Instant.parse("2026-10-11T14:00:00Z"),
        gmtOffset = "08:00:00", path = "2026/2026-10-11_Singapore_Grand_Prix/2026-10-11_Race/",
    )

    private fun client() = ArchiveClient { url, _ ->
        File(dir, url.substringAfterLast('/')).takeIf { it.exists() }?.readText()
    }

    @Test
    fun replaysRace() = runBlocking {
        assumeTrue(dir != null && File(dir, "TimingData.jsonStream").exists())
        val started = System.currentTimeMillis()
        val loaded = client().loadSession(ref)
        val snap = loaded.snapshot
        println("Session replay took ${System.currentTimeMillis() - started} ms")

        assertEquals("Singapore Grand Prix", snap.meetingName)
        assertEquals(22, snap.drivers.size)
        assertEquals(1, snap.timing.first().position)
        assertEquals("3", snap.timing.first().number)

        val norris = snap.laps.getValue("1")
        assertEquals(62, norris.size)
        val lap5 = norris.single { it.lap == 5 }
        // Matches the values published for the same lap by other F1 data services.
        assertEquals(98.466, lap5.lapTime!!, 1e-6)
        assertEquals(listOf(28.988, 41.724, 27.754), lap5.sectors)
        assertEquals(307, lap5.i1Speed)
        assertEquals(272, lap5.i2Speed)
        assertEquals(289, lap5.speedTrap)
        assertEquals(24, lap5.segments.sumOf { it.size })
        assertTrue(norris.single { it.lap == 32 }.pitInLap)
        assertTrue(norris.single { it.lap == 33 }.pitOutLap)

        val stints = snap.stints.getValue("1")
        assertEquals(listOf("SOFT", "MEDIUM"), stints.map { it.compound })
        assertEquals(1 to 32, stints[0].lapStart to stints[0].lapEnd)
        assertEquals(33 to 62, stints[1].lapStart to stints[1].lapEnd)

        assertTrue(snap.pitStops.any { it.driver == "1" && it.lap == 32 })
        assertTrue(snap.weather.size > 100)
        assertTrue(snap.raceControl.isNotEmpty())
        assertTrue(snap.radio.all { it.url.startsWith("https://livetiming.formula1.com/static/2026/") })
        assertEquals(340.0, snap.championshipDrivers.first().predictedPoints!!, 1e-9)
        assertEquals(63, snap.lapPositions.getValue("1").size)
        println("Winner ${snap.tla(snap.timing.first().number)}, laps ${snap.currentLap}/${snap.totalLaps}")

        val tStart = System.currentTimeMillis()
        val telemetry = client().telemetry(ref)
        println("Telemetry decode took ${System.currentTimeMillis() - tStart} ms")
        val trace = TelemetryParser.lapTrace(telemetry, lap5)
        assertNotNull(trace)
        trace!!
        println("Lap 5: ${trace.speed.size} samples, max ${trace.maxSpeed} km/h, distance ${trace.distance.last()} m, map points ${trace.mapX.size}")
        assertTrue(trace.speed.size in 300..420)
        assertTrue(trace.maxSpeed in 280f..330f)
        assertTrue(trace.distance.last() in 4400f..5300f)
        assertTrue(trace.mapX.size > 100)
    }
}
