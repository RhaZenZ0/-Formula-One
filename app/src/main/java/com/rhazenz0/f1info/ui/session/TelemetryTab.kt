package com.rhazenz0.f1info.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhazenz0.f1info.data.timing.LapRecord
import com.rhazenz0.f1info.data.timing.LapTrace
import com.rhazenz0.f1info.data.timing.SessionSnapshot
import com.rhazenz0.f1info.data.timing.TelemetryData
import com.rhazenz0.f1info.data.timing.TelemetryParser
import com.rhazenz0.f1info.ui.components.ChartLegend
import com.rhazenz0.f1info.ui.components.ChartSeries
import com.rhazenz0.f1info.ui.components.EmptyState
import com.rhazenz0.f1info.ui.components.LineChart
import com.rhazenz0.f1info.ui.components.MapDot
import com.rhazenz0.f1info.ui.components.SectionCard
import com.rhazenz0.f1info.ui.components.Selector
import com.rhazenz0.f1info.ui.components.StatTile
import com.rhazenz0.f1info.ui.components.TeamBar
import com.rhazenz0.f1info.ui.components.TrackMap
import com.rhazenz0.f1info.ui.theme.F1Colors
import com.rhazenz0.f1info.ui.theme.TimingText
import com.rhazenz0.f1info.util.TimeFormat

sealed interface TelemetryUi {
    data object Idle : TelemetryUi
    data class Loading(val message: String) : TelemetryUi
    data class Error(val message: String) : TelemetryUi
    data class Ready(val data: TelemetryData) : TelemetryUi
}

private fun lapLabel(l: LapRecord) = "Lap ${l.lap} · ${TimeFormat.formatLapTime(l.lapTime)}"

/** Laps that have a time window, fastest first in the default choice. */
private fun SessionSnapshot.usableLaps(driver: String) = laps[driver].orEmpty().filter { it.end != null && it.lapTime != null }

@Composable
fun ArchiveTelemetryTab(s: SessionSnapshot, state: TelemetryUi, onLoad: () -> Unit) {
    when (state) {
        TelemetryUi.Idle -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Car telemetry: speed, throttle, brake, RPM and gear sampled about 3.7 times per second, plus car positions for a track map.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This downloads about 15–25 MB for a race. Use Wi-Fi if your data plan is limited.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onLoad) { Text("Load telemetry") }
        }
        is TelemetryUi.Loading -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is TelemetryUi.Error -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.message, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onLoad) { Text("Try again") }
        }
        is TelemetryUi.Ready -> LapComparison(s, state.data)
    }
}

