package com.rhazenz0.f1info.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhazenz0.f1info.data.timing.SessionSnapshot
import com.rhazenz0.f1info.data.timing.TimingRow
import com.rhazenz0.f1info.ui.components.ChartSeries
import com.rhazenz0.f1info.ui.components.EmptyState
import com.rhazenz0.f1info.ui.components.LineChart
import com.rhazenz0.f1info.ui.components.Pill
import com.rhazenz0.f1info.ui.components.SectionCard
import com.rhazenz0.f1info.ui.components.TeamBar
import com.rhazenz0.f1info.ui.components.TyreBadge
import com.rhazenz0.f1info.ui.theme.F1Colors
import com.rhazenz0.f1info.ui.theme.TimingText

enum class SessionTab(val title: String) {
    Positions("Positions"),
    Track("Track"),
    Laps("Lap times"),
    Telemetry("Telemetry"),
    Pits("Pit stops"),
    Radio("Team radio"),
    Weather("Weather"),
    RaceControl("Race control"),
    Championship("Championship"),
}

fun SessionSnapshot.teamColor(number: String): Color = F1Colors.team(drivers[number]?.teamColour)

/** Tabs shared by archived sessions and the live view. */
@Composable
fun SessionTabsView(
    snapshot: SessionSnapshot,
    isLive: Boolean,
    radio: RadioPlayer,
    trackTab: @Composable () -> Unit,
    telemetryTab: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = SessionTab.entries.filter { it != SessionTab.Championship || snapshot.championshipDrivers.isNotEmpty() }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val selected = tabs.getOrElse(index) { tabs.first() }

    Column(modifier.fillMaxSize()) {
        SessionHeader(snapshot, isLive)
        ScrollableTabRow(
            selectedTabIndex = tabs.indexOf(selected),
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            tabs.forEachIndexed { i, tab ->
                Tab(
                    selected = tab == selected,
                    onClick = { index = i },
                    text = { Text(tab.title) },
                    selectedContentColor = F1Colors.Red,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (selected) {
            SessionTab.Positions -> PositionsTab(snapshot)
            SessionTab.Track -> trackTab()
            SessionTab.Laps -> LapsTab(snapshot)
            SessionTab.Telemetry -> telemetryTab()
            SessionTab.Pits -> PitStopsTab(snapshot)
            SessionTab.Radio -> RadioTab(snapshot, radio)
            SessionTab.Weather -> WeatherTab(snapshot)
            SessionTab.RaceControl -> RaceControlTab(snapshot)
            SessionTab.Championship -> ChampionshipTab(snapshot, isLive)
        }
    }
}

@Composable
private fun SessionHeader(s: SessionSnapshot, isLive: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isLive && s.sessionStatus == "Started") Pill("LIVE", F1Colors.Red, Color.White)
        if (s.sessionStatus.isNotBlank()) Pill(s.sessionStatus.uppercase(), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
        if (s.trackMessage.isNotBlank() && s.sessionStatus != "Finalised" && s.sessionStatus != "Ends") {
            Pill(trackLabel(s.trackStatus, s.trackMessage), F1Colors.trackStatus(s.trackStatus))
        }
        Spacer(Modifier.weight(1f))
        val lap = if (s.currentLap != null && s.totalLaps != null && s.totalLaps > 0) "Lap ${s.currentLap}/${s.totalLaps}" else null
        Text(
            listOfNotNull(lap, s.remaining?.takeIf { s.sessionType != "Race" || lap == null }).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

fun trackLabel(code: String, message: String): String = when (code) {
    "1" -> "TRACK CLEAR"
    "2" -> "YELLOW FLAG"
    "4" -> "SAFETY CAR"
    "5" -> "RED FLAG"
    "6" -> "VIRTUAL SC"
    "7" -> "VSC ENDING"
    else -> message.uppercase()
}

/** Horizontally scrollable driver chips. */
@Composable
fun DriverChips(
    snapshot: SessionSnapshot,
    order: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    allowNone: Boolean = false,
    noneLabel: String = "None",
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (allowNone) {
            FilterChip(selected = selected == null, onClick = { onSelect("") }, label = { Text(noneLabel) })
        }
        order.forEach { number ->
            val color = snapshot.teamColor(number)
            FilterChip(
                selected = selected == number,
                onClick = { onSelect(number) },
                label = { Text(snapshot.tla(number), fontWeight = FontWeight.Bold) },
                leadingIcon = { TeamBar(color, 14.dp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = 0.35f)),
            )
        }
    }
}

/** Drivers in classification order, falling back to the driver list. */
fun SessionSnapshot.driverOrder(): List<String> {
    val fromTiming = timing.map { it.number }
    return fromTiming + drivers.values.sortedBy { it.line }.map { it.number }.filter { it !in fromTiming }
}

@Composable
fun PositionsTab(s: SessionSnapshot) {
    if (s.timing.isEmpty()) { EmptyState("No timing data yet."); return }
    val isRace = s.sessionType.equals("Race", ignoreCase = true)
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                HeaderText("POS", 30.dp); HeaderText("DRIVER", null, Modifier.weight(1f))
                HeaderText("TYRE", 46.dp); HeaderText(if (isRace) "GAP" else "BEST", 78.dp, align = TextAlign.End)
                HeaderText(if (isRace) "INT" else "GAP", 66.dp, align = TextAlign.End)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        items(s.timing, key = { it.number }) { row -> TowerRow(s, row, isRace) }
        val progress = s.lapPositions
        if (isRace && progress.values.any { it.size > 2 }) {
            item {
                Spacer(Modifier.height(12.dp))
                SectionCard("Race progress", Modifier.padding(horizontal = 12.dp)) {
                    val series = s.driverOrder().mapNotNull { n ->
                        val laps = progress[n] ?: return@mapNotNull null
                        val xs = FloatArray(laps.size) { it.toFloat() }
                        val ys = FloatArray(laps.size) { laps[it]?.toFloat() ?: Float.NaN }
                        ChartSeries(s.tla(n), xs, ys, s.teamColor(n))
                    }
                    LineChart(
                        series, height = 300.dp, yMin = 1f, yMax = s.drivers.size.coerceAtLeast(2).toFloat(),
                        invertY = true, xLabel = { "L${it.toInt()}" }, yLabel = { "P${it.toInt()}" },
                    )
                    Text(
                        "Position at the end of each lap (lap 0 is the grid).",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderText(text: String, width: androidx.compose.ui.unit.Dp?, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        modifier = if (width != null) modifier.width(width) else modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
    )
}

@Composable
private fun TowerRow(s: SessionSnapshot, row: TimingRow, isRace: Boolean) {
    val driver = s.driver(row.number)
    val dim = row.retired || row.knockedOut || row.stopped
    val textColor = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.position?.toString() ?: "-", Modifier.width(30.dp), fontWeight = FontWeight.Black, color = textColor)
        TeamBar(s.teamColor(row.number))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.tla(row.number), fontWeight = FontWeight.Bold, color = textColor)
                Spacer(Modifier.width(6.dp))
                val gained = if (isRace && row.gridPosition != null && row.position != null && row.gridPosition > 0) row.gridPosition - row.position else null
                if (gained != null && gained != 0) {
                    Text(
                        if (gained > 0) "▲$gained" else "▼${-gained}",
                        fontSize = 11.sp,
                        color = if (gained > 0) F1Colors.Green else F1Colors.Soft,
                    )
                }
                Spacer(Modifier.width(6.dp))
                when {
                    row.retired -> Pill("OUT", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    row.knockedOut -> Pill("KO", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                    row.inPit -> Pill("PIT", F1Colors.PitBlue, Color.White)
                    row.pitOut -> Pill("OUT LAP", F1Colors.PitBlue.copy(alpha = 0.6f), Color.White)
                }
            }
            Text(
                listOfNotNull(driver?.teamName, row.laps?.let { "$it laps" }, row.pitStops?.takeIf { it > 0 }?.let { if (it == 1) "1 stop" else "$it stops" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(Modifier.width(46.dp), verticalAlignment = Alignment.CenterVertically) {
            if (row.compound != null) {
                TyreBadge(row.compound, 20.dp)
                Spacer(Modifier.width(3.dp))
                Text(row.tyreAge?.toString() ?: "", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val primary = if (isRace) (if (row.position == 1) "Leader" else row.gap) else row.bestLap
        val secondary = if (isRace) row.interval else row.gap
        Text(primary, Modifier.width(78.dp), style = TimingText, textAlign = TextAlign.End, maxLines = 1, color = textColor)
        Text(secondary, Modifier.width(66.dp), style = TimingText, textAlign = TextAlign.End, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
}
