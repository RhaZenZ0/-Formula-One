package com.rhazenz0.f1info.data.jolpica

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// JSON returned by the Jolpica F1 API (the maintained successor of the Ergast API).

@Serializable
data class Envelope(@SerialName("MRData") val data: MrData = MrData())

@Serializable
data class MrData(
    val total: String? = null,
    @SerialName("RaceTable") val raceTable: RaceTable? = null,
    @SerialName("StandingsTable") val standingsTable: StandingsTable? = null,
)

@Serializable
data class RaceTable(
    val season: String? = null,
    @SerialName("Races") val races: List<Race> = emptyList(),
)

@Serializable
data class Race(
    val season: String = "",
    val round: String = "",
    val url: String? = null,
    val raceName: String = "",
    @SerialName("Circuit") val circuit: Circuit = Circuit(),
    val date: String = "",
    val time: String? = null,
    @SerialName("FirstPractice") val firstPractice: SessionTime? = null,
    @SerialName("SecondPractice") val secondPractice: SessionTime? = null,
    @SerialName("ThirdPractice") val thirdPractice: SessionTime? = null,
    @SerialName("Qualifying") val qualifying: SessionTime? = null,
    @SerialName("Sprint") val sprint: SessionTime? = null,
    @SerialName("SprintQualifying") val sprintQualifying: SessionTime? = null,
    @SerialName("SprintShootout") val sprintShootout: SessionTime? = null,
    @SerialName("Results") val results: List<RaceResult> = emptyList(),
    @SerialName("SprintResults") val sprintResults: List<RaceResult> = emptyList(),
    @SerialName("QualifyingResults") val qualifyingResults: List<QualifyingResult> = emptyList(),
)

@Serializable
data class SessionTime(val date: String = "", val time: String? = null)

@Serializable
data class Circuit(
    val circuitId: String = "",
    val url: String? = null,
    val circuitName: String = "",
    @SerialName("Location") val location: Location = Location(),
)

@Serializable
data class Location(
    val lat: String? = null,
    val long: String? = null,
    val locality: String = "",
    val country: String = "",
)

@Serializable
data class Driver(
    val driverId: String = "",
    val permanentNumber: String? = null,
    val code: String? = null,
    val url: String? = null,
    val givenName: String = "",
    val familyName: String = "",
    val dateOfBirth: String? = null,
    val nationality: String = "",
) {
    val fullName: String get() = "$givenName $familyName".trim()
}

@Serializable
data class Constructor(
    val constructorId: String = "",
    val url: String? = null,
    val name: String = "",
    val nationality: String = "",
)

@Serializable
data class ResultTime(val millis: String? = null, val time: String? = null)

@Serializable
data class FastestLap(
    val rank: String? = null,
    val lap: String? = null,
    @SerialName("Time") val time: ResultTime? = null,
)

@Serializable
data class RaceResult(
    val number: String? = null,
    val position: String? = null,
    val positionText: String = "",
    val points: String = "0",
    @SerialName("Driver") val driver: Driver = Driver(),
    @SerialName("Constructor") val constructor: Constructor = Constructor(),
    val grid: String? = null,
    val laps: String? = null,
    val status: String = "",
    @SerialName("Time") val time: ResultTime? = null,
    @SerialName("FastestLap") val fastestLap: FastestLap? = null,
)

@Serializable
data class QualifyingResult(
    val number: String? = null,
    val position: String = "",
    @SerialName("Driver") val driver: Driver = Driver(),
    @SerialName("Constructor") val constructor: Constructor = Constructor(),
    @SerialName("Q1") val q1: String? = null,
    @SerialName("Q2") val q2: String? = null,
    @SerialName("Q3") val q3: String? = null,
)

@Serializable
data class StandingsTable(
    val season: String? = null,
    val round: String? = null,
    @SerialName("StandingsLists") val lists: List<StandingsList> = emptyList(),
)

@Serializable
data class StandingsList(
    val season: String? = null,
    val round: String? = null,
    @SerialName("DriverStandings") val drivers: List<DriverStanding> = emptyList(),
    @SerialName("ConstructorStandings") val constructors: List<ConstructorStanding> = emptyList(),
)

@Serializable
data class DriverStanding(
    val position: String? = null,
    val positionText: String = "",
    val points: String = "0",
    val wins: String = "0",
    @SerialName("Driver") val driver: Driver = Driver(),
    @SerialName("Constructors") val constructors: List<Constructor> = emptyList(),
)

@Serializable
data class ConstructorStanding(
    val position: String? = null,
    val positionText: String = "",
    val points: String = "0",
    val wins: String = "0",
    @SerialName("Constructor") val constructor: Constructor = Constructor(),
)
