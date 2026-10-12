package com.rhazenz0.f1info.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TimeFormatTest {
    @Test
    fun parsesLapTimes() {
        assertEquals(98.977, TimeFormat.parseLapTime("1:38.977")!!, 1e-9)
        assertEquals(28.988, TimeFormat.parseLapTime("28.988")!!, 1e-9)
        assertEquals(1.234, TimeFormat.parseLapTime("+1.234")!!, 1e-9)
        assertNull(TimeFormat.parseLapTime(""))
        assertNull(TimeFormat.parseLapTime("1 L"))
    }

    @Test
    fun formatsLapTimes() {
        assertEquals("1:38.466", TimeFormat.formatLapTime(98.466))
        assertEquals("28.900", TimeFormat.formatLapTime(28.9))
        assertEquals("—", TimeFormat.formatLapTime(null))
    }

    @Test
    fun parsesFeedTimestamps() {
        assertEquals(Instant.parse("2026-10-11T11:07:37.8131343Z"), TimeFormat.parseUtc("2026-10-11T11:07:37.8131343Z"))
        assertEquals(Instant.parse("2026-10-11T11:11:21Z"), TimeFormat.parseUtc("2026-10-11T11:11:21"))
        assertEquals(Instant.parse("2026-10-11T12:00:00Z"), TimeFormat.trackLocalToInstant("2026-10-11T20:00:00", "08:00:00"))
        assertEquals(Instant.parse("2026-06-01T20:00:00Z"), TimeFormat.trackLocalToInstant("2026-06-01T16:00:00", "-04:00:00"))
    }
}
