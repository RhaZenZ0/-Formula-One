package com.rhazenz0.f1info.ui.schedule

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rhazenz0.f1info.AppGraph
import com.rhazenz0.f1info.data.jolpica.Race
import com.rhazenz0.f1info.data.jolpica.RaceWeekend
import com.rhazenz0.f1info.data.timing.MeetingRef
import com.rhazenz0.f1info.data.timing.SessionRef
import com.rhazenz0.f1info.ui.components.EmptyState
import com.rhazenz0.f1info.ui.components.Loadable
import com.rhazenz0.f1info.ui.components.LoadableContent
import com.rhazenz0.f1info.ui.components.SectionCard
import com.rhazenz0.f1info.ui.components.userMessage
import com.rhazenz0.f1info.ui.theme.F1Colors
import com.rhazenz0.f1info.ui.theme.TimingText
import com.rhazenz0.f1info.util.TimeFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

data class RaceDetail(
    val weekend: RaceWeekend,
    val race: Race?,
    val qualifying: Race?,
    val sprint: Race?,
    val timing: MeetingRef?,
)

class RaceDetailViewModel(private val season: Int, private val round: Int) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<RaceDetail>>(Loadable.Loading)
    val state: StateFlow<Loadable<RaceDetail>> = _state

    init { load() }

    fun load() {
        _state.value = Loadable.Loading
        viewModelScope.launch {
            try {
                val j = AppGraph.jolpica
                val s = season.toString(); val r = round.toString()
                val weekend = j.schedule(s).firstOrNull { it.round == round }
                    ?: throw IllegalStateException("Race not found")
                val race = async { runCatching { j.raceResults(s, r) }.getOrNull() }
                val quali = async { runCatching { j.qualifyingResults(s, r) }.getOrNull() }
                val sprint = async { if (weekend.isSprintWeekend) runCatching { j.sprintResults(s, r) }.getOrNull() else null }
                val timing = async {
                    if (season < 2018) null
                    else runCatching { matchMeeting(AppGraph.meetings(season), weekend) }.getOrNull()
                }
                _state.value = Loadable.Ready(RaceDetail(weekend, race.await(), quali.await(), sprint.await(), timing.await()))
            } catch (e: Exception) {
                _state.value = Loadable.Error(e.userMessage())
            }
        }
    }

    private fun matchMeeting(meetings: List<MeetingRef>, weekend: RaceWeekend): MeetingRef? {
        meetings.firstOrNull { it.name.equals(weekend.name, ignoreCase = true) }?.let { return it }
        val raceDay = weekend.raceStart ?: return null
        return meetings.firstOrNull { m ->
            m.sessions.any { s -> s.start != null && kotlin.math.abs(s.start.epochSecond - raceDay.epochSecond) < 36 * 3600 && s.isRace }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaceDetailScreen(
    season: Int,
    round: Int,
    onBack: () -> Unit,
    onOpenSession: (SessionRef) -> Unit,
    vm: RaceDetailViewModel = viewModel(key = "race-$season-$round") { RaceDetailViewModel(season, round) },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text((state as? Loadable.Ready)?.value?.weekend?.name ?: "Round $round", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LoadableContent(state, onRetry = vm::load) { detail -> RaceDetailContent(detail, onOpenSession) }
    }
}

@Composable
private fun RaceDetailContent(detail: RaceDetail, onOpenSession: (SessionRef) -> Unit) {
    val tabs = buildList {
        add("Race")
        if (detail.sprint != null || detail.weekend.isSprintWeekend) add("Sprint")
        add("Qualifying")
    }
    var tab by remember { mutableStateOf(tabs.first()) }
    val now = Instant.now()

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Weekend") {
                val w = detail.weekend
                Text("${w.circuit}", fontWeight = FontWeight.Bold)
                Text("${w.locality}, ${w.country}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val timingSessions = detail.timing?.sessions.orEmpty()
                w.sessions.forEach { s ->
                    val ref = timingSessions.firstOrNull { it.sessionName.equals(s.name, ignoreCase = true) }
                        ?: timingSessions.firstOrNull { ts -> s.start != null && ts.start != null && kotlin.math.abs(ts.start.epochSecond - s.start.epochSecond) < 1800 }
                    val available = ref != null && (ref.start?.isBefore(now) == true)
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (available) Modifier.clickable { onOpenSession(ref!!) } else Modifier)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.name, Modifier.weight(1f))
                        Text("${TimeFormat.localDate(s.start)} ${TimeFormat.localTime(s.start)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (available) {
                            Spacer(Modifier.width(6.dp))
                            Text("TIMING", color = F1Colors.Red, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = F1Colors.Red)
                        }
                    }
                }
                if (detail.timing != null) {
                    Text(
                        "Tap a session to open laps, telemetry, tyres, radio, weather and race control.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) }) }
            }
        }
        item {
            when (tab) {
                "Race" -> ResultsTable(detail.race)
                "Sprint" -> ResultsTable(detail.sprint, sprint = true)
                else -> QualifyingTable(detail.qualifying)
            }
        }
    }
}

@Composable
private fun ResultsTable(race: Race?, sprint: Boolean = false) {
    val results = if (sprint) race?.sprintResults.orEmpty() else race?.results.orEmpty()
    if (results.isEmpty()) { EmptyState("No results yet."); return }
    SectionCard(if (sprint) "Sprint result" else "Race result") {
        results.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.positionText, Modifier.width(30.dp), fontWeight = FontWeight.Black)
                Column(Modifier.weight(1f)) {
                    Text(r.driver.fullName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${r.constructor.name} · Grid ${r.grid ?: "-"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(r.time?.time ?: r.status, style = TimingText, maxLines = 1)
                    if (r.fastestLap?.rank == "1") {
                        Text("Fastest lap", style = MaterialTheme.typography.labelSmall, color = F1Colors.Purple)
                    }
                }
                Text(r.points, Modifier.width(40.dp), fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun QualifyingTable(race: Race?) {
    val results = race?.qualifyingResults.orEmpty()
    if (results.isEmpty()) { EmptyState("No qualifying results yet."); return }
    SectionCard("Qualifying") {
        Row(Modifier.fillMaxWidth()) {
            Text("", Modifier.width(30.dp))
            Text("Driver", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            listOf("Q1", "Q2", "Q3").forEach { Text(it, Modifier.width(66.dp), style = MaterialTheme.typography.labelSmall) }
        }
        results.forEach { q ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(q.position, Modifier.width(30.dp), fontWeight = FontWeight.Black)
                Column(Modifier.weight(1f)) {
                    Text(q.driver.code ?: q.driver.familyName, fontWeight = FontWeight.Bold)
                    Text(q.constructor.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                listOf(q.q1, q.q2, q.q3).forEach { Text(it ?: "", Modifier.width(66.dp), style = TimingText) }
            }
        }
    }
}
