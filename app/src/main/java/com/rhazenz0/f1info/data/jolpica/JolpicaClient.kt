package com.rhazenz0.f1info.data.jolpica

import com.rhazenz0.f1info.data.net.Http
import com.rhazenz0.f1info.data.net.RequestLimiter
import com.rhazenz0.f1info.util.AppJson
import com.rhazenz0.f1info.util.TimeFormat
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A scheduled session with its start time. */
data class ScheduledSession(val name: String, val start: Instant?)

/** A race weekend on the calendar. */
data class RaceWeekend(
    val season: Int,
    val round: Int,
    val name: String,
    val circuit: String,
    val locality: String,
    val country: String,
    val raceStart: Instant?,
    val sessions: List<ScheduledSession>,
    val wikipediaUrl: String?,
) {
    val isSprintWeekend: Boolean get() = sessions.any { it.name.startsWith("Sprint") }
    val firstSessionStart: Instant? get() = sessions.mapNotNull { it.start }.minOrNull() ?: raceStart

    fun isFinished(now: Instant = Instant.now()): Boolean = raceStart?.plusSeconds(3 * 3600)?.isBefore(now) ?: false
}

class JolpicaClient(
    private val fetch: suspend (String) -> String? = { url -> Http.getString(url, missingIsNull = true, limiter = limiter) },
) {
    companion object {
        const val BASE = "https://api.jolpi.ca/ergast/f1"
        // Jolpica allows about 4 requests per second; stay comfortably below that.
        private val limiter = RequestLimiter(minIntervalMs = 350)
        private const val SHORT_TTL_MS = 5 * 60 * 1000L
        private const val LONG_TTL_MS = 12 * 60 * 60 * 1000L
    }

    private data class Cached(val body: String?, val expires: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    private suspend fun get(path: String, ttlMs: Long): Envelope? {
        val url = "$BASE/$path"
        val now = System.currentTimeMillis()
        val body = cache[url]?.takeIf { it.expires > now }?.body
            ?: fetch(url).also { cache[url] = Cached(it, now + ttlMs) }
        return body?.let { AppJson.decodeFromString(Envelope.serializer(), it) }
    }

    private fun ttlFor(season: String) =
        if (season == "current" || season.toIntOrNull() == java.time.Year.now().value) SHORT_TTL_MS else LONG_TTL_MS

    suspend fun schedule(season: String = "current"): List<RaceWeekend> {
        val races = get("$season.json?limit=100", ttlFor(season))?.data?.raceTable?.races.orEmpty()
        return races.map { it.toWeekend() }
    }

    suspend fun driverStandings(season: String = "current"): StandingsList? =
        get("$season/driverStandings.json?limit=100", ttlFor(season))?.data?.standingsTable?.lists?.firstOrNull()

    suspend fun constructorStandings(season: String = "current"): StandingsList? =
        get("$season/constructorStandings.json?limit=100", ttlFor(season))?.data?.standingsTable?.lists?.firstOrNull()

    suspend fun raceResults(season: String, round: String): Race? =
        get("$season/$round/results.json?limit=100", ttlFor(season))?.data?.raceTable?.races?.firstOrNull()

    suspend fun qualifyingResults(season: String, round: String): Race? =
        get("$season/$round/qualifying.json?limit=100", ttlFor(season))?.data?.raceTable?.races?.firstOrNull()

    suspend fun sprintResults(season: String, round: String): Race? =
        get("$season/$round/sprint.json?limit=100", ttlFor(season))?.data?.raceTable?.races?.firstOrNull()

    suspend fun lastRaceResults(): Race? =
        get("current/last/results.json?limit=100", SHORT_TTL_MS)?.data?.raceTable?.races?.firstOrNull()
}

private fun instantOf(date: String?, time: String?): Instant? {
    if (date.isNullOrBlank()) return null
    val t = time?.takeIf { it.isNotBlank() } ?: return TimeFormat.parseUtc("${date}T12:00:00Z")
    return TimeFormat.parseUtc("${date}T${if (t.endsWith("Z")) t else t + "Z"}")
}

fun Race.toWeekend(): RaceWeekend {
    val sessions = buildList {
        firstPractice?.let { add(ScheduledSession("Practice 1", instantOf(it.date, it.time))) }
        secondPractice?.let { add(ScheduledSession("Practice 2", instantOf(it.date, it.time))) }
        thirdPractice?.let { add(ScheduledSession("Practice 3", instantOf(it.date, it.time))) }
        sprintQualifying?.let { add(ScheduledSession("Sprint Qualifying", instantOf(it.date, it.time))) }
        sprintShootout?.let { add(ScheduledSession("Sprint Shootout", instantOf(it.date, it.time))) }
        sprint?.let { add(ScheduledSession("Sprint", instantOf(it.date, it.time))) }
        qualifying?.let { add(ScheduledSession("Qualifying", instantOf(it.date, it.time))) }
        add(ScheduledSession("Race", instantOf(date, time)))
    }.sortedBy { it.start ?: Instant.MAX }
    return RaceWeekend(
        season = season.toIntOrNull() ?: 0,
        round = round.toIntOrNull() ?: 0,
        name = raceName,
        circuit = circuit.circuitName,
        locality = circuit.location.locality,
        country = circuit.location.country,
        raceStart = instantOf(date, time),
        sessions = sessions,
        wikipediaUrl = url,
    )
}
