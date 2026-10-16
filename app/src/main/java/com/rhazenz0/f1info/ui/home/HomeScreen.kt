package com.rhazenz0.f1info.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.rhazenz0.f1info.data.jolpica.StandingsList
import com.rhazenz0.f1info.ui.components.Loadable
import com.rhazenz0.f1info.ui.components.LoadableContent
import com.rhazenz0.f1info.ui.components.SectionCard
import com.rhazenz0.f1info.ui.components.UpdateCard
import com.rhazenz0.f1info.data.update.UpdateManager
import com.rhazenz0.f1info.ui.components.userMessage
import com.rhazenz0.f1info.ui.theme.F1Colors
import com.rhazenz0.f1info.util.TimeFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

data class HomeData(
    val next: RaceWeekend?,
    val lastRace: Race?,
    val drivers: StandingsList?,
    val constructors: StandingsList?,
)

class HomeViewModel : ViewModel() {
    private val _state = MutableStateFlow<Loadable<HomeData>>(Loadable.Loading)
    val state: StateFlow<Loadable<HomeData>> = _state

    init { load() }

    fun load() {
        _state.value = Loadable.Loading
        viewModelScope.launch {
            try {
                val j = AppGraph.jolpica
                val schedule = async { j.schedule() }
                val last = async { runCatching { j.lastRaceResults() }.getOrNull() }
                val drivers = async { runCatching { j.driverStandings() }.getOrNull() }
                val teams = async { runCatching { j.constructorStandings() }.getOrNull() }
                val now = Instant.now()
                val next = schedule.await().firstOrNull { !it.isFinished(now) }
                _state.value = Loadable.Ready(HomeData(next, last.await(), drivers.await(), teams.await()))
            } catch (e: Exception) {
                _state.value = Loadable.Error(e.userMessage())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenRace: (Int, Int) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLive: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val update by UpdateManager.state.collectAsStateWithLifecycle()
    val dismissed by UpdateManager.dismissedVersion.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("F1 Info", fontWeight = FontWeight.Black) },
            actions = {
                IconButton(onClick = vm::load) { Icon(Icons.Filled.Refresh, "Refresh") }
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LoadableContent(state, onRetry = vm::load) { data ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    UpdateCard(
                        state = update,
                        currentVersion = UpdateManager.currentVersion,
                        dismissedVersion = dismissed,
                        onUpdate = UpdateManager::install,
                        onLater = UpdateManager::dismiss,
                        onCancel = UpdateManager::cancel,
                    )
                }
                data.next?.let { next -> item { NextRaceCard(next, onOpenRace, onOpenLive) } }
                data.lastRace?.let { race -> item { LastRaceCard(race, onOpenRace) } }
                data.drivers?.let { list ->
                    item {
                        SectionCard("Drivers' championship") {
                            list.drivers.take(5).forEach { s ->
                                StandingLine(
                                    s.positionText, s.driver.fullName,
                                    s.constructors.firstOrNull()?.name.orEmpty(), s.points,
                                )
                            }
                        }
                    }
                }
                data.constructors?.let { list ->
                    item {
                        SectionCard("Constructors' championship") {
                            list.constructors.take(5).forEach { s ->
                                StandingLine(s.positionText, s.constructor.name, s.constructor.nationality, s.points)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NextRaceCard(next: RaceWeekend, onOpenRace: (Int, Int) -> Unit, onOpenLive: () -> Unit) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1000); now = Instant.now() }
    }
    val upcoming = next.sessions.firstOrNull { (it.start ?: Instant.MIN).plusSeconds(2 * 3600).isAfter(now) }
    val inProgress = upcoming?.start?.let { it.isBefore(now) } == true

    SectionCard("Next race · Round ${next.round}", Modifier.clickable { onOpenRace(next.season, next.round) }) {
        Text(next.name, style = MaterialTheme.typography.headlineSmall)
        Text("${next.circuit} · ${next.locality}, ${next.country}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (upcoming != null) {
            Text(
                if (inProgress) "${upcoming.name} is on now" else "${upcoming.name} starts in",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inProgress) {
                TextButton(onClick = onOpenLive) { Text("Open live timing", color = F1Colors.Red, fontWeight = FontWeight.Bold) }
            } else {
                Text(
                    TimeFormat.countdown(upcoming.start ?: now, now),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = F1Colors.Red,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        next.sessions.forEach { s ->
            val done = s.start?.plusSeconds(2 * 3600)?.isBefore(now) == true
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    s.name, Modifier.weight(1f),
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${TimeFormat.localDate(s.start)}  ${TimeFormat.localTime(s.start)}",
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text("Times shown in your time zone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LastRaceCard(race: Race, onOpenRace: (Int, Int) -> Unit) {
    SectionCard(
        "Last race · ${race.raceName}",
        Modifier.clickable { onOpenRace(race.season.toIntOrNull() ?: 0, race.round.toIntOrNull() ?: 0) },
    ) {
        race.results.take(3).forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf("1st", "2nd", "3rd")[i], Modifier.width(44.dp), fontWeight = FontWeight.Black,
                    color = listOf(F1Colors.Yellow, F1Colors.Hard, androidx.compose.ui.graphics.Color(0xFFCD7F32))[i],
                )
                Column(Modifier.weight(1f)) {
                    Text(r.driver.fullName, fontWeight = FontWeight.Bold)
                    Text(r.constructor.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(r.time?.time ?: r.status, style = MaterialTheme.typography.bodySmall)
            }
        }
        race.results.firstOrNull { it.fastestLap?.rank == "1" }?.let { fl ->
            Spacer(Modifier.height(6.dp))
            Text(
                "Fastest lap: ${fl.driver.fullName} · ${fl.fastestLap?.time?.time.orEmpty()} (lap ${fl.fastestLap?.lap.orEmpty()})",
                style = MaterialTheme.typography.bodySmall,
                color = F1Colors.Purple,
            )
        }
    }
}

@Composable
private fun StandingLine(pos: String, name: String, sub: String, points: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pos, Modifier.width(28.dp), fontWeight = FontWeight.Black)
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$points pts", fontWeight = FontWeight.Bold)
    }
}
