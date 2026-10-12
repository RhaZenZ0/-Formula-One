package com.rhazenz0.f1info.data.timing

import com.rhazenz0.f1info.data.net.Http
import com.rhazenz0.f1info.util.TimeFormat
import com.rhazenz0.f1info.util.at
import com.rhazenz0.f1info.util.elements
import com.rhazenz0.f1info.util.int
import com.rhazenz0.f1info.util.text
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant

/** A finished (or in-progress) session replayed from the official timing archive. */
class LoadedSession(
    val ref: SessionRef,
    val accumulator: SessionAccumulator,
    val snapshot: SessionSnapshot,
)

/**
 * Reads Formula 1's own live timing archive (livetiming.formula1.com/static), the primary
 * source that third-party F1 data services are built on. No account is needed.
 */
class ArchiveClient(
    private val fetch: suspend (url: String, immutable: Boolean) -> String? = { url, immutable ->
        Http.getString(url, missingIsNull = true, immutable = immutable)
    },
) {
    companion object {
        const val STATIC_BASE = "https://livetiming.formula1.com/static/"

        /** Topics loaded as final-state keyframes. */
        private val KEYFRAME_TOPICS = listOf(
            "SessionInfo", "DriverList", "TimingData", "TimingAppData", "TimingStats", "LapSeries",
            "PitStopSeries", "TeamRadio", "RaceControlMessages", "ChampionshipPrediction",
            "LapCount", "SessionStatus", "TrackStatus", "ExtrapolatedClock",
        )

        /** Topics replayed in order to rebuild history. */
        private val STREAM_TOPICS = listOf(
            "Heartbeat", "SessionStatus", "TrackStatus", "TimingData", "WeatherData", "PitLaneTimeCollection",
        )
    }

    suspend fun meetings(year: Int): List<MeetingRef> {
        val text = fetch("$STATIC_BASE$year/Index.json", false) ?: return emptyList()
        return parseIndex(year, text)
    }

    internal fun parseIndex(year: Int, text: String): List<MeetingRef> {
        val root = FeedCodec.parseJson(text)
        return root.at("Meetings").elements().map { m ->
            val name = m.at("Name").text().orEmpty()
            val location = m.at("Location").text().orEmpty()
            val country = m.at("Country", "Name").text().orEmpty()
            val circuit = m.at("Circuit", "ShortName").text().orEmpty()
            val official = m.at("OfficialName").text().orEmpty()
            val key = m.at("Key").int() ?: 0
            val sessions = m.at("Sessions").elements().mapNotNull { s ->
                val path = s.at("Path").text() ?: return@mapNotNull null
                val offset = s.at("GmtOffset").text().orEmpty()
                SessionRef(
                    year = year,
                    meetingKey = key,
                    meetingName = name,
                    meetingOfficialName = official,
                    location = location,
                    country = country,
                    circuit = circuit,
                    sessionKey = s.at("Key").int() ?: 0,
                    sessionName = s.at("Name").text().orEmpty(),
                    sessionType = s.at("Type").text().orEmpty(),
                    start = TimeFormat.trackLocalToInstant(s.at("StartDate").text(), offset),
                    end = TimeFormat.trackLocalToInstant(s.at("EndDate").text(), offset),
                    gmtOffset = offset,
                    path = path,
                )
            }.sortedBy { it.start ?: Instant.MAX }
            MeetingRef(year, key, name, official, location, country, circuit, sessions)
        }.sortedBy { it.start ?: Instant.MAX }
    }

    /** True once the session ended long enough ago that its archive files no longer change. */
    private fun isFinal(ref: SessionRef): Boolean =
        ref.end?.let { Instant.now().isAfter(it.plusSeconds(6 * 3600)) } ?: false

    suspend fun loadSession(ref: SessionRef, onProgress: (String) -> Unit = {}): LoadedSession = coroutineScope {
        val base = STATIC_BASE + ref.path
        val immutable = isFinal(ref)
        onProgress("Downloading timing data…")

        val keyframes = KEYFRAME_TOPICS.map { topic ->
            async { topic to fetch("$base$topic.json", immutable) }
        }
        val streams = STREAM_TOPICS.map { topic ->
            async { topic to fetch("$base$topic.jsonStream", immutable) }
        }
        val keyframeTexts = keyframes.awaitAll().toMap()
        val streamTexts = streams.awaitAll().toMap()

        if (keyframeTexts.values.all { it == null } && streamTexts.values.all { it == null }) {
            throw IllegalStateException("No timing data has been published for this session yet.")
        }

        onProgress("Rebuilding lap history…")
        val acc = SessionAccumulator()
        replay(acc, ref, streamTexts)

        for ((topic, text) in keyframeTexts) {
            if (text.isNullOrBlank()) continue
            runCatching { acc.setKeyframe(topic, FeedCodec.parseJson(text)) }
        }
        LoadedSession(ref, acc, acc.snapshot())
    }

    /** Replays several streams in time order into [acc]. */
    internal fun replay(acc: SessionAccumulator, ref: SessionRef, streamTexts: Map<String, String?>) {
        val parsed = streamTexts.mapValues { (_, text) -> text?.let { FeedCodec.parseStream(it) }.orEmpty() }
        val baseTime = streamBaseTime(parsed["Heartbeat"].orEmpty()) ?: ref.start ?: Instant.EPOCH

        val events = ArrayList<Triple<Long, String, String>>()
        for ((topic, lines) in parsed) {
            if (topic == "Heartbeat") continue
            lines.forEach { events.add(Triple(it.offsetMs, topic, it.payload)) }
        }
        events.sortBy { it.first }
        for ((offset, topic, payload) in events) {
            val json = runCatching { FeedCodec.parseJson(payload) }.getOrNull() ?: continue
            acc.apply(topic, json, baseTime.plusMillis(offset))
        }
    }

    /** Stream offsets count from an unpublished start time; Heartbeat lines reveal it. */
    internal fun streamBaseTime(heartbeat: List<StreamLine>): Instant? {
        val samples = heartbeat.take(5).mapNotNull { line ->
            val utc = runCatching { TimeFormat.parseUtc(FeedCodec.parseJson(line.payload).at("Utc").text()) }.getOrNull()
            utc?.minusMillis(line.offsetMs)
        }
        if (samples.isEmpty()) return null
        return samples.sorted()[samples.size / 2]
    }

    /** Downloads and decodes the car telemetry and car position streams (about 18 MB for a race). */
    suspend fun telemetry(ref: SessionRef, onProgress: (String) -> Unit = {}): TelemetryData = coroutineScope {
        val base = STATIC_BASE + ref.path
        val immutable = isFinal(ref)
        onProgress("Downloading car telemetry…")
        val car = async { fetch("${base}CarData.z.jsonStream", immutable) }
        val pos = async { fetch("${base}Position.z.jsonStream", immutable) }
        val carText = car.await()
        val posText = pos.await()
        if (carText == null && posText == null) {
            throw IllegalStateException("Car telemetry is not available for this session.")
        }
        onProgress("Decoding telemetry…")
        TelemetryParser.parseArchive(carText, posText)
    }
}
