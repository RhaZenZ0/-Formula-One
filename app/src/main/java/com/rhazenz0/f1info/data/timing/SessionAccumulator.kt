package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.util.TimeFormat
import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.bool
import com.rhazenz0.f1info.util.double
import com.rhazenz0.f1info.util.elements
import com.rhazenz0.f1info.util.entries
import com.rhazenz0.f1info.util.int
import com.rhazenz0.f1info.util.text
import com.rhazenz0.f1info.util.textOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

/**
 * Holds the merged state of every timing topic for one session and derives a [SessionSnapshot].
 * The same accumulator serves archived replays and the live connection.
 */
class SessionAccumulator(private val staticBaseUrl: String = ArchiveClient.STATIC_BASE) {

    private val state = HashMap<String, JsonElement>()
    private val lapBuilder = LapHistoryBuilder()
    private val weather = ArrayList<WeatherSample>()
    private val trackHistory = ArrayList<TrackStatusChange>()
    private val pitLaneEvents = LinkedHashMap<String, PitStopInfo>()
    private var lastUpdate: Instant? = null

    /** Topics the accumulator understands (CarData.z / Position.z are handled by telemetry code). */
    val topics: List<String> = TOPICS

    fun topic(name: String): JsonElement? = state[name]

    fun apply(topic: String, patch: JsonElement, at: Instant?) {
        val name = topic.removeSuffix(".z")
        val merged = FeedMerge.merge(state[name], patch)
        state[name] = merged
        if (at != null) lastUpdate = at

        when (name) {
            "TimingData" -> lapBuilder.onLines(patch.at("Lines"), at)
            "SessionStatus" -> if (patch.at("Status").text() == "Started") lapBuilder.resetAll(at)
            "WeatherData" -> weather.add(weatherSample(merged, at))
            "TrackStatus" -> trackHistory.add(
                TrackStatusChange(at, merged.at("Status").text().orEmpty(), merged.at("Message").text().orEmpty())
            )
            "PitLaneTimeCollection" -> recordPitLane(patch, at)
        }
    }

    /** Loads the full state sent when a live connection subscribes. */
    fun applyInitial(topic: String, value: JsonElement, at: Instant?) {
        val name = topic.removeSuffix(".z")
        state[name] = value
        if (at != null) lastUpdate = at
        when (name) {
            "TimingData" -> lapBuilder.prime(value.at("Lines"), at)
            "WeatherData" -> weather.add(weatherSample(value, at))
            "TrackStatus" -> trackHistory.add(
                TrackStatusChange(at, value.at("Status").text().orEmpty(), value.at("Message").text().orEmpty())
            )
        }
    }

    /** Replaces a topic with a full keyframe without replaying history. */
    fun setKeyframe(topic: String, value: JsonElement) {
        state[topic.removeSuffix(".z")] = value
        if (topic == "WeatherData" && weather.isEmpty()) weather.add(weatherSample(value, null))
    }

    private fun recordPitLane(patch: JsonElement, at: Instant?) {
        for ((driver, entry) in patch.at("PitTimes").entries()) {
            if (entry !is JsonObject) continue
            val duration = entry.at("Duration").double() ?: continue
            val lap = entry.at("Lap").int()
            pitLaneEvents["$driver-$lap"] = PitStopInfo(driver, lap, at, null, duration)
        }
    }

    private fun weatherSample(w: JsonElement?, at: Instant?) = WeatherSample(
        time = at,
        // The feed occasionally reports a 0 reading for one sample; treat it as missing.
        airTemp = w.at("AirTemp").double()?.takeIf { it != 0.0 },
        trackTemp = w.at("TrackTemp").double()?.takeIf { it != 0.0 },
        humidity = w.at("Humidity").double()?.takeIf { it != 0.0 },
        pressure = w.at("Pressure").double(),
        rainfall = (w.at("Rainfall").double() ?: 0.0) > 0.0,
        windSpeed = w.at("WindSpeed").double(),
        windDirection = w.at("WindDirection").int(),
    )

    fun snapshot(now: Instant = Instant.now()): SessionSnapshot {
        val info = state["SessionInfo"]
        val drivers = parseDrivers(state["DriverList"])
        val stints = parseStints(state["TimingAppData"])
        return SessionSnapshot(
            sessionName = info.at("Name").text().orEmpty(),
            meetingName = info.at("Meeting", "Name").text().orEmpty(),
            sessionType = info.at("Type").text().orEmpty(),
            sessionStatus = state["SessionStatus"].at("Status").text() ?: info.at("SessionStatus").text().orEmpty(),
            trackStatus = state["TrackStatus"].at("Status").text().orEmpty(),
            trackMessage = state["TrackStatus"].at("Message").text().orEmpty(),
            currentLap = state["LapCount"].at("CurrentLap").int(),
            totalLaps = state["LapCount"].at("TotalLaps").int(),
            remaining = remaining(state["ExtrapolatedClock"], now),
            drivers = drivers,
            timing = parseTiming(state["TimingData"], state["TimingAppData"], stints),
            laps = lapBuilder.laps(),
            lapPositions = parseLapSeries(state["LapSeries"]),
            stints = stints,
            pitStops = parsePitStops(state["PitStopSeries"]).ifEmpty { pitLaneEvents.values.toList() },
            radio = parseRadio(state["TeamRadio"], info.at("Path").text()),
            weather = weather.toList(),
            raceControl = parseRaceControl(state["RaceControlMessages"]),
            championshipDrivers = parseChampionshipDrivers(state["ChampionshipPrediction"]),
            championshipTeams = parseChampionshipTeams(state["ChampionshipPrediction"]),
            trackStatusHistory = trackHistory.toList(),
            bestTimes = parseBestTimes(state["TimingStats"]),
            updatedAt = lastUpdate,
        )
    }

