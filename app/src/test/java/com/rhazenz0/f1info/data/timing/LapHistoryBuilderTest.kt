package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.util.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Patches copied from the 2026 Singapore GP TimingData stream for car 1. */
class LapHistoryBuilderTest {
    private fun feed(b: LapHistoryBuilder, t: String, json: String) =
        b.onLines(AppJson.parseToJsonElement("""{"1":$json}"""), Instant.parse(t))

    @Test
    fun buildsLapFromPatches() {
        val b = LapHistoryBuilder()
        feed(b, "2026-10-11T12:08:44Z", """{"NumberOfLaps":3,"Sectors":{"2":{"Value":"27.929"}},"Speeds":{"FL":{"Value":"253"}},"LastLapTime":{"Value":"1:38.977"}}""")
        feed(b, "2026-10-11T12:08:47Z", """{"Sectors":{"2":{"PreviousValue":"27.929"}},"Speeds":{"ST":{"Value":"290"}}}""")
        feed(b, "2026-10-11T12:09:13Z", """{"Sectors":{"0":{"Value":"29.118","Segments":{"0":{"Status":2049},"1":{"Status":2048}}},"1":{"Value":""},"2":{"Value":""}},"Speeds":{"I1":{"Value":"309"},"I2":{"Value":""},"FL":{"Value":""}}}""")
        feed(b, "2026-10-11T12:09:54Z", """{"Sectors":{"1":{"Value":"41.341","Segments":{"0":{"Status":2051}}}},"Speeds":{"I2":{"Value":"272"}}}""")
        feed(b, "2026-10-11T12:10:22Z", """{"NumberOfLaps":4,"Sectors":{"2":{"Value":"27.937"}},"Speeds":{"FL":{"Value":"252"}},"LastLapTime":{"Value":"1:38.396"}}""")

        val laps = b.laps().getValue("1")
        val lap4 = laps.single { it.lap == 4 }
        assertEquals(98.396, lap4.lapTime!!, 1e-9)
        assertEquals(listOf(29.118, 41.341, 27.937), lap4.sectors)
        assertEquals(309, lap4.i1Speed)
        assertEquals(272, lap4.i2Speed)
        assertEquals(252, lap4.finishSpeed)
        assertEquals(290, lap4.speedTrap)
        assertEquals(listOf(SegmentStatus.GREEN, SegmentStatus.YELLOW), lap4.segments[0])
        assertEquals(listOf(SegmentStatus.PURPLE), lap4.segments[1])
        assertEquals(Instant.parse("2026-10-11T12:08:44Z"), lap4.start)
        assertEquals(Instant.parse("2026-10-11T12:10:22Z"), lap4.end)
        assertTrue(!lap4.pitInLap && !lap4.pitOutLap)
    }

    @Test
    fun primeDoesNotCreateLaps() {
        val b = LapHistoryBuilder()
        b.prime(AppJson.parseToJsonElement("""{"1":{"NumberOfLaps":10}}"""), Instant.EPOCH)
        b.onLines(AppJson.parseToJsonElement("""{"1":{"NumberOfLaps":10}}"""), Instant.EPOCH)
        assertTrue(b.laps().isEmpty())
        b.onLines(AppJson.parseToJsonElement("""{"1":{"NumberOfLaps":11,"LastLapTime":{"Value":"1:40.000"}}}"""), Instant.EPOCH)
        assertEquals(11, b.laps().getValue("1").single().lap)
    }
}
