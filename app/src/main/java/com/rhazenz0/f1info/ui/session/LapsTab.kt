package com.rhazenz0.f1info.ui.session

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
import androidx.compose.material3.HorizontalDivider
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
import com.rhazenz0.f1info.data.timing.LapRecord
import com.rhazenz0.f1info.data.timing.SessionSnapshot
import com.rhazenz0.f1info.ui.components.ChartSeries
import com.rhazenz0.f1info.ui.components.EmptyState
import com.rhazenz0.f1info.ui.components.LineChart
import com.rhazenz0.f1info.ui.components.MiniSectors
import com.rhazenz0.f1info.ui.components.SectionCard
import com.rhazenz0.f1info.ui.components.StatTile
import com.rhazenz0.f1info.ui.components.TyreBadge
import com.rhazenz0.f1info.ui.theme.F1Colors
import com.rhazenz0.f1info.ui.theme.TimingText
import com.rhazenz0.f1info.util.TimeFormat

private data class SessionBests(
    val lap: Pair<String, Double>?,
    val sectors: List<Pair<String, Double>?>,
    val speedTrap: Pair<String, Int>?,
)

private fun bests(s: SessionSnapshot): SessionBests {
    val all = s.laps.values.flatten()
    val lap = all.filter { it.lapTime != null }.minByOrNull { it.lapTime!! }?.let { it.driver to it.lapTime!! }
    val sectors = (0..2).map { i ->
        all.filter { it.sectors.getOrNull(i) != null }.minByOrNull { it.sectors[i]!! }?.let { it.driver to it.sectors[i]!! }
    }
    val trap = all.filter { it.speedTrap != null }.maxByOrNull { it.speedTrap!! }?.let { it.driver to it.speedTrap!! }
    return SessionBests(lap, sectors, trap)
}

fun SessionSnapshot.compoundForLap(driver: String, lap: Int): Pair<String, Boolean>? =
    stints[driver]?.firstOrNull { lap in it.lapStart..it.lapEnd }?.let { it.compound to it.isNew }

@Composable
fun LapsTab(s: SessionSnapshot) {
    if (s.laps.isEmpty()) { EmptyState("No completed laps yet."); return }
    val order = s.driverOrder().filter { s.laps.containsKey(it) }
    var selected by rememberSaveable { mutableStateOf(order.firstOrNull().orEmpty()) }
    if (selected !in s.laps) selected = order.firstOrNull().orEmpty()
    val laps = s.laps[selected].orEmpty()
    val b = bests(s)
    val personalBest = laps.mapNotNull { it.lapTime }.minOrNull()
    val personalSectors = (0..2).map { i -> laps.mapNotNull { it.sectors.getOrNull(i) }.minOrNull() }

    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item { DriverChips(s, order, selected, { selected = it }) }
        item {
            SectionCard("Session bests", Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("FASTEST LAP", b.lap?.let { "${s.tla(it.first)} ${TimeFormat.formatLapTime(it.second)}" } ?: "—", Modifier.weight(1f), F1Colors.Purple)
                    StatTile("SPEED TRAP", b.speedTrap?.let { "${s.tla(it.first)} ${it.second}" } ?: "—", Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    b.sectors.forEachIndexed { i, best ->
                        StatTile(
                            "S${i + 1}" + (best?.let { " · ${s.tla(it.first)}" } ?: ""),
                            best?.let { TimeFormat.formatLapTime(it.second) } ?: "—", Modifier.weight(1f), F1Colors.Purple, compact = true,
                        )
                    }
                }
                val ideal = personalSectors.takeIf { l -> l.all { it != null } }?.sumOf { it!! }
                if (ideal != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${s.tla(selected)} ideal lap (best sectors combined): ${TimeFormat.formatLapTime(ideal)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            val timed = laps.filter { it.lapTime != null }
            if (timed.size >= 2) {
                SectionCard("${s.tla(selected)} lap times", Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    val sorted = timed.mapNotNull { it.lapTime }.sorted()
                    val median = sorted[sorted.size / 2]
                    LineChart(
                        listOf(
                            ChartSeries(
                                s.tla(selected),
                                FloatArray(timed.size) { timed[it].lap.toFloat() },
                                FloatArray(timed.size) { timed[it].lapTime!!.toFloat() },
                                s.teamColor(selected),
                            ),
                        ),
                        height = 170.dp,
                        yMin = (sorted.first() - 0.5).toFloat(),
                        yMax = (median * 1.08).toFloat().coerceAtLeast(sorted.first().toFloat() + 1f),
                        yLabel = { TimeFormat.formatLapTime(it.toDouble()).substringBeforeLast('.') },
                        xLabel = { "L${it.toInt()}" },
                    )
                    Text(
                        "Slow laps (safety car, pit stops) are cut off at the top.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                listOf("LAP" to 34.dp, "TIME" to 70.dp, "S1" to 52.dp, "S2" to 52.dp, "S3" to 52.dp).forEach { (t, w) ->
                    Text(t, Modifier.width(w), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("ST", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        items(laps.reversed(), key = { it.lap }) { lap ->
            LapRow(s, lap, b, personalBest, personalSectors)
        }
    }
}

@Composable
private fun LapRow(
    s: SessionSnapshot,
    lap: LapRecord,
    b: SessionBests,
    personalBest: Double?,
    personalSectors: List<Double?>,
) {
    fun colorFor(value: Double?, overall: Double?, personal: Double?): Color = when {
        value == null -> Color.Unspecified
        overall != null && value <= overall -> F1Colors.Purple
        personal != null && value <= personal -> F1Colors.Green
        else -> Color.Unspecified
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(lap.lap.toString(), Modifier.width(34.dp), fontWeight = FontWeight.Bold)
            Text(
                TimeFormat.formatLapTime(lap.lapTime), Modifier.width(70.dp), style = TimingText,
                color = colorFor(lap.lapTime, b.lap?.second, personalBest), fontWeight = FontWeight.Bold,
            )
            lap.sectors.forEachIndexed { i, v ->
                Text(
                    v?.let { TimeFormat.formatLapTime(it) } ?: "—", Modifier.width(52.dp), style = TimingText,
                    color = colorFor(v, b.sectors[i]?.second, personalSectors[i]),
                )
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (lap.pitInLap) Text("IN ", fontSize = 10.sp, color = F1Colors.PitBlue, fontWeight = FontWeight.Bold)
                if (lap.pitOutLap) Text("OUT ", fontSize = 10.sp, color = F1Colors.PitBlue, fontWeight = FontWeight.Bold)
                s.compoundForLap(lap.driver, lap.lap)?.let { (c, isNew) -> TyreBadge(c, 16.dp, isNew); Spacer(Modifier.width(4.dp)) }
                Text(lap.speedTrap?.toString() ?: "", style = TimingText)
            }
        }
        if (lap.segments.any { it.isNotEmpty() }) {
            Spacer(Modifier.height(4.dp))
            MiniSectors(lap.segments, Modifier.fillMaxWidth().padding(start = 34.dp))
        }
        val speeds = listOfNotNull(
            lap.i1Speed?.let { "I1 $it" }, lap.i2Speed?.let { "I2 $it" }, lap.finishSpeed?.let { "FL $it" },
        )
        if (speeds.isNotEmpty()) {
            Text(
                speeds.joinToString("  ") + " km/h", Modifier.padding(start = 34.dp, top = 2.dp),
                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}
