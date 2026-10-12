package com.rhazenz0.f1info.data.timing

import java.time.Instant

/** A session listed in the F1 live timing archive index. */
data class SessionRef(
    val year: Int,
    val meetingKey: Int,
    val meetingName: String,
    val meetingOfficialName: String,
    val location: String,
    val country: String,
    val circuit: String,
    val sessionKey: Int,
    val sessionName: String,
    val sessionType: String,
    val start: Instant?,
    val end: Instant?,
    val gmtOffset: String,
    /** Relative archive path, e.g. "2026/2026-10-11_Singapore_Grand_Prix/2026-10-11_Race/". */
    val path: String,
    val circuitKey: Int? = null,
) {
    val isRace: Boolean get() = sessionType.equals("Race", ignoreCase = true)
    val isQualifying: Boolean get() = sessionType.equals("Qualifying", ignoreCase = true)
    val title: String get() = "$meetingName · $sessionName"
}

data class MeetingRef(
    val year: Int,
    val key: Int,
    val name: String,
    val officialName: String,
    val location: String,
    val country: String,
    val circuit: String,
    val sessions: List<SessionRef>,
) {
    val start: Instant? get() = sessions.mapNotNull { it.start }.minOrNull()
}

data class DriverInfo(
    val number: String,
    val tla: String,
    val fullName: String,
    val firstName: String,
    val lastName: String,
    val teamName: String,
    /** "RRGGBB" without '#'. */
    val teamColour: String,
    val headshotUrl: String?,
    val line: Int,
) {
    val displayName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { fullName }
}

/** A row of the timing tower. */
data class TimingRow(
    val number: String,
    val position: Int?,
    val gap: String,
    val interval: String,
    val lastLap: String,
    val bestLap: String,
    val laps: Int?,
    val pitStops: Int?,
    val inPit: Boolean,
    val pitOut: Boolean,
    val retired: Boolean,
    val stopped: Boolean,
    val knockedOut: Boolean,
    val compound: String?,
    val tyreAge: Int?,
    val gridPosition: Int?,
)

/** Mini-sector status codes reported by the timing feed. */
enum class SegmentStatus { NONE, YELLOW, GREEN, PURPLE, PIT, OTHER;
    companion object {
        fun of(code: Int?): SegmentStatus = when (code) {
            null, 0 -> NONE
            2048 -> YELLOW
            2049 -> GREEN
            2051 -> PURPLE
            2064 -> PIT
            else -> OTHER
        }
    }
}

data class LapRecord(
    val driver: String,
    val lap: Int,
    val start: Instant?,
    val end: Instant?,
    val lapTime: Double?,
    val sectors: List<Double?>,
    val i1Speed: Int?,
    val i2Speed: Int?,
    val finishSpeed: Int?,
    val speedTrap: Int?,
    val segments: List<List<SegmentStatus>>,
    val pitOutLap: Boolean,
    val pitInLap: Boolean,
)

data class StintInfo(
    val driver: String,
    val stintNumber: Int,
    val compound: String,
    val isNew: Boolean,
    val lapStart: Int,
    val lapEnd: Int,
    val tyreAgeAtStart: Int,
)

data class PitStopInfo(
    val driver: String,
    val lap: Int?,
    val time: Instant?,
    val stopDuration: Double?,
    val laneDuration: Double?,
)

data class RadioClip(val driver: String, val time: Instant?, val url: String)

data class WeatherSample(
    val time: Instant?,
    val airTemp: Double?,
    val trackTemp: Double?,
    val humidity: Double?,
    val pressure: Double?,
    val rainfall: Boolean,
    val windSpeed: Double?,
    val windDirection: Int?,
)

data class RaceControlMessage(
    val time: Instant?,
    val lap: Int?,
    val category: String,
    val flag: String?,
    val scope: String?,
    val sector: Int?,
    val driver: String?,
    val message: String,
)

data class ChampionshipDriverRow(
    val driver: String,
    val currentPosition: Int?,
    val predictedPosition: Int?,
    val currentPoints: Double?,
    val predictedPoints: Double?,
)

data class ChampionshipTeamRow(
    val team: String,
    val currentPosition: Int?,
    val predictedPosition: Int?,
    val currentPoints: Double?,
    val predictedPoints: Double?,
)

data class TrackStatusChange(val time: Instant?, val status: String, val message: String)

data class BestTimes(
    val driver: String,
    val bestLap: Double?,
    val bestLapNumber: Int?,
    val bestSectors: List<Double?>,
    val bestSpeeds: Map<String, Int?>,
)

/** Everything the session screens show, at one moment in time. */
data class SessionSnapshot(
    val sessionName: String = "",
    val meetingName: String = "",
    val sessionType: String = "",
    val sessionStatus: String = "",
    val trackStatus: String = "",
    val trackMessage: String = "",
    val currentLap: Int? = null,
    val totalLaps: Int? = null,
    val remaining: String? = null,
    val drivers: Map<String, DriverInfo> = emptyMap(),
    val timing: List<TimingRow> = emptyList(),
    val laps: Map<String, List<LapRecord>> = emptyMap(),
    val lapPositions: Map<String, List<Int?>> = emptyMap(),
    val stints: Map<String, List<StintInfo>> = emptyMap(),
    val pitStops: List<PitStopInfo> = emptyList(),
    val radio: List<RadioClip> = emptyList(),
    val weather: List<WeatherSample> = emptyList(),
    val raceControl: List<RaceControlMessage> = emptyList(),
    val championshipDrivers: List<ChampionshipDriverRow> = emptyList(),
    val championshipTeams: List<ChampionshipTeamRow> = emptyList(),
    val trackStatusHistory: List<TrackStatusChange> = emptyList(),
    val bestTimes: Map<String, BestTimes> = emptyMap(),
    val updatedAt: Instant? = null,
    /** Device clock (epoch ms) when [updatedAt] was received; lets the UI advance server time smoothly. */
    val receivedAtMs: Long = 0L,
    val circuitKey: Int? = null,
    val year: Int? = null,
    val lapProgress: Map<String, LapProgress> = emptyMap(),
) {
    /** Best guess of the timing server's clock right now. */
    fun serverNow(nowMs: Long = System.currentTimeMillis()): Instant? =
        updatedAt?.plusMillis((nowMs - receivedAtMs).coerceIn(0L, 60_000L))

    fun driver(number: String): DriverInfo? = drivers[number]
    fun tla(number: String): String = drivers[number]?.tla?.ifBlank { null } ?: "#$number"
}
