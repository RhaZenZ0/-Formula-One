package com.rhazenz0.f1info.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhazenz0.f1info.data.timing.SessionSnapshot
import com.rhazenz0.f1info.data.timing.WeatherSample
import com.rhazenz0.f1info.ui.components.ChartLegend
import com.rhazenz0.f1info.ui.components.ChartSeries
import com.rhazenz0.f1info.ui.components.EmptyState
import com.rhazenz0.f1info.ui.components.LineChart
import com.rhazenz0.f1info.ui.components.Pill
import com.rhazenz0.f1info.ui.components.SectionCard
import com.rhazenz0.f1info.ui.components.StatTile
import com.rhazenz0.f1info.ui.components.TeamBar
import com.rhazenz0.f1info.ui.components.TyreBadge
import com.rhazenz0.f1info.ui.components.TyreStrategyChart
import com.rhazenz0.f1info.ui.theme.F1Colors
import com.rhazenz0.f1info.ui.theme.TimingText
import com.rhazenz0.f1info.util.TimeFormat
import java.util.Locale

@Composable
fun PitStopsTab(s: SessionSnapshot) {
    if (s.stints.isEmpty() && s.pitStops.isEmpty()) { EmptyState("No tyre or pit stop data yet."); return }
    val order = s.driverOrder().filter { s.stints.containsKey(it) }
    val totalLaps = listOfNotNull(
        s.totalLaps?.takeIf { it > 0 },
        s.stints.values.flatten().maxOfOrNull { it.lapEnd },
    ).maxOrNull() ?: 1
    val fastestStop = s.pitStops.mapNotNull { it.stopDuration }.minOrNull()

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (order.isNotEmpty()) {
            item {
                SectionCard("Tyre strategy") {
                    TyreStrategyChart(order.map { s.tla(it) to s.stints.getValue(it) }, totalLaps)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("SOFT", "MEDIUM", "HARD", "INTERMEDIATE", "WET").forEach { c ->
                            TyreBadge(c, 16.dp); Spacer(Modifier.width(4.dp))
                            Text(c.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 10.sp)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    Text("Faded bars are used tyres.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (s.pitStops.isNotEmpty()) {
            item {
                SectionCard("Pit stops") {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        listOf("LAP" to 40.dp, "DRIVER" to 0.dp, "STOPPED" to 70.dp, "PIT LANE" to 70.dp).forEach { (t, w) ->
                            Text(
                                t, if (w == 0.dp) Modifier.weight(1f) else Modifier.width(w),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = if (w == 70.dp) TextAlign.End else TextAlign.Start,
                            )
                        }
                    }
                    s.pitStops.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(p.lap?.toString() ?: "-", Modifier.width(40.dp), fontWeight = FontWeight.Bold)
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                TeamBar(s.teamColor(p.driver), 16.dp); Spacer(Modifier.width(6.dp))
                                Text(s.tla(p.driver), fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(6.dp))
                                p.lap?.let { lap -> s.compoundForLap(p.driver, lap + 1) }?.let { (c, n) -> TyreBadge(c, 16.dp, n) }
                            }
                            val best = p.stopDuration != null && p.stopDuration == fastestStop
                            Text(
                                p.stopDuration?.let { String.format(Locale.US, "%.1fs", it) } ?: "—",
                                Modifier.width(70.dp), style = TimingText, textAlign = TextAlign.End,
                                color = if (best) F1Colors.Purple else Color.Unspecified,
                                fontWeight = if (best) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(
                                p.laneDuration?.let { String.format(Locale.US, "%.1fs", it) } ?: "—",
                                Modifier.width(70.dp), style = TimingText, textAlign = TextAlign.End,
                            )
                        }
                    }
                    Text(
                        "Stopped is time stationary in the box; pit lane is entry to exit.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun RadioTab(s: SessionSnapshot, radio: RadioPlayer) {
    if (s.radio.isEmpty()) { EmptyState("No team radio published for this session."); return }
    val playing by radio.playing.collectAsStateWithLifecycle()
    val loading by radio.loading.collectAsStateWithLifecycle()
    val error by radio.error.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    val drivers = s.driverOrder().filter { d -> s.radio.any { it.driver == d } }
    val clips = s.radio.filter { filter.isBlank() || it.driver == filter }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item { DriverChips(s, drivers, filter.ifBlank { null }, { filter = it }, allowNone = true, noneLabel = "All") }
        if (error != null) item { Text(error!!, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        items(clips, key = { it.url }) { clip ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { radio.toggle(clip.url) }) {
                    when {
                        loading == clip.url -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        playing == clip.url -> Icon(Icons.Filled.Stop, "Stop")
                        else -> Icon(Icons.Filled.PlayArrow, "Play")
                    }
                }
                Spacer(Modifier.width(10.dp))
                TeamBar(s.teamColor(clip.driver))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.driver(clip.driver)?.displayName ?: s.tla(clip.driver), fontWeight = FontWeight.Bold)
                    Text(s.driver(clip.driver)?.teamName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(TimeFormat.localTime(clip.time, seconds = true), style = TimingText)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        }
    }
}

@Composable
fun WeatherTab(s: SessionSnapshot) {
    val latest = s.weather.lastOrNull()
    if (latest == null) { EmptyState("No weather data yet."); return }
    val timed = s.weather.filter { it.time != null }
    val t0 = timed.firstOrNull()?.time
    fun xs(list: List<WeatherSample>) = FloatArray(list.size) { ((list[it].time!!.epochSecond - t0!!.epochSecond) / 60f) }
    fun ys(list: List<WeatherSample>, f: (WeatherSample) -> Double?) = FloatArray(list.size) { f(list[it])?.toFloat() ?: Float.NaN }
    val minutes: (Float) -> String = { "${it.toInt()}m" }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Now") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("TRACK", latest.trackTemp?.let { "${fmt1(it)}°C" } ?: "—", Modifier.weight(1f), F1Colors.Soft)
                    StatTile("AIR", latest.airTemp?.let { "${fmt1(it)}°C" } ?: "—", Modifier.weight(1f))
                    StatTile("HUMIDITY", latest.humidity?.let { "${it.toInt()}%" } ?: "—", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("WIND", latest.windSpeed?.let { "${fmt1(it)} m/s ${compass(latest.windDirection)}" } ?: "—", Modifier.weight(1.3f), compact = true)
                    StatTile("PRESSURE", latest.pressure?.let { "${it.toInt()} hPa" } ?: "—", Modifier.weight(1f), compact = true)
                    StatTile("RAIN", if (latest.rainfall) "Yes" else "No", Modifier.weight(0.8f), if (latest.rainfall) F1Colors.Wet else MaterialTheme.colorScheme.onSurface, compact = true)
                }
            }
        }
        if (timed.size >= 3 && t0 != null) {
            item {
                SectionCard("Temperature (°C)") {
                    LineChart(
                        listOf(
                            ChartSeries("Track", xs(timed), ys(timed) { it.trackTemp }, F1Colors.Soft),
                            ChartSeries("Air", xs(timed), ys(timed) { it.airTemp }, F1Colors.PitBlue),
                        ),
                        height = 170.dp, xLabel = minutes,
                    )
                    ChartLegend(listOf("Track" to F1Colors.Soft, "Air" to F1Colors.PitBlue))
                }
            }
            item {
                SectionCard("Humidity (%)") {
                    LineChart(listOf(ChartSeries("Humidity", xs(timed), ys(timed) { it.humidity }, F1Colors.Intermediate)), height = 120.dp, xLabel = minutes)
                }
            }
            item {
                SectionCard("Wind speed (m/s)") {
                    LineChart(listOf(ChartSeries("Wind", xs(timed), ys(timed) { it.windSpeed }, F1Colors.Hard)), height = 120.dp, yMin = 0f, xLabel = minutes)
                }
            }
            if (timed.any { it.rainfall }) {
                item {
                    SectionCard("Rainfall") {
                        LineChart(
                            listOf(ChartSeries("Rain", xs(timed), FloatArray(timed.size) { if (timed[it].rainfall) 1f else 0f }, F1Colors.Wet, stepped = true)),
                            height = 70.dp, yMin = 0f, yMax = 1f, xLabel = minutes, yLabel = { if (it > 0.5f) "Rain" else "Dry" },
                        )
                    }
                }
            }
            item {
                Text(
                    "Minutes since the first reading of the session. Readings arrive about once a minute.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)

private fun compass(deg: Int?): String {
    if (deg == null) return ""
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[(((deg % 360) + 22) / 45) % 8]
}

@Composable
fun RaceControlTab(s: SessionSnapshot) {
    if (s.raceControl.isEmpty()) { EmptyState("No race control messages yet."); return }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        val changes = s.trackStatusHistory.filter { it.time != null }
        if (changes.size > 1) {
            item {
                SectionCard("Track status", Modifier.padding(12.dp)) {
                    changes.takeLast(12).reversed().forEach { c ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(TimeFormat.localTime(c.time, seconds = true), Modifier.width(76.dp), style = TimingText)
                            Pill(trackLabel(c.status, c.message), F1Colors.trackStatus(c.status))
                        }
                    }
                }
            }
        }
        items(s.raceControl) { m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(Modifier.width(64.dp)) {
                    Text(TimeFormat.localTime(m.time), style = TimingText)
                    m.lap?.let { Text("Lap $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.flag?.let { Pill(it, F1Colors.flag(it), if (it.uppercase() in setOf("RED", "BLUE")) Color.White else Color.Black) }
                        if (m.category.isNotBlank() && m.category != "Flag") {
                            Pill(m.category.replace(Regex("([a-z])([A-Z])"), "$1 $2").uppercase(), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
                        }
                        m.scope?.takeIf { it == "Sector" }?.let { m.sector?.let { sec -> Pill("SECTOR $sec", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface) } }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(m.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        }
    }
}

@Composable
fun ChampionshipTab(s: SessionSnapshot, isLive: Boolean) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                if (isLive) "Projected standings if the session finished now." else "Standings before and after this session.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SectionCard("Drivers") {
                s.championshipDrivers.forEach { d ->
                    ChampRow(
                        pos = d.predictedPosition ?: d.currentPosition,
                        change = if (d.currentPosition != null && d.predictedPosition != null) d.currentPosition - d.predictedPosition else 0,
                        name = s.driver(d.driver)?.displayName ?: s.tla(d.driver),
                        sub = s.driver(d.driver)?.teamName.orEmpty(),
                        color = s.teamColor(d.driver),
                        points = d.predictedPoints ?: d.currentPoints,
                        gained = (d.predictedPoints ?: 0.0) - (d.currentPoints ?: 0.0),
                    )
                }
            }
        }
        if (s.championshipTeams.isNotEmpty()) {
            item {
                SectionCard("Constructors") {
                    s.championshipTeams.forEach { t ->
                        val colour = s.drivers.values.firstOrNull { d -> t.team.startsWith(d.teamName, ignoreCase = true) || d.teamName.startsWith(t.team, ignoreCase = true) }?.teamColour
                        ChampRow(
                            pos = t.predictedPosition ?: t.currentPosition,
                            change = if (t.currentPosition != null && t.predictedPosition != null) t.currentPosition - t.predictedPosition else 0,
                            name = t.team, sub = "", color = F1Colors.team(colour),
                            points = t.predictedPoints ?: t.currentPoints,
                            gained = (t.predictedPoints ?: 0.0) - (t.currentPoints ?: 0.0),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChampRow(pos: Int?, change: Int, name: String, sub: String, color: Color, points: Double?, gained: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pos?.toString() ?: "-", Modifier.width(28.dp), fontWeight = FontWeight.Black)
        Text(
            when { change > 0 -> "▲$change"; change < 0 -> "▼${-change}"; else -> "" },
            Modifier.width(30.dp), fontSize = 11.sp,
            color = if (change > 0) F1Colors.Green else F1Colors.Soft,
        )
        TeamBar(color)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Bold)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(points?.let { fmtPts(it) } ?: "-", fontWeight = FontWeight.Black)
            if (gained > 0) Text("+${fmtPts(gained)}", fontSize = 11.sp, color = F1Colors.Green)
        }
    }
}

private fun fmtPts(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