@Composable
private fun LapComparison(s: SessionSnapshot, data: TelemetryData) {
    val order = s.driverOrder().filter { s.usableLaps(it).isNotEmpty() && data.car.containsKey(it) }
    if (order.isEmpty()) { EmptyState("No laps with telemetry in this session."); return }

    var driverA by rememberSaveable { mutableStateOf(order.first()) }
    var driverB by rememberSaveable { mutableStateOf(order.getOrNull(1).orEmpty()) }
    var lapA by rememberSaveable(driverA) { mutableIntStateOf(s.usableLaps(driverA).minByOrNull { it.lapTime!! }?.lap ?: 0) }
    var lapB by rememberSaveable(driverB) { mutableIntStateOf(s.usableLaps(driverB).minByOrNull { it.lapTime!! }?.lap ?: 0) }

    val recA = s.usableLaps(driverA).firstOrNull { it.lap == lapA }
    val recB = if (driverB.isBlank()) null else s.usableLaps(driverB).firstOrNull { it.lap == lapB }
    val traceA = remember(data, recA) { recA?.let { TelemetryParser.lapTrace(data, it) } }
    val traceB = remember(data, recB) { recB?.let { TelemetryParser.lapTrace(data, it) } }

    val colorA = s.teamColor(driverA)
    val colorB = s.teamColor(driverB).let { if (driverB.isNotBlank() && s.drivers[driverB]?.teamName == s.drivers[driverA]?.teamName) Color(0xFFDADAE0) else it }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Driver A", Modifier.padding(start = 12.dp, top = 6.dp), style = MaterialTheme.typography.labelSmall)
            DriverChips(s, order, driverA, { driverA = it })
            Selector("Lap", s.usableLaps(driverA), recA, ::lapLabel, { lapA = it.lap }, Modifier.padding(horizontal = 12.dp))
        }
        item {
            Text("Compare with", Modifier.padding(start = 12.dp, top = 6.dp), style = MaterialTheme.typography.labelSmall)
            DriverChips(s, order, driverB.ifBlank { null }, { driverB = it }, allowNone = true)
            if (driverB.isNotBlank()) {
                Selector("Lap", s.usableLaps(driverB), recB, ::lapLabel, { lapB = it.lap }, Modifier.padding(horizontal = 12.dp))
            }
        }
        if (traceA == null) {
            item { EmptyState("Not enough telemetry samples for this lap.") }
            return@LazyColumn
        }
        val b = traceB
        val scaleB = if (b != null && b.distance.last() > 0) traceA.distance.last() / b.distance.last() else 1f
        val xB = b?.let { tr -> FloatArray(tr.distance.size) { tr.distance[it] * scaleB } }
        val legend = buildList {
            add("${s.tla(driverA)} L${traceA.lap}" to colorA)
            if (b != null) add("${s.tla(driverB)} L${b.lap}" to colorB)
        }
        item {
            SectionCard("Summary", Modifier.padding(horizontal = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("TOP SPEED", "${traceA.maxSpeed.toInt()}${b?.let { " / ${it.maxSpeed.toInt()}" } ?: ""}", Modifier.weight(1f), compact = true)
                    StatTile("FULL THROTTLE", "${traceA.fullThrottlePercent.toInt()}%${b?.let { " / ${it.fullThrottlePercent.toInt()}%" } ?: ""}", Modifier.weight(1f), compact = true)
                    StatTile("BRAKING", "${traceA.brakingPercent.toInt()}%${b?.let { " / ${it.brakingPercent.toInt()}%" } ?: ""}", Modifier.weight(1f), compact = true)
                }
                if (recA != null && recB != null && recA.lapTime != null && recB.lapTime != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${s.tla(driverA)} ${TimeFormat.formatLapTime(recA.lapTime)} vs ${s.tla(driverB)} ${TimeFormat.formatLapTime(recB.lapTime)} " +
                            "(${TimeFormat.formatDelta(recB.lapTime - recA.lapTime)} for ${s.tla(driverB)})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ChartLegend(legend)
            }
        }
        if (b != null && xB != null) {
            item {
                SectionCard("Gap (${s.tla(driverB)} relative to ${s.tla(driverA)})", Modifier.padding(horizontal = 12.dp)) {
                    val delta = timeDelta(traceA, b, xB)
                    LineChart(
                        listOf(ChartSeries("delta", traceA.distance, delta, colorB)),
                        height = 130.dp,
                        xLabel = { "${(it / 1000).let { km -> String.format(java.util.Locale.US, "%.1f", km) }}km" },
                        yLabel = { String.format(java.util.Locale.US, "%+.1fs", it) },
                    )
                    Text(
                        "Above zero: ${s.tla(driverB)} is behind at that point of the lap.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        fun series(sel: (LapTrace) -> FloatArray, stepped: Boolean = false) = buildList {
            add(ChartSeries(s.tla(driverA), traceA.distance, sel(traceA), colorA, stepped = stepped))
            if (b != null && xB != null) add(ChartSeries(s.tla(driverB), xB, sel(b), colorB, dashed = false, stepped = stepped))
        }
        val km: (Float) -> String = { String.format(java.util.Locale.US, "%.1fkm", it / 1000) }
        item {
            SectionCard("Speed (km/h)", Modifier.padding(horizontal = 12.dp)) { LineChart(series({ it.speed }), height = 180.dp, xLabel = km) }
        }
        item {
            SectionCard("Throttle (%)", Modifier.padding(horizontal = 12.dp)) { LineChart(series({ it.throttle }), height = 110.dp, yMin = 0f, yMax = 100f, xLabel = km) }
        }
        item {
            SectionCard("Brake", Modifier.padding(horizontal = 12.dp)) {
                LineChart(series({ it.brake }, stepped = true), height = 80.dp, yMin = 0f, yMax = 100f, xLabel = km, yLabel = { if (it > 50) "On" else "Off" })
            }
        }
        item {
            SectionCard("RPM", Modifier.padding(horizontal = 12.dp)) { LineChart(series({ it.rpm }), height = 120.dp, xLabel = km) }
        }
        item {
            SectionCard("Gear", Modifier.padding(horizontal = 12.dp)) {
                LineChart(series({ it.gear }, stepped = true), height = 110.dp, yMin = 0f, yMax = 8f, xLabel = km)
            }
        }
        if (traceA.mapX.size > 10) {
            item {
                SectionCard("${s.tla(driverA)} lap ${traceA.lap} · speed on track", Modifier.padding(horizontal = 12.dp)) {
                    TrackMap(traceA.mapX, traceA.mapY, speeds = traceA.mapSpeed, height = 280.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Slow", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(6.dp))
                        listOf(0f, 0.33f, 0.66f, 1f).forEach { t ->
                            Box(Modifier.width(24.dp).height(6.dp).background(com.rhazenz0.f1info.ui.components.speedColor(t, 0f, 1f)))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Fast", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Time difference of B against A at each of A's distance samples (B distances pre-scaled). */
private fun timeDelta(a: LapTrace, b: LapTrace, xB: FloatArray): FloatArray {
    val out = FloatArray(a.distance.size)
    var j = 0
    for (i in a.distance.indices) {
        val d = a.distance[i]
        while (j < xB.size - 2 && xB[j + 1] < d) j++
        val d0 = xB[j]; val d1 = xB[(j + 1).coerceAtMost(xB.size - 1)]
        val t0 = b.time[j]; val t1 = b.time[(j + 1).coerceAtMost(b.time.size - 1)]
        val tB = if (d1 > d0) t0 + (t1 - t0) * ((d - d0) / (d1 - d0)).coerceIn(0f, 1f) else t0
        out[i] = tB - a.time[i]
    }
    return out
}

/** Latest live values for one car. */
data class LiveCar(val speed: Int, val gear: Int, val throttle: Int, val brake: Boolean, val rpm: Int)

data class LiveTelemetry(
    val cars: Map<String, LiveCar> = emptyMap(),
    val positions: Map<String, Pair<Float, Float>> = emptyMap(),
    val outlineX: FloatArray = FloatArray(0),
    val outlineY: FloatArray = FloatArray(0),
) {
    val hasData: Boolean get() = cars.isNotEmpty() || positions.isNotEmpty()
}

@Composable
fun LiveTelemetryTab(s: SessionSnapshot, live: LiveTelemetry, hasToken: Boolean, onOpenSettings: () -> Unit) {
    if (!live.hasData) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (hasToken) "Waiting for live car data… It only flows while cars are on track."
                else "Formula 1 sends live car telemetry and positions only to F1 TV subscribers.",
                textAlign = TextAlign.Center,
            )
            if (!hasToken) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add your F1 TV token in Settings to see it here. Telemetry for finished sessions is free in the Sessions tab.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenSettings) { Text("Open settings") }
            }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        if (live.positions.isNotEmpty()) {
            item {
                SectionCard("Track", Modifier.padding(12.dp)) {
                    TrackMap(
                        live.outlineX, live.outlineY,
                        dots = live.positions.map { (n, p) -> MapDot(p.first, p.second, s.teamColor(n), s.tla(n)) },
                        height = 300.dp,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                listOf("DRIVER" to 80.dp, "KM/H" to 52.dp, "GEAR" to 40.dp, "RPM" to 56.dp).forEach { (t, w) ->
                    Text(t, Modifier.width(w), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("THROTTLE / BRAKE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        items(s.driverOrder().filter { live.cars.containsKey(it) }, key = { it }) { n ->
            val c = live.cars.getValue(n)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.width(80.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeamBar(s.teamColor(n), 16.dp); Spacer(Modifier.width(6.dp))
                    Text(s.tla(n), fontWeight = FontWeight.Bold)
                }
                Text(c.speed.toString(), Modifier.width(52.dp), style = TimingText)
                Text(if (c.gear == 0) "N" else c.gear.toString(), Modifier.width(40.dp), style = TimingText)
                Text(c.rpm.toString(), Modifier.width(56.dp), style = TimingText)
                Box(Modifier.weight(1f).height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp))) {
                    Box(Modifier.fillMaxWidth(c.throttle / 100f).fillMaxHeight().background(F1Colors.Green, RoundedCornerShape(5.dp)))
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.width(14.dp).height(10.dp).background(if (c.brake) F1Colors.Red else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)))
            }
        }
        item {
            Text(
                "Speed, gear, RPM, throttle and brake update about 3.7 times per second.",
                Modifier.padding(12.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
