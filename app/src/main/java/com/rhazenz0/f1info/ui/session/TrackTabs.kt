package com.rhazenz0.f1info.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhazenz0.f1info.data.timing.LapRecord
import com.rhazenz0.f1info.data.timing.RaceReplay
import com.rhazenz0.f1info.data.timing.SessionSnapshot
import com.rhazenz0.f1info.data.timing.TelemetryParser
import com.rhazenz0.f1info.data.timing.TrackEstimator
import com.rhazenz0.f1info.data.timing.TrackOutline
import com.rhazenz0.f1info.ui.components.EmptyState
import com.rhazenz0.f1info.ui.components.Pill
import com.rhazenz0.f1info.ui.components.SectionCard
import com.rhazenz0.f1info.ui.components.TeamBar
import com.rhazenz0.f1info.ui.components.TrackCar
import com.rhazenz0.f1info.ui.components.TrackPositionsMap
import com.rhazenz0.f1info.ui.theme.F1Colors
import com.rhazenz0.f1info.ui.theme.TimingText
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.Locale

/** One row of the list under the track map. */
private data class TrackRow(val number: String, val position: Int?, val gap: String, val note: String? = null)

private fun trackTint(status: String): Color? = when (status) {
    "2" -> F1Colors.Yellow
    "4", "6", "7" -> Color(0xFFFF9800)
    "5" -> F1Colors.Red
    else -> null
}

