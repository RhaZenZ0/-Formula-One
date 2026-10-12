package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.data.jolpica.JolpicaClient
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Talks to the real services. Runs only with NETWORK_TESTS=1. */
class LiveTimingNetworkTest {
    private val enabled = System.getenv("NETWORK_TESTS") == "1"

    @Test
    fun subscribesToLiveTiming() = runBlocking {
        assumeTrue(enabled)
        val events = LiveTimingClient().events()
        val initial = withTimeout(90_000) { events.filterIsInstance<LiveEvent.Initial>().first() }
        println("Live topics: ${initial.topics.keys.sorted()}")
        val acc = SessionAccumulator()
        initial.topics.forEach { (t, v) -> acc.applyInitial(t, v, initial.at) }
        val snap = acc.snapshot()
        println("Live session: ${snap.meetingName} ${snap.sessionName} status=${snap.sessionStatus} drivers=${snap.drivers.size} timing=${snap.timing.size}")
        assertTrue(initial.topics.containsKey("SessionInfo"))
        assertTrue(snap.drivers.isNotEmpty())
    }

    @Test
    fun readsJolpica() = runBlocking {
        assumeTrue(enabled)
        val j = JolpicaClient()
        val schedule = j.schedule("2026")
        val standings = j.driverStandings("2026")
        println("2026 rounds: ${schedule.size}, leader: ${standings?.drivers?.firstOrNull()?.driver?.fullName}")
        assertTrue(schedule.size > 15)
        assertTrue(schedule.first().sessions.size >= 3)
    }

    @Test
    fun readsArchiveIndex() = runBlocking {
        assumeTrue(enabled)
        val meetings = ArchiveClient().meetings(2026)
        println("Archive 2026 meetings: ${meetings.size}, last started: ${meetings.lastOrNull { m -> m.sessions.any { it.start?.isBefore(java.time.Instant.now()) == true } }?.name}")
        assertTrue(meetings.isNotEmpty())
    }
}