    private fun remaining(clock: JsonElement?, now: Instant): String? {
        val text = clock.at("Remaining").textOrNull() ?: return null
        val parts = text.split(":").mapNotNull { it.toLongOrNull() }
        if (parts.size != 3) return text
        var seconds = parts[0] * 3600 + parts[1] * 60 + parts[2]
        if (clock.at("Extrapolating").bool() == true) {
            val utc = TimeFormat.parseUtc(clock.at("Utc").text())
            if (utc != null) seconds -= Duration.between(utc, now).seconds.coerceAtLeast(0)
        }
        seconds = seconds.coerceAtLeast(0)
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    private fun parseDrivers(list: JsonElement?): Map<String, DriverInfo> {
        val out = LinkedHashMap<String, DriverInfo>()
        for ((key, d) in list.entries()) {
            if (d !is JsonObject) continue
            val number = d.at("RacingNumber").textOrNull() ?: key
            out[number] = DriverInfo(
                number = number,
                tla = d.at("Tla").text().orEmpty(),
                fullName = d.at("FullName").text().orEmpty(),
                firstName = d.at("FirstName").text().orEmpty(),
                lastName = d.at("LastName").text().orEmpty(),
                teamName = d.at("TeamName").text().orEmpty(),
                teamColour = d.at("TeamColour").text().orEmpty(),
                headshotUrl = d.at("HeadshotUrl").textOrNull(),
                line = d.at("Line").int() ?: 99,
            )
        }
        return out
    }

    private fun parseTiming(
        timing: JsonElement?,
        app: JsonElement?,
        stints: Map<String, List<StintInfo>>,
    ): List<TimingRow> {
        val rows = timing.at("Lines").entries().mapNotNull { (number, line) ->
            if (line !is JsonObject) return@mapNotNull null
            val stats = line.at("Stats").elements()
            val bestLapTimes = line.at("BestLapTimes").elements()
            val gap = line.at("GapToLeader").textOrNull()
                ?: line.at("TimeDiffToFastest").textOrNull()
                ?: stats.lastOrNull { it.at("TimeDiffToFastest").textOrNull() != null }?.at("TimeDiffToFastest").textOrNull()
                ?: ""
            val interval = line.at("IntervalToPositionAhead", "Value").textOrNull()
                ?: line.at("TimeDiffToPositionAhead").textOrNull()
                ?: stats.lastOrNull { it.at("TimeDifftoPositionAhead").textOrNull() != null }?.at("TimeDifftoPositionAhead").textOrNull()
                ?: ""
            val best = line.at("BestLapTime", "Value").textOrNull()
                ?: bestLapTimes.lastOrNull { it.at("Value").textOrNull() != null }?.at("Value").textOrNull()
                ?: ""
            val lastStint = stints[number]?.lastOrNull()
            val appLine = app.at("Lines", number)
            TimingRow(
                number = number,
                position = line.at("Position").int(),
                gap = gap,
                interval = interval,
                lastLap = line.at("LastLapTime", "Value").text().orEmpty(),
                bestLap = best,
                laps = line.at("NumberOfLaps").int(),
                pitStops = line.at("NumberOfPitStops").int(),
                inPit = line.at("InPit").bool() == true,
                pitOut = line.at("PitOut").bool() == true,
                retired = line.at("Retired").bool() == true,
                stopped = line.at("Stopped").bool() == true,
                knockedOut = line.at("KnockedOut").bool() == true,
                compound = lastStint?.compound,
                tyreAge = appLine.at("Stints").elements().lastOrNull()?.at("TotalLaps").int(),
                gridPosition = appLine.at("GridPos").textOrNull()?.toIntOrNull(),
            )
        }
        return rows.sortedWith(compareBy<TimingRow> { it.position ?: Int.MAX_VALUE }.thenBy { it.number.toIntOrNull() ?: 0 })
    }

    private fun parseStints(app: JsonElement?): Map<String, List<StintInfo>> {
        val out = LinkedHashMap<String, List<StintInfo>>()
        for ((number, line) in app.at("Lines").entries()) {
            var nextLap = 1
            val list = line.at("Stints").elements().mapIndexedNotNull { index, s ->
                val compound = s.at("Compound").textOrNull() ?: return@mapIndexedNotNull null
                val startLaps = s.at("StartLaps").int() ?: 0
                val total = s.at("TotalLaps").int() ?: startLaps
                val driven = (total - startLaps).coerceAtLeast(0)
                val start = nextLap
                val end = (start + driven - 1).coerceAtLeast(start)
                nextLap = end + 1
                StintInfo(
                    driver = number,
                    stintNumber = index + 1,
                    compound = compound,
                    isNew = s.at("New").bool() == true,
                    lapStart = start,
                    lapEnd = end,
                    tyreAgeAtStart = startLaps,
                )
            }
            if (list.isNotEmpty()) out[number] = list
        }
        return out
    }

    private fun parseLapSeries(series: JsonElement?): Map<String, List<Int?>> =
        series.entries().associate { (number, d) ->
            number to d.at("LapPosition").elements().map { it.text()?.toIntOrNull() }
        }.filterValues { it.isNotEmpty() }

    private fun parsePitStops(series: JsonElement?): List<PitStopInfo> =
        series.at("PitTimes").entries().flatMap { (number, stops) ->
            stops.elements().mapNotNull { s ->
                val p = s.at("PitStop") ?: return@mapNotNull null
                PitStopInfo(
                    driver = p.at("RacingNumber").textOrNull() ?: number,
                    lap = p.at("Lap").int(),
                    time = TimeFormat.parseUtc(s.at("Timestamp").text()),
                    stopDuration = p.at("PitStopTime").double(),
                    laneDuration = p.at("PitLaneTime").double(),
                )
            }
        }.sortedWith(compareBy<PitStopInfo> { it.time ?: Instant.MAX }.thenBy { it.lap ?: 0 })

    private fun parseRadio(radio: JsonElement?, sessionPath: String?): List<RadioClip> {
        val base = staticBaseUrl + (sessionPath ?: "")
        return radio.at("Captures").elements().mapNotNull { c ->
            val path = c.at("Path").textOrNull() ?: return@mapNotNull null
            RadioClip(
                driver = c.at("RacingNumber").text().orEmpty(),
                time = TimeFormat.parseUtc(c.at("Utc").text()),
                url = if (path.startsWith("http")) path else base + path,
            )
        }.sortedByDescending { it.time }
    }

    private fun parseRaceControl(rc: JsonElement?): List<RaceControlMessage> =
        rc.at("Messages").elements().map { m ->
            RaceControlMessage(
                time = TimeFormat.parseUtc(m.at("Utc").text()),
                lap = m.at("Lap").int(),
                category = m.at("Category").text().orEmpty(),
                flag = m.at("Flag").textOrNull(),
                scope = m.at("Scope").textOrNull(),
                sector = m.at("Sector").int(),
                driver = m.at("RacingNumber").textOrNull(),
                message = m.at("Message").text().orEmpty(),
            )
        }.sortedByDescending { it.time }

    private fun parseChampionshipDrivers(cp: JsonElement?): List<ChampionshipDriverRow> =
        cp.at("Drivers").entries().map { (number, d) ->
            ChampionshipDriverRow(
                driver = d.at("RacingNumber").textOrNull() ?: number,
                currentPosition = d.at("CurrentPosition").int(),
                predictedPosition = d.at("PredictedPosition").int(),
                currentPoints = d.at("CurrentPoints").double(),
                predictedPoints = d.at("PredictedPoints").double(),
            )
        }.sortedBy { it.predictedPosition ?: it.currentPosition ?: Int.MAX_VALUE }

    private fun parseChampionshipTeams(cp: JsonElement?): List<ChampionshipTeamRow> =
        cp.at("Teams").entries().map { (name, t) ->
            ChampionshipTeamRow(
                team = t.at("TeamName").textOrNull() ?: name,
                currentPosition = t.at("CurrentPosition").int(),
                predictedPosition = t.at("PredictedPosition").int(),
                currentPoints = t.at("CurrentPoints").double(),
                predictedPoints = t.at("PredictedPoints").double(),
            )
        }.sortedBy { it.predictedPosition ?: it.currentPosition ?: Int.MAX_VALUE }

    private fun parseBestTimes(stats: JsonElement?): Map<String, BestTimes> =
        stats.at("Lines").entries().associate { (number, l) ->
            number to BestTimes(
                driver = number,
                bestLap = TimeFormat.parseLapTime(l.at("PersonalBestLapTime", "Value").textOrNull()),
                bestLapNumber = l.at("PersonalBestLapTime", "Lap").int(),
                bestSectors = (0..2).map { i ->
                    TimeFormat.parseLapTime(l.at("BestSectors").elements().getOrNull(i).at("Value").textOrNull())
                },
                bestSpeeds = listOf("I1", "I2", "FL", "ST").associateWith { k ->
                    l.at("BestSpeeds", k, "Value").textOrNull()?.toIntOrNull()
                },
            )
        }

    companion object {
        val TOPICS = listOf(
            "Heartbeat", "SessionInfo", "SessionStatus", "TrackStatus", "LapCount", "ExtrapolatedClock",
            "DriverList", "TimingData", "TimingAppData", "TimingStats", "LapSeries",
            "PitStopSeries", "PitLaneTimeCollection", "TeamRadio", "RaceControlMessages",
            "WeatherData", "ChampionshipPrediction",
        )
    }
}