@Composable
private fun TrackList(s: SessionSnapshot, rows: List<TrackRow>, selected: String?, onSelect: (String) -> Unit) {
    rows.forEach { r ->
        Row(
            Modifier.fillMaxWidth().clickable { onSelect(r.number) }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(r.position?.toString() ?: "-", Modifier.width(28.dp), fontWeight = FontWeight.Black)
            TeamBar(s.teamColor(r.number), 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                s.tla(r.number), Modifier.weight(1f), fontWeight = FontWeight.Bold,
                color = if (r.number == selected) F1Colors.Red else MaterialTheme.colorScheme.onSurface,
            )
            r.note?.let { Pill(it, F1Colors.PitBlue, Color.White); Spacer(Modifier.width(8.dp)) }
            Text(r.gap, style = TimingText, textAlign = TextAlign.End)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

/** Live track map: real car coordinates with F1 TV, otherwise positions estimated from timing. */
@Composable
fun LiveTrackTab(
    s: SessionSnapshot,
    outline: TrackOutline?,
    live: LiveTelemetry,
    outlineFailed: Boolean,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) { withFrameMillis { }; value = System.currentTimeMillis(); delay(100) }
    }
    if (outline == null) {
        if (outlineFailed) EmptyState("The circuit map could not be loaded. Check your connection.")
        else Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
        }
        return
    }
    val running = s.sessionStatus == "Started"
    val realPositions = live.positions.isNotEmpty()
    val serverNow = s.serverNow(nowMs) ?: Instant.now()

    val rows = s.timing.filter { !it.retired && !it.stopped }
    val cars = if (!running && !realPositions) emptyList() else rows.mapNotNull { row ->
        if (row.inPit) return@mapNotNull null
        val xy = if (realPositions) live.positions[row.number] else {
            val p = s.lapProgress[row.number] ?: return@mapNotNull null
            outline.pointAt(TrackEstimator.lapFraction(p, serverNow))
        } ?: return@mapNotNull null
        TrackCar(row.number, s.tla(row.number), s.teamColor(row.number), xy.first, xy.second, row.position)
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            SectionCard(if (running) "Live track" else "Track", Modifier.padding(12.dp)) {
                TrackPositionsMap(outline, cars, selected = selected, trackTint = trackTint(s.trackStatus)) { selected = it }
                Text(
                    when {
                        !running && !realPositions -> "Cars appear here while a session is running. Watch any finished race in Sessions → Track."
                        realPositions -> "Real car positions from F1 TV data."
                        else -> "Positions are estimated from live timing (mini-sectors and lap times), typically within about 60 m. Real GPS positions need F1 TV."
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            TrackList(
                s,
                rows.map { r ->
                    TrackRow(
                        r.number, r.position,
                        if (r.position == 1) "Leader" else r.gap.ifBlank { r.interval },
                        note = if (r.inPit) "PIT" else null,
                    )
                },
                selected,
            ) { selected = it }
        }
    }
}

private val replaySpeeds = listOf(1, 4, 16, 60)

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
}

/** Replay of a finished session on the track map, from real car coordinates. */
@Composable
fun ReplayTrackTab(
    s: SessionSnapshot,
    telemetry: TelemetryUi,
    circuit: TrackOutline?,
    onLoad: () -> Unit,
    initialTimeMs: Long? = null,
) {
    val data = (telemetry as? TelemetryUi.Ready)?.data
    if (data == null) {
        ArchiveTelemetryTab(s, telemetry, onLoad)
        return
    }
    val replay = remember(data, circuit, s.laps) {
        val outline = circuit ?: fallbackOutline(s.laps, data)
        outline?.let { RaceReplay(data, s.laps, it) }
    }
    if (replay == null) { EmptyState("Not enough position data to draw this circuit."); return }

    var timeMs by rememberSaveable(replay.startMs) { mutableLongStateOf(initialTimeMs ?: replay.startMs) }
    var playing by remember { mutableStateOf(false) }
    var speed by rememberSaveable { mutableStateOf(16) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(playing, speed) {
        if (!playing) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (playing) {
            val now = withFrameMillis { it }
            timeMs = (timeMs + (now - last) * speed).coerceAtMost(replay.endMs)
            last = now
            if (timeMs >= replay.endMs) playing = false
        }
    }

    val cars = replay.at(timeMs)
    val totalLaps = s.totalLaps?.takeIf { it > 0 }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            SectionCard("Race replay", Modifier.padding(12.dp)) {
                TrackPositionsMap(
                    replay.outline,
                    cars.map { c -> TrackCar(c.driver, s.tla(c.driver), s.teamColor(c.driver), c.x, c.y, c.position) },
                    selected = selected,
                ) { selected = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = {
                        if (timeMs >= replay.endMs) timeMs = replay.startMs
                        playing = !playing
                    }) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play")
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        val lap = replay.lapOfLeader(cars).let { if (totalLaps != null) it.coerceAtMost(totalLaps) else it }
                        Text(
                            "Lap $lap${totalLaps?.let { "/$it" } ?: ""} · ${formatClock(timeMs - replay.startMs)}",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            com.rhazenz0.f1info.util.TimeFormat.localTime(Instant.ofEpochMilli(timeMs), seconds = true),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val span = (replay.endMs - replay.startMs).coerceAtLeast(1)
                Slider(
                    value = ((timeMs - replay.startMs).toFloat() / span).coerceIn(0f, 1f),
                    onValueChange = { timeMs = replay.startMs + (it * span).toLong() },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Speed", style = MaterialTheme.typography.labelSmall)
                    replaySpeeds.forEach { sp ->
                        FilterChip(selected = speed == sp, onClick = { speed = sp }, label = { Text("${sp}×", fontSize = 12.sp) })
                    }
                }
            }
        }
        item {
            TrackList(
                s,
                cars.map { c ->
                    TrackRow(
                        c.driver, c.position,
                        when {
                            c.gapToLeader == null -> "Leader"
                            c.lapsBehind >= 1 -> "+${c.lapsBehind} lap${if (c.lapsBehind > 1) "s" else ""}"
                            else -> String.format(Locale.US, "+%.1fs", c.gapToLeader)
                        },
                    )
                },
                selected,
            ) { selected = it }
            Text(
                "Positions and gaps come from real car coordinates. Gaps are approximate.",
                Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Circuit outline from a clean lap of the data itself, when the circuit service is unavailable. */
private fun fallbackOutline(
    laps: Map<String, List<LapRecord>>,
    data: com.rhazenz0.f1info.data.timing.TelemetryData,
): TrackOutline? {
    val lap = laps.values.flatten()
        .filter { it.lapTime != null && !it.pitInLap && !it.pitOutLap && data.position.containsKey(it.driver) }
        .minByOrNull { it.lapTime!! } ?: return null
    val trace = TelemetryParser.lapTrace(data, lap) ?: return null
    return TrackOutline.fromLap(trace.mapX, trace.mapY)
}
